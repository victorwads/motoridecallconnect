package dev.wads.motoridecallconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.audio.AudioCapturer
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import dev.wads.motoridecallconnect.stt.SpeechRecognizerHelper
import dev.wads.motoridecallconnect.stt.SttEngine
import dev.wads.motoridecallconnect.stt.WhisperModelCatalog
import dev.wads.motoridecallconnect.transport.SignalingClient
import dev.wads.motoridecallconnect.transport.WebRtcClient
import dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import dev.wads.motoridecallconnect.vad.SimpleVad
import kotlinx.coroutines.launch
import org.webrtc.*
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AudioService : LifecycleService(), AudioCapturer.AudioCapturerListener, SpeechRecognizerHelper.SpeechRecognitionListener, SignalingClient.SignalingListener {

    companion object {
        private const val TAG = "AudioService"
        private const val CONTROL_CHANNEL_LABEL = "trip-control"
        private const val REMOTE_TRACK_GAIN = 2.0
        private const val TARGET_VOICE_CALL_VOLUME_RATIO = 0.95f
        private const val TARGET_MUSIC_VOLUME_RATIO = 0.80f
        private const val PIPELINE_LOG_INTERVAL_MS = 5_000L
        private const val AUDIO_SAMPLE_RATE_HZ = 48_000
        private const val AUDIO_BYTES_PER_SAMPLE = 2

        // Tuning knobs for chunk-based transcription behavior.
        private const val TRANSCRIPTION_MIN_CONTEXT_MS = 3_000L
        private const val TRANSCRIPTION_SILENCE_FLUSH_MS = 3_000L
        private const val TRANSCRIPTION_MAX_CHUNK_MS = 45_000L
    }

    private val binder = LocalBinder()
    private val CHANNEL_ID = "AudioServiceChannel"
    private lateinit var audioCapturer: AudioCapturer
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private lateinit var signalingClient: SignalingClient
    private lateinit var audioManager: AudioManager
    private lateinit var vad: SimpleVad
    private lateinit var webRtcClient: WebRtcClient
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    private val transcriptionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var callback: ServiceCallback? = null

    // Service State
    private var currentMode = OperatingMode.VOICE_COMMAND
    private var startCommand = "iniciar"
    private var stopCommand = "parar"
    private var isTransmitting = false
    private var isTripActive = false
    private var currentTripId: String? = null
    private var currentTripHostUid: String? = null
    private var currentTripPath: String? = null
    private var connectedPeer: Device? = null
    private var connectionStatus = ConnectionStatus.DISCONNECTED
    private val audioBuffer = mutableListOf<Byte>()
    private val transcriptionStateLock = Any()
    private var lastTransmissionTime = 0L
    private var totalFramesReceived = 0L
    private var totalChunksDispatched = 0L
    private var bufferedAudioDurationMs = 0L
    private var consecutiveSilenceDurationMs = 0L
    private var chunkHadSpeech = false
    private var isAudioCaptureRunning = false
    private var isHostingEnabled = false
    private var sttEngine = SttEngine.WHISPER
    private var whisperModelId = WhisperModelCatalog.defaultOption.id
    private var isRtcConnected = false
    private var controlDataChannel: DataChannel? = null
    private var pendingTripStatusSync = false
    private var isRemoteTransmitting = false
    private var communicationProfileApplied = false
    private var savedAudioMode: Int? = null
    private var savedSpeakerphoneOn: Boolean? = null
    private var savedMicrophoneMute: Boolean? = null
    private var savedVoiceCallVolume: Int? = null
    private var savedMusicVolume: Int? = null

    interface ServiceCallback {
        fun onTranscriptUpdate(transcript: String, isFinal: Boolean)
        fun onConnectionStatusChanged(status: dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus, peer: Device?)
        fun onTransmissionStateChanged(isLocalTransmitting: Boolean, isRemoteTransmitting: Boolean)
        fun onTripStatusChanged(
            isActive: Boolean,
            tripId: String? = null,
            hostUid: String? = null,
            tripPath: String? = null
        )
        fun onModelDownloadProgress(progress: Int)
        fun onModelDownloadStateChanged(isDownloading: Boolean, isSuccess: Boolean? = null)
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
             signalingClient.sendMessage(
                 "ICE64:${candidate.sdpMid}:${candidate.sdpMLineIndex}:${encodeSignalPayload(candidate.sdp)}"
             )
        }
        override fun onDataChannel(channel: DataChannel?) {
            if (channel == null) {
                return
            }
            attachControlDataChannel(channel, source = "remote")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d("AudioService", "IceConnectionChange: $state")
            val status = when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> ConnectionStatus.CONNECTED
                PeerConnection.IceConnectionState.CHECKING -> ConnectionStatus.CONNECTING
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.CLOSED -> ConnectionStatus.DISCONNECTED
                else -> ConnectionStatus.DISCONNECTED
            }
            isRtcConnected = status == ConnectionStatus.CONNECTED
            connectionStatus = status
            syncOutgoingAudioState(reason = "ice_state_change")
            callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
            if (isRtcConnected && pendingTripStatusSync) {
                sendTripStatusToPeer()
            }
            updateCommunicationAudioProfile(reason = "ice_state_change")
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onAddStream(stream: MediaStream?) {
            Log.d("AudioService", "Received remote stream")
            stream?.audioTracks?.forEach { remoteTrack ->
                remoteTrack.setEnabled(true)
                remoteTrack.setVolume(REMOTE_TRACK_GAIN)
            }
        }
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val audioTrack = receiver?.track() as? org.webrtc.AudioTrack ?: return
            audioTrack.setEnabled(true)
            audioTrack.setVolume(REMOTE_TRACK_GAIN)
            Log.i(TAG, "Remote audio track enabled with gain=$REMOTE_TRACK_GAIN")
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vad = SimpleVad()
        audioCapturer = AudioCapturer(this)
        signalingClient = SignalingClient(this)
        webRtcClient = WebRtcClient(this, peerConnectionObserver)
        Log.i(TAG, "Service created. Idle until connect/host/trip events.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = createNotification()
        startForeground(1, notification)
        Log.i(
            TAG,
            "Service started. Mode=$currentMode, TripActive=$isTripActive, " +
                "AudioCaptureRunning=$isAudioCaptureRunning, HostingEnabled=$isHostingEnabled."
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        restoreCommunicationAudioProfile(reason = "service_destroy")
        releaseAudioDucking()
        stopAudioCaptureIfNeeded(reason = "service_destroy")
        flushTranscriptionBuffer(force = true, reason = "service_destroy")
        transcriptionExecutor.shutdown()
        if (!transcriptionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            transcriptionExecutor.shutdownNow()
        }
        speechRecognizerHelper?.destroy()
        speechRecognizerHelper = null
        audioCapturer.shutdown()
        clearControlDataChannel()
        webRtcClient.close()
        signalingClient.close()
    }

    fun registerCallback(callback: ServiceCallback) {
        this.callback = callback
        // Update immediately with current state
        callback.onConnectionStatusChanged(connectionStatus, connectedPeer)
        callback.onTransmissionStateChanged(isTransmitting, isRemoteTransmitting)
        callback.onTripStatusChanged(isTripActive, currentTripId, currentTripHostUid, currentTripPath)
    }

    fun unregisterCallback(callback: ServiceCallback) {
        if (this.callback === callback) {
            this.callback = null
        }
    }

    fun connectToPeer(device: Device) {
        if (isHostingEnabled) {
            setHostingEnabled(false)
        }
        resetSignalingClient(startServer = false)
        applyRemoteTransmissionState(false, source = "connect_to_peer")
        connectedPeer = device
        connectionStatus = ConnectionStatus.CONNECTING
        callback?.onConnectionStatusChanged(connectionStatus, device)
        if (device.ip != null && device.port != null) {
            try {
                signalingClient.connectToPeer(InetAddress.getByName(device.ip), device.port)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to resolve/connect peer ${device.ip}:${device.port}", t)
                connectionStatus = ConnectionStatus.ERROR
                callback?.onConnectionStatusChanged(connectionStatus, device)
                restartSignalingServer()
            }
        } else {
            Log.e(TAG, "Selected device has no IP/port: $device")
            connectionStatus = ConnectionStatus.ERROR
            callback?.onConnectionStatusChanged(connectionStatus, device)
            restartSignalingServer()
        }
    }

    fun disconnect() {
        resetSignalingClient(startServer = isHostingEnabled)
        clearControlDataChannel()
        webRtcClient.close()
        // Re-initialize for next potential connection
        webRtcClient = WebRtcClient(this, peerConnectionObserver)
        connectedPeer = null
        connectionStatus = ConnectionStatus.DISCONNECTED
        isRtcConnected = false
        pendingTripStatusSync = false
        isRemoteTransmitting = false
        setTransmitting(false, reason = "disconnect")
        syncOutgoingAudioState(reason = "disconnect_recreate_webrtc")
        restoreCommunicationAudioProfile(reason = "disconnect")
        callback?.onConnectionStatusChanged(connectionStatus, null)
        notifyTransmissionStateChanged()
    }

    fun setHostingEnabled(enabled: Boolean) {
        if (isHostingEnabled == enabled) {
            return
        }
        isHostingEnabled = enabled
        if (enabled) {
            resetSignalingClient(startServer = true)
            Log.i(TAG, "Hosting enabled: signaling server started.")
        } else {
            if (connectionStatus == ConnectionStatus.DISCONNECTED) {
                resetSignalingClient(startServer = false)
            }
            Log.i(TAG, "Hosting disabled.")
        }
    }

    fun updateConfiguration(
        mode: OperatingMode,
        startCmd: String,
        stopCmd: String,
        sttEngine: SttEngine,
        modelId: String,
        tripActive: Boolean,
        tripId: String? = null,
        tripHostUid: String? = null,
        tripPath: String? = null,
        propagateTripStatus: Boolean = true
    ) {
        val previousTripId = currentTripId
        val previousTripHostUid = currentTripHostUid
        val previousTripPath = currentTripPath
        val resolvedModelId = WhisperModelCatalog.findById(modelId)?.id
            ?: WhisperModelCatalog.defaultOption.id
        val normalizedTripId = when {
            !tripActive -> null
            !tripId.isNullOrBlank() -> tripId
            else -> currentTripId
        }
        val normalizedTripHostUid = when {
            !tripActive -> null
            !tripHostUid.isNullOrBlank() -> tripHostUid
            else -> currentTripHostUid
        }
        val normalizedTripPath = when {
            !tripActive -> null
            !tripPath.isNullOrBlank() -> tripPath
            else -> buildTripPath(normalizedTripHostUid, normalizedTripId)
        }
        val tripChanged =
            isTripActive != tripActive ||
                currentTripId != normalizedTripId ||
                currentTripHostUid != normalizedTripHostUid ||
                currentTripPath != normalizedTripPath
        val wasTripActive = isTripActive
        val engineChanged = this.sttEngine != sttEngine
        val modelChanged = whisperModelId != resolvedModelId
        val modeChanged = currentMode != mode
        currentMode = mode
        startCommand = startCmd
        stopCommand = stopCmd
        this.sttEngine = sttEngine
        whisperModelId = resolvedModelId
        isTripActive = tripActive
        currentTripId = normalizedTripId
        currentTripHostUid = normalizedTripHostUid
        currentTripPath = normalizedTripPath

        if (resolvedModelId != modelId) {
            Log.w(TAG, "Unknown whisper model '$modelId'. Falling back to '$resolvedModelId'.")
        }

        if (modelChanged) {
            flushTranscriptionBuffer(force = true, reason = "model_change")
            resetTranscriptionState(clearAudio = true)
            speechRecognizerHelper?.setWhisperModel(whisperModelId)
        }
        if (engineChanged) {
            flushTranscriptionBuffer(force = true, reason = "stt_engine_change")
            resetTranscriptionState(clearAudio = true)
            speechRecognizerHelper?.setEngine(sttEngine)
        }

        if (tripChanged && wasTripActive && !tripActive) {
            setTransmitting(false, reason = "trip_end")
            applyRemoteTransmissionState(false, source = "trip_end")
            stopAudioCaptureIfNeeded(reason = "trip_end")
            flushTranscriptionBuffer(force = true, reason = "trip_end")
            speechRecognizerHelper?.stopListening()
        }
        if (tripChanged && tripActive) {
            resetTranscriptionState(clearAudio = true)
            startAudioCaptureIfNeeded()
            lifecycleScope.launch {
                val recognizer = getOrCreateSpeechRecognizerHelper()
                if (engineChanged) {
                    recognizer.setEngine(sttEngine)
                }
                if (modelChanged) {
                    recognizer.setWhisperModel(whisperModelId)
                }
                if (sttEngine == SttEngine.WHISPER) {
                    recognizer.downloadModelIfNeeded()
                }
                if (isTripActive) {
                    recognizer.startListening()
                }
            }
        } else if ((modelChanged || engineChanged) && tripActive) {
            lifecycleScope.launch {
                val recognizer = getOrCreateSpeechRecognizerHelper()
                if (engineChanged) {
                    recognizer.setEngine(sttEngine)
                }
                recognizer.setWhisperModel(whisperModelId)
                if (sttEngine == SttEngine.WHISPER) {
                    recognizer.downloadModelIfNeeded()
                }
                if (isTripActive) {
                    recognizer.startListening()
                }
            }
        }

        if (tripChanged) {
            Log.i(TAG, "Trip status changed locally. isTripActive=$isTripActive, tripId=$tripId")
            if (propagateTripStatus) {
                if (tripActive) {
                    sendTripStatusToPeer(
                        active = true,
                        tripId = normalizedTripId,
                        hostUid = normalizedTripHostUid,
                        tripPath = normalizedTripPath
                    )
                } else {
                    sendTripStatusToPeer(
                        active = false,
                        tripId = previousTripId,
                        hostUid = previousTripHostUid,
                        tripPath = previousTripPath
                    )
                }
            }
        }
        if (modelChanged) {
            Log.i(TAG, "Whisper model changed locally. modelId=$whisperModelId")
        }
        if (engineChanged) {
            Log.i(TAG, "STT engine changed locally. engine=$sttEngine")
        }
        Log.d(
            TAG,
            "Configuration updated: Mode=$mode, Start=$startCmd, Stop=$stopCmd, " +
                "SttEngine=$sttEngine, WhisperModel=$whisperModelId, TripActive=$tripActive"
        )

        if (!isTripActive) {
            setTransmitting(false, reason = "trip_inactive")
        } else {
            when (currentMode) {
                OperatingMode.CONTINUOUS_TRANSMISSION -> {
                    setTransmitting(true, reason = "continuous_mode")
                }
                OperatingMode.VOICE_ACTIVITY_DETECTION,
                OperatingMode.VOICE_COMMAND -> {
                    if (tripChanged && tripActive) {
                        setTransmitting(false, reason = "trip_start_wait_for_trigger")
                    } else if (modeChanged) {
                        setTransmitting(false, reason = "mode_requires_trigger")
                    }
                }
            }
        }
        syncOutgoingAudioState(reason = "configuration_update")
        updateCommunicationAudioProfile(reason = "configuration_update")
    }

    // --- SignalingListener Callbacks ---
    override fun onPeerConnected(isInitiator: Boolean) {
        Log.d("AudioService", "Peer connected. initiator=$isInitiator")
        connectionStatus = ConnectionStatus.CONNECTING
        callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
        signalingClient.sendMessage("NAME:${buildPeerInfoPayload()}")
        if (isInitiator) {
            val localControlChannel = webRtcClient.createDataChannel(CONTROL_CHANNEL_LABEL)
            if (localControlChannel != null) {
                attachControlDataChannel(localControlChannel, source = "local")
            } else {
                Log.w(TAG, "Failed to create local control data channel.")
            }
            webRtcClient.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    webRtcClient.setLocalDescription(this, sdp)
                    signalingClient.sendMessage("OFFER64:${encodeSignalPayload(sdp.description)}")
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(reason: String?) {
                    Log.e(TAG, "createOffer failed: $reason")
                    connectionStatus = ConnectionStatus.ERROR
                    callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
                }
                override fun onSetFailure(reason: String?) {
                    Log.e(TAG, "setLocalDescription(offer) failed: $reason")
                    connectionStatus = ConnectionStatus.ERROR
                    callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
                }
            })
        }
        if (isTripActive) {
            sendTripStatusToPeer()
        }
        sendTransmissionStateToPeer()
        syncOutgoingAudioState(reason = "peer_connected")
    }

    override fun onPeerInfoReceived(name: String) {
        Log.d("AudioService", "Peer info received: $name")
        val (peerUid, peerDisplayName) = parsePeerInfoPayload(name)
        if (connectedPeer == null) {
            connectedPeer = Device(
                id = peerUid ?: peerDisplayName,
                name = peerDisplayName,
                deviceName = peerDisplayName
            )
        } else {
            val existing = connectedPeer
            connectedPeer = existing?.copy(
                id = peerUid ?: existing.id,
                name = peerDisplayName,
                deviceName = peerDisplayName
            )
        }
        callback?.onConnectionStatusChanged(dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus.CONNECTING, connectedPeer)
    }

    override fun onOfferReceived(description: String) {
        val sdpString = normalizeSignaledSdp(description)
        val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
        
        webRtcClient.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                webRtcClient.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription) {
                        webRtcClient.setLocalDescription(this, answerSdp)
                        signalingClient.sendMessage("ANSWER64:${encodeSignalPayload(answerSdp.description)}")
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(reason: String?) {
                        Log.e(TAG, "createAnswer failed: $reason")
                        connectionStatus = ConnectionStatus.ERROR
                        callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
                    }
                    override fun onSetFailure(reason: String?) {
                        Log.e(TAG, "setLocalDescription(answer) failed: $reason")
                        connectionStatus = ConnectionStatus.ERROR
                        callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
                    }
                })
            }
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(reason: String?) {
                Log.e(TAG, "setRemoteDescription(offer) failed: $reason")
                connectionStatus = ConnectionStatus.ERROR
                callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
            }
        }, sdp)
    }

    override fun onAnswerReceived(description: String) {
        val sdpString = normalizeSignaledSdp(description)
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
        webRtcClient.setRemoteDescription(object : SdpObserver {
             override fun onSetSuccess() {}
             override fun onCreateSuccess(s: SessionDescription?) {}
             override fun onCreateFailure(s: String?) {}
             override fun onSetFailure(reason: String?) {
                 Log.e(TAG, "setRemoteDescription(answer) failed: $reason")
                 connectionStatus = ConnectionStatus.ERROR
                 callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
             }
        }, sdp)
    }

    override fun onIceCandidateReceived(candidate: String) {
        val parts = candidate.split(":", limit = 3)
        if (parts.size >= 3) {
            val mid = parts[0]
            val index = parts[1].toIntOrNull() ?: return
            val sdp = normalizeSignaledSdp(parts[2])
            webRtcClient.addIceCandidate(IceCandidate(mid, index, sdp))
        }
    }

    override fun onPeerTransmissionStateReceived(isTransmitting: Boolean) {
        applyRemoteTransmissionState(isTransmitting, source = "signaling")
    }

    override fun onTripStatusReceived(active: Boolean, tripId: String?, hostUid: String?, tripPath: String?) {
        applyRemoteTripStatus(active, tripId, hostUid, tripPath, source = "signaling")
    }

    override fun onPeerDisconnected() {
        Log.w(TAG, "Signaling peer disconnected")
        applyRemoteTransmissionState(false, source = "peer_disconnected")
        if (isRtcConnected) {
            restartSignalingServer()
            return
        }
        connectionStatus = ConnectionStatus.DISCONNECTED
        syncOutgoingAudioState(reason = "peer_disconnected")
        callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
        restartSignalingServer()
    }

    override fun onSignalingError(error: Throwable) {
        Log.e(TAG, "Signaling error", error)
        applyRemoteTransmissionState(false, source = "signaling_error")
        if (isRtcConnected) {
            restartSignalingServer()
            return
        }
        connectionStatus = ConnectionStatus.ERROR
        syncOutgoingAudioState(reason = "signaling_error")
        callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
        restartSignalingServer()
    }

    // --- AudioCapturerListener Callbacks ---
    override fun onAudioData(data: ByteArray, size: Int) {
        if (size <= 0) {
            return
        }

        totalFramesReceived++
        val currentData = data.sliceArray(0 until size)
        val now = System.currentTimeMillis()
        val isSpeechDetected = vad.isSpeech(currentData)

        if (now - lastTransmissionTime >= PIPELINE_LOG_INTERVAL_MS) {
            lastTransmissionTime = now
            Log.d(
                TAG,
                "Audio pipeline heartbeat: frames=$totalFramesReceived, " +
                    "bufferedBytes=${audioBuffer.size}, bufferedMs=$bufferedAudioDurationMs, " +
                    "silenceMs=$consecutiveSilenceDurationMs, tripActive=$isTripActive, " +
                    "mode=$currentMode, sttEngine=$sttEngine, " +
                    "whisper=${speechRecognizerHelper?.isUsingWhisper ?: false}, " +
                    "transmitting=$isTransmitting"
            )
        }
        
        // 1. Handle Intercom Transmission (VAD Logic)
        if (isTripActive && currentMode == OperatingMode.VOICE_ACTIVITY_DETECTION) {
            if (isSpeechDetected && !isTransmitting) {
                setTransmitting(true, reason = "vad_speech_detected")
            } else if (!isSpeechDetected && isTransmitting) {
                setTransmitting(false, reason = "vad_silence_detected")
            }
        }

        // 2. Handle Transcription Recording for the selected STT engine.
        if (isTripActive) {
            var flushReason: String? = null
            synchronized(transcriptionStateLock) {
                audioBuffer.addAll(currentData.toList())

                val frameDurationMs = bytesToDurationMs(size)
                bufferedAudioDurationMs += frameDurationMs

                if (isSpeechDetected) {
                    consecutiveSilenceDurationMs = 0L
                    chunkHadSpeech = true
                } else {
                    consecutiveSilenceDurationMs += frameDurationMs
                }

                val hasMinimumContext = bufferedAudioDurationMs >= TRANSCRIPTION_MIN_CONTEXT_MS
                val silenceFlushReached = consecutiveSilenceDurationMs >= TRANSCRIPTION_SILENCE_FLUSH_MS
                val maxChunkReached = bufferedAudioDurationMs >= TRANSCRIPTION_MAX_CHUNK_MS

                if (hasMinimumContext && (silenceFlushReached || maxChunkReached)) {
                    flushReason = if (maxChunkReached) "max_chunk_timeout" else "silence_timeout"
                }
            }

            flushReason?.let { reason ->
                flushTranscriptionBuffer(force = false, reason = reason)
            }
        } else {
            synchronized(transcriptionStateLock) {
                if (audioBuffer.isNotEmpty()) {
                    resetTranscriptionStateLocked(clearAudio = true)
                }
            }
        }
    }

    // --- SpeechRecognitionListener Callbacks ---
    override fun onPartialResults(results: String) {
        Log.v(TAG, "Partial transcript len=${results.length}")
        callback?.onTranscriptUpdate(results, false)
    }

    override fun onFinalResults(results: String) {
        Log.i(TAG, "Final transcript len=${results.length}: $results")
        callback?.onTranscriptUpdate(results, true)
        if (currentMode == OperatingMode.VOICE_COMMAND) {
            handleVoiceCommand(results)
        }
    }

    override fun onModelDownloadStarted() {
        Log.i(TAG, "Model download started")
        callback?.onModelDownloadStateChanged(true)
    }

    override fun onModelDownloadProgress(progress: Int) {
        // Log.d("AudioService", "Model download progress: $progress%") // Too noisy
        callback?.onModelDownloadProgress(progress)
    }

    override fun onModelDownloadFinished(success: Boolean) {
        Log.i(TAG, "Model download finished. Success: $success. Whisper=${speechRecognizerHelper?.isUsingWhisper ?: false}")
        callback?.onModelDownloadStateChanged(false, success)
    }

    override fun onError(error: String) {
        Log.e(TAG, "Speech Recognizer Error: $error")
    }

    private fun handleVoiceCommand(command: String) {
        if (!isTripActive) {
            return
        }
        val startKeyword = startCommand.trim()
        val stopKeyword = stopCommand.trim()
        if (startKeyword.isNotEmpty() && command.contains(startKeyword, ignoreCase = true) && !isTransmitting) {
            setTransmitting(true, reason = "voice_command_start")
        } else if (stopKeyword.isNotEmpty() && command.contains(stopKeyword, ignoreCase = true) && isTransmitting) {
            setTransmitting(false, reason = "voice_command_stop")
        }
    }

    private fun setTransmitting(transmitting: Boolean, reason: String) {
        if (isTransmitting == transmitting) {
            notifyTransmissionStateChanged()
            syncOutgoingAudioState(reason = reason)
            return
        }
        isTransmitting = transmitting
        if (transmitting) {
            requestAudioDucking()
            Log.i(TAG, "Transmission ON. reason=$reason")
        } else {
            releaseAudioDucking()
            Log.i(TAG, "Transmission OFF. reason=$reason")
        }
        sendTransmissionStateToPeer()
        notifyTransmissionStateChanged()
        syncOutgoingAudioState(reason = reason)
    }

    private fun shouldEnableOutgoingAudio(): Boolean {
        return isTripActive && isTransmitting && isRtcConnected
    }

    private fun syncOutgoingAudioState(reason: String) {
        val enabled = shouldEnableOutgoingAudio()
        webRtcClient.setLocalAudioEnabled(enabled)
        updateCommunicationAudioProfile(reason = "sync_outgoing_audio")
        Log.d(
            TAG,
            "Sync outgoing audio. enabled=$enabled, reason=$reason, tripActive=$isTripActive, " +
                "transmitting=$isTransmitting, connectionStatus=$connectionStatus"
        )
    }

    @Suppress("DEPRECATION")
    private fun requestAudioDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun shouldUseCommunicationAudioProfile(): Boolean {
        return connectionStatus == ConnectionStatus.CONNECTING ||
            isRtcConnected ||
            isTripActive
    }

    private fun updateCommunicationAudioProfile(reason: String) {
        if (shouldUseCommunicationAudioProfile()) {
            applyCommunicationAudioProfile(reason)
        } else {
            restoreCommunicationAudioProfile(reason)
        }
    }

    private fun applyCommunicationAudioProfile(reason: String) {
        if (!communicationProfileApplied) {
            savedAudioMode = audioManager.mode
            savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
            savedMicrophoneMute = audioManager.isMicrophoneMute
            savedVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            communicationProfileApplied = true
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        audioManager.isMicrophoneMute = false
        boostStreamVolume(AudioManager.STREAM_VOICE_CALL, TARGET_VOICE_CALL_VOLUME_RATIO)
        boostStreamVolume(AudioManager.STREAM_MUSIC, TARGET_MUSIC_VOLUME_RATIO)
        Log.d(TAG, "Communication audio profile applied. reason=$reason")
    }

    private fun restoreCommunicationAudioProfile(reason: String) {
        if (!communicationProfileApplied) {
            return
        }
        savedAudioMode?.let { audioManager.mode = it }
        savedSpeakerphoneOn?.let { audioManager.isSpeakerphoneOn = it }
        savedMicrophoneMute?.let { audioManager.isMicrophoneMute = it }
        savedVoiceCallVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) }
        savedMusicVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }

        communicationProfileApplied = false
        savedAudioMode = null
        savedSpeakerphoneOn = null
        savedMicrophoneMute = null
        savedVoiceCallVolume = null
        savedMusicVolume = null
        Log.d(TAG, "Communication audio profile restored. reason=$reason")
    }

    private fun boostStreamVolume(streamType: Int, ratio: Float) {
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        if (maxVolume <= 0) {
            return
        }
        val safeRatio = ratio.coerceIn(0f, 1f)
        val targetVolume = (maxVolume * safeRatio).roundToInt().coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(streamType)
        if (currentVolume < targetVolume) {
            audioManager.setStreamVolume(streamType, targetVolume, 0)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Moto Ride Call Connect")
            .setContentText("Intercomunicador ativo.")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    private fun bytesToDurationMs(byteCount: Int): Long {
        if (byteCount <= 0) return 0L
        val sampleCount = byteCount / AUDIO_BYTES_PER_SAMPLE
        return (sampleCount * 1000L) / AUDIO_SAMPLE_RATE_HZ
    }

    private fun flushTranscriptionBuffer(force: Boolean, reason: String) {
        val chunk: ByteArray
        val chunkDurationMs: Long
        val silenceMs: Long
        val hadSpeech: Boolean
        synchronized(transcriptionStateLock) {
            if (audioBuffer.isEmpty()) {
                resetTranscriptionStateLocked(clearAudio = false)
                return
            }

            if (!force && bufferedAudioDurationMs < TRANSCRIPTION_MIN_CONTEXT_MS) {
                return
            }

            chunk = audioBuffer.toByteArray()
            chunkDurationMs = bufferedAudioDurationMs
            silenceMs = consecutiveSilenceDurationMs
            hadSpeech = chunkHadSpeech
            resetTranscriptionStateLocked(clearAudio = true)
        }

        if (!force && !hadSpeech) {
            Log.d(
                TAG,
                "Dropping chunk without detected speech. bytes=${chunk.size}, " +
                    "durationMs=$chunkDurationMs, silenceMs=$silenceMs, reason=$reason"
            )
            return
        }

        totalChunksDispatched++
        val chunkId = totalChunksDispatched

        Log.i(
            TAG,
            "Dispatching chunk #$chunkId to STT. bytes=${chunk.size}, durationMs=$chunkDurationMs, " +
                "silenceMs=$silenceMs, hadSpeech=$hadSpeech, reason=$reason, " +
                "sttEngine=$sttEngine, whisper=${speechRecognizerHelper?.isUsingWhisper ?: false}"
        )

        val recognizer = speechRecognizerHelper
        if (recognizer == null) {
            Log.w(TAG, "Dropping STT chunk #$chunkId because recognizer is not initialized.")
            return
        }

        transcriptionExecutor.execute {
            try {
                recognizer.processAudio(chunk)
            } catch (t: Throwable) {
                Log.e(TAG, "STT chunk #$chunkId failed", t)
            }
        }
    }

    private fun resetTranscriptionState(clearAudio: Boolean) {
        synchronized(transcriptionStateLock) {
            resetTranscriptionStateLocked(clearAudio)
        }
    }

    private fun resetTranscriptionStateLocked(clearAudio: Boolean) {
        if (clearAudio) {
            audioBuffer.clear()
        }
        bufferedAudioDurationMs = 0L
        consecutiveSilenceDurationMs = 0L
        chunkHadSpeech = false
    }

    private fun restartSignalingServer() {
        resetSignalingClient(startServer = isHostingEnabled)
    }

    private fun sendTransmissionStateToPeer() {
        val message = if (isTransmitting) "TX:ON" else "TX:OFF"
        val sentByDataChannel = sendControlMessage(message)
        val canSendBySignaling =
            connectionStatus != ConnectionStatus.DISCONNECTED && connectionStatus != ConnectionStatus.ERROR
        if (canSendBySignaling) {
            signalingClient.sendMessage(message)
        }
        Log.d(
            TAG,
            "Sent transmission state. message=$message, viaDataChannel=$sentByDataChannel, " +
                "viaSignaling=$canSendBySignaling"
        )
    }

    private fun applyRemoteTransmissionState(transmitting: Boolean, source: String) {
        if (isRemoteTransmitting == transmitting) {
            return
        }
        isRemoteTransmitting = transmitting
        Log.d(TAG, "Remote transmission updated. transmitting=$transmitting, source=$source")
        notifyTransmissionStateChanged()
    }

    private fun notifyTransmissionStateChanged() {
        callback?.onTransmissionStateChanged(isTransmitting, isRemoteTransmitting)
    }

    private fun sendTripStatusToPeer(
        active: Boolean = isTripActive,
        tripId: String? = currentTripId,
        hostUid: String? = currentTripHostUid,
        tripPath: String? = currentTripPath
    ) {
        val resolvedTripPath = tripPath ?: buildTripPath(hostUid, tripId)
        val message = if (active) {
            "TRIP:START:${tripId.orEmpty()}:${hostUid.orEmpty()}:${resolvedTripPath.orEmpty()}"
        } else {
            "TRIP:STOP:${tripId.orEmpty()}:${hostUid.orEmpty()}:${resolvedTripPath.orEmpty()}"
        }

        val sentByDataChannel = sendControlMessage(message)
        pendingTripStatusSync = !sentByDataChannel && active

        val canSendBySignaling =
            connectionStatus != ConnectionStatus.DISCONNECTED && connectionStatus != ConnectionStatus.ERROR
        if (canSendBySignaling) {
            signalingClient.sendMessage(message)
        }
        Log.i(
            TAG,
            "Sent trip status. message=$message, viaDataChannel=$sentByDataChannel, " +
                "viaSignaling=$canSendBySignaling, pendingTripStatusSync=$pendingTripStatusSync"
        )
    }

    private fun sendControlMessage(message: String): Boolean {
        val channel = controlDataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) {
            return false
        }
        val payload = message.toByteArray(Charsets.UTF_8)
        val sent = channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), false))
        if (!sent) {
            Log.w(TAG, "Failed to send control message via data channel: $message")
        }
        return sent
    }

    private fun attachControlDataChannel(channel: DataChannel, source: String) {
        if (controlDataChannel === channel) {
            return
        }
        clearControlDataChannel()
        controlDataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val state = channel.state()
                Log.i(TAG, "Control data channel state=$state source=$source")
                if (state == DataChannel.State.OPEN) {
                    if (pendingTripStatusSync) {
                        sendTripStatusToPeer()
                    }
                    sendTransmissionStateToPeer()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val messageBytes = ByteArray(buffer.data.remaining())
                buffer.data.get(messageBytes)
                val message = String(messageBytes, Charsets.UTF_8).trim()
                if (message.isBlank()) {
                    return
                }
                handleControlChannelMessage(message)
            }
        })
    }

    private fun clearControlDataChannel() {
        val channel = controlDataChannel ?: return
        try {
            channel.unregisterObserver()
        } catch (_: Throwable) {
        }
        try {
            channel.close()
        } catch (_: Throwable) {
        }
        try {
            channel.dispose()
        } catch (_: Throwable) {
        }
        controlDataChannel = null
    }

    private fun handleControlChannelMessage(message: String) {
        when {
            message.startsWith("TX:") -> {
                val raw = message.substringAfter("TX:").trim().lowercase()
                val transmitting = raw == "on" || raw == "1" || raw == "true"
                applyRemoteTransmissionState(transmitting, source = "data_channel")
            }
            message.startsWith("TRIP:START") -> {
                val parts = message.split(":", limit = 5)
                val tripId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                val hostUid = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                val tripPath = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                applyRemoteTripStatus(true, tripId, hostUid, tripPath, source = "data_channel")
            }
            message.startsWith("TRIP:STOP") -> {
                val parts = message.split(":", limit = 5)
                val tripId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                val hostUid = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                val tripPath = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                applyRemoteTripStatus(false, tripId, hostUid, tripPath, source = "data_channel")
            }
            else -> {
                Log.d(TAG, "Ignoring unknown control message: $message")
            }
        }
    }

    private fun applyRemoteTripStatus(
        active: Boolean,
        tripId: String?,
        hostUid: String?,
        tripPath: String?,
        source: String
    ) {
        Log.i(
            TAG,
            "Remote trip status received via $source. active=$active, id=$tripId, " +
                "hostUid=$hostUid, tripPath=$tripPath"
        )
        val pathParts = parseTripPath(tripPath)
        val incomingTripId = tripId ?: pathParts.second
        val incomingHostUid = hostUid ?: pathParts.first
        if (!active) {
            if (!incomingTripId.isNullOrBlank() && !currentTripId.isNullOrBlank() && incomingTripId != currentTripId) {
                return
            }
            if (!incomingHostUid.isNullOrBlank() && !currentTripHostUid.isNullOrBlank() && incomingHostUid != currentTripHostUid) {
                return
            }
        }
        val normalizedTripId = when {
            !active -> null
            !tripId.isNullOrBlank() -> tripId
            !pathParts.second.isNullOrBlank() -> pathParts.second
            else -> currentTripId
        }
        val normalizedHostUid = when {
            !active -> null
            !hostUid.isNullOrBlank() -> hostUid
            !pathParts.first.isNullOrBlank() -> pathParts.first
            else -> currentTripHostUid
        }
        val normalizedTripPath = when {
            !active -> null
            !tripPath.isNullOrBlank() -> tripPath
            else -> buildTripPath(normalizedHostUid, normalizedTripId)
        }
        val hasChanged =
            isTripActive != active ||
                currentTripId != normalizedTripId ||
                currentTripHostUid != normalizedHostUid ||
                currentTripPath != normalizedTripPath
        if (!hasChanged) {
            return
        }
        updateConfiguration(
            mode = currentMode,
            startCmd = startCommand,
            stopCmd = stopCommand,
            sttEngine = sttEngine,
            modelId = whisperModelId,
            tripActive = active,
            tripId = normalizedTripId,
            tripHostUid = normalizedHostUid,
            tripPath = normalizedTripPath,
            propagateTripStatus = false
        )
        callback?.onTripStatusChanged(active, normalizedTripId, normalizedHostUid, normalizedTripPath)
    }

    private fun resetSignalingClient(startServer: Boolean) {
        signalingClient.close()
        signalingClient = SignalingClient(this)
        if (startServer) {
            signalingClient.startServer(8080)
        }
    }

    private fun normalizeSignaledSdp(raw: String): String {
        return if (raw.contains("|")) raw.replace("|", "\n") else raw
    }

    private fun encodeSignalPayload(raw: String): String {
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun buildPeerInfoPayload(): String {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val uid = firebaseUser?.uid?.takeIf { it.isNotBlank() }
        val displayName = firebaseUser?.displayName?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return if (uid != null) {
            "$uid|$displayName"
        } else {
            displayName
        }
    }

    private fun parsePeerInfoPayload(raw: String): Pair<String?, String> {
        val parts = raw.split("|", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank()) {
            val uid = parts[0]
            val displayName = parts[1].ifBlank { uid }
            uid to displayName
        } else {
            null to raw
        }
    }

    private fun buildTripPath(hostUid: String?, tripId: String?): String? {
        val normalizedHostUid = hostUid?.trim().orEmpty()
        val normalizedTripId = tripId?.trim().orEmpty()
        if (normalizedHostUid.isBlank() || normalizedTripId.isBlank()) {
            return null
        }
        return "${FirestorePaths.ACCOUNTS}/$normalizedHostUid/${FirestorePaths.RIDES}/$normalizedTripId"
    }

    private fun parseTripPath(path: String?): Pair<String?, String?> {
        if (path.isNullOrBlank()) {
            return null to null
        }
        val segments = path.trim().split("/")
        if (segments.size < 4) {
            return null to null
        }
        if (segments[0] != FirestorePaths.ACCOUNTS || segments[2] != FirestorePaths.RIDES) {
            return null to null
        }
        val hostUid = segments[1].takeIf { it.isNotBlank() }
        val tripId = segments[3].takeIf { it.isNotBlank() }
        return hostUid to tripId
    }

    private fun getOrCreateSpeechRecognizerHelper(): SpeechRecognizerHelper {
        val current = speechRecognizerHelper
        if (current != null) {
            return current
        }
        return SpeechRecognizerHelper(this, this, whisperModelId, sttEngine).also { created ->
            speechRecognizerHelper = created
        }
    }

    private fun startAudioCaptureIfNeeded() {
        if (isAudioCaptureRunning) {
            return
        }
        audioCapturer.startCapture()
        isAudioCaptureRunning = true
        Log.i(TAG, "Audio capture started for active trip.")
    }

    private fun stopAudioCaptureIfNeeded(reason: String) {
        if (!isAudioCaptureRunning) {
            return
        }
        audioCapturer.stopCapture()
        isAudioCaptureRunning = false
        Log.i(TAG, "Audio capture stopped. reason=$reason")
    }
}
