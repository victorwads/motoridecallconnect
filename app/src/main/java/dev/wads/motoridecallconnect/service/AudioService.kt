package dev.wads.motoridecallconnect.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.audio.AudioCapturer
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.TranscriptStatus
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import dev.wads.motoridecallconnect.stt.NativeSpeechLanguageCatalog
import dev.wads.motoridecallconnect.stt.SpeechRecognizerHelper
import dev.wads.motoridecallconnect.stt.SttEngine
import dev.wads.motoridecallconnect.stt.WhisperModelCatalog
import dev.wads.motoridecallconnect.stt.queue.FileBackedTranscriptionChunkQueue
import dev.wads.motoridecallconnect.stt.queue.QueuedTranscriptionChunk
import dev.wads.motoridecallconnect.stt.queue.TranscriptionChunkQueue
import dev.wads.motoridecallconnect.stt.queue.TranscriptionQueueSnapshot
import dev.wads.motoridecallconnect.transport.SignalingClient
import dev.wads.motoridecallconnect.transport.WebRtcClient
import dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import dev.wads.motoridecallconnect.vad.SimpleVad
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
        private const val DEFAULT_VAD_START_DELAY_MS = 0L
        private const val DEFAULT_VAD_STOP_DELAY_MS = 1_500L
        private const val MIN_VAD_DELAY_MS = 0L
        private const val MAX_VAD_DELAY_MS = 5_000L
        private const val AUDIO_ROUTE_LABEL_BT_UNAVAILABLE = "Bluetooth headset unavailable"
        private const val AUDIO_ROUTE_LABEL_BT_PERMISSION_MISSING = "Bluetooth permission missing"
        private const val AUDIO_ROUTE_LABEL_BT_PENDING = "Bluetooth available, waiting route activation"
        private const val AUDIO_ROUTE_LABEL_BT_ACTIVE = "Bluetooth headset active"
        private const val AUDIO_ROUTE_LABEL_EARPIECE = "Phone earpiece"
        private const val AUDIO_ROUTE_LABEL_SPEAKER = "Phone speaker"
        private const val AUDIO_ROUTE_LABEL_WIRED = "Wired headset"
        private const val AUDIO_ROUTE_LABEL_USB = "USB audio"
        private const val AUDIO_ROUTE_LABEL_PHONE = "Phone audio"
        private const val PIPELINE_LOG_INTERVAL_MS = 5_000L
        private const val AUDIO_SAMPLE_RATE_HZ = 48_000
        private const val AUDIO_BYTES_PER_SAMPLE = 2

        // Tuning knobs for chunk-based transcription behavior.
        private const val TRANSCRIPTION_MIN_CONTEXT_MS = 3_000L
        private const val TRANSCRIPTION_SILENCE_FLUSH_MS = 3_000L
        private const val TRANSCRIPTION_MAX_CHUNK_MS = 45_000L
        private const val TRANSCRIPTION_TRIM_FRAME_MS = 20L
        private const val TRANSCRIPTION_TRIM_PREROLL_MS = 200L
        private const val TRANSCRIPTION_TRIM_POSTROLL_MS = 200L
        private const val TRANSCRIPTION_MIN_TRIMMED_MS = 700L
    }

    private val binder = LocalBinder()
    private val CHANNEL_ID = "AudioServiceChannel"
    private lateinit var audioCapturer: AudioCapturer
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private lateinit var signalingClient: SignalingClient
    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var audioManager: AudioManager
    private lateinit var vad: SimpleVad
    private lateinit var webRtcClient: WebRtcClient
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    private val transcriptionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var transcriptionQueue: TranscriptionChunkQueue

    private var callback: ServiceCallback? = null

    // Service State
    private var currentMode = OperatingMode.VOICE_COMMAND
    private var startCommand = "iniciar"
    private var stopCommand = "parar"
    private var vadStartDelayMs = DEFAULT_VAD_START_DELAY_MS
    private var vadStopDelayMs = DEFAULT_VAD_STOP_DELAY_MS
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
    private var vadSpeechDetectedAtMs: Long? = null
    private var vadSilenceDetectedAtMs: Long? = null
    private var isAudioCaptureRunning = false
    private var isHostingEnabled = false
    private var sttEngine = SttEngine.WHISPER
    private var nativeSpeechLanguageTag = NativeSpeechLanguageCatalog.defaultOption.tag
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
    private var savedCommunicationDevice: AudioDeviceInfo? = null
    private var bluetoothScoStartedByService = false
    private var isBluetoothAudioRouteActive = false
    private var currentAudioRouteLabel = AUDIO_ROUTE_LABEL_BT_UNAVAILABLE
    private var communicationDeviceChangedListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private val transcriptionWorkerLock = Any()
    private var isTranscriptionWorkerRunning = false

    private data class AudioRouteState(
        val label: String,
        val isBluetoothActive: Boolean
    )

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handleAudioDeviceInventoryChanged(reason = "audio_devices_added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handleAudioDeviceInventoryChanged(reason = "audio_devices_removed")
        }
    }

    interface ServiceCallback {
        fun onTranscriptUpdate(
            transcript: String,
            isFinal: Boolean,
            tripId: String? = null,
            hostUid: String? = null,
            tripPath: String? = null,
            timestampMs: Long? = null
        )
        fun onConnectionStatusChanged(status: dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus, peer: Device?)
        fun onTransmissionStateChanged(isLocalTransmitting: Boolean, isRemoteTransmitting: Boolean)
        fun onTripStatusChanged(
            isActive: Boolean,
            tripId: String? = null,
            hostUid: String? = null,
            tripPath: String? = null
        )
        fun onTranscriptionQueueUpdated(snapshot: TranscriptionQueueSnapshot)
        fun onAudioRouteChanged(routeLabel: String, isBluetoothActive: Boolean, isBluetoothRequired: Boolean)
        fun onModelDownloadProgress(progress: Int)
        fun onModelDownloadStateChanged(isDownloading: Boolean, isSuccess: Boolean? = null)
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val sdpMid = candidate.sdpMid.orEmpty()
             signalingClient.sendMessage(
                 "ICE64:$sdpMid:${candidate.sdpMLineIndex}:${encodeSignalPayload(candidate.sdp)}"
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
        transcriptionQueue = FileBackedTranscriptionChunkQueue(this)
        signalingClient = SignalingClient(this)
        webRtcClient = WebRtcClient(this, peerConnectionObserver)
        registerAudioRouteCallbacks()
        refreshAudioRouteState(reason = "service_create")
        publishTranscriptionQueueSnapshot(reason = "service_create")
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
        unregisterAudioRouteCallbacks()
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
        callback.onTranscriptionQueueUpdated(transcriptionQueue.snapshot())
        callback.onAudioRouteChanged(currentAudioRouteLabel, isBluetoothAudioRouteActive, isBluetoothRequired = true)
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
        if (device.port != null) {
            try {
                val candidateIpStrings = buildConnectionCandidates(device)
                val candidateAddresses = candidateIpStrings.mapNotNull { candidateIp ->
                    try {
                        InetAddress.getByName(candidateIp)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Skipping invalid candidate peer IP: $candidateIp", t)
                        null
                    }
                }
                if (candidateAddresses.isEmpty()) {
                    throw IllegalArgumentException("Selected device has no valid IP candidates: $device")
                }

                // When connected to a Hotspot with no internet, Android might prioritize Cellular
                // for the default network. We must explicitly bind the socket to the WiFi network
                // to reach the local IP of the Hotspot host.
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val allNetworks = connectivityManager.allNetworks.toList()
                val wifiNetworks = allNetworks.filter { network ->
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                val fallbackNetworks = allNetworks.filterNot { wifiNetworks.contains(it) }
                val networkCandidates = buildList<android.net.Network?> {
                    addAll(wifiNetworks)
                    addAll(fallbackNetworks)
                    add(null) // final fallback to default network routing.
                }.distinct()

                if (wifiNetworks.isNotEmpty()) {
                    Log.i(TAG, "Will try ${wifiNetworks.size} WiFi network(s) first for signaling socket.")
                } else {
                    Log.w(TAG, "No explicit WiFi network found for signaling socket. Will use all available networks.")
                }

                Log.i(
                    TAG,
                    "Connecting using candidate IPs=${candidateIpStrings.joinToString()} port=${device.port}"
                )
                signalingClient.connectToPeer(candidateAddresses, device.port, networkCandidates)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to resolve/connect peer candidates for port ${device.port}", t)
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

    private fun buildConnectionCandidates(device: Device): List<String> {
        return buildList {
            if (!device.ip.isNullOrBlank()) add(device.ip)
            device.candidateIps.forEach { candidate ->
                if (candidate.isNotBlank()) add(candidate)
            }
        }.distinct()
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
        nativeLanguageTag: String,
        modelId: String,
        vadStartDelaySeconds: Float? = null,
        vadStopDelaySeconds: Float? = null,
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
        val resolvedNativeLanguageTag = NativeSpeechLanguageCatalog.normalizeTag(nativeLanguageTag)
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
        val nativeLanguageChanged = this.nativeSpeechLanguageTag != resolvedNativeLanguageTag
        val modelChanged = whisperModelId != resolvedModelId
        val modeChanged = currentMode != mode
        currentMode = mode
        startCommand = startCmd
        stopCommand = stopCmd
        if (vadStartDelaySeconds != null) {
            vadStartDelayMs = normalizeVadDelayMs(vadStartDelaySeconds)
        }
        if (vadStopDelaySeconds != null) {
            vadStopDelayMs = normalizeVadDelayMs(vadStopDelaySeconds)
        }
        this.sttEngine = sttEngine
        nativeSpeechLanguageTag = resolvedNativeLanguageTag
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
        if (nativeLanguageChanged) {
            speechRecognizerHelper?.setNativeLanguageTag(nativeSpeechLanguageTag)
        }

        if (tripChanged && wasTripActive && !tripActive) {
            resetVadTimingState()
            setTransmitting(false, reason = "trip_end")
            applyRemoteTransmissionState(false, source = "trip_end")
            stopAudioCaptureIfNeeded(reason = "trip_end")
            flushTranscriptionBuffer(force = true, reason = "trip_end")
            speechRecognizerHelper?.stopListening()
        }
        if (tripChanged && tripActive) {
            resetTranscriptionState(clearAudio = true)
            updateCommunicationAudioProfile(reason = "trip_start")
            if (isBluetoothAudioRouteActive) {
                startAudioCaptureIfNeeded()
            } else {
                stopAudioCaptureIfNeeded(reason = "trip_start_bluetooth_missing")
            }
            lifecycleScope.launch {
                val recognizer = getOrCreateSpeechRecognizerHelper()
                if (engineChanged) {
                    recognizer.setEngine(sttEngine)
                }
                if (nativeLanguageChanged) {
                    recognizer.setNativeLanguageTag(nativeSpeechLanguageTag)
                }
                if (modelChanged) {
                    recognizer.setWhisperModel(whisperModelId)
                }
                if (sttEngine == SttEngine.WHISPER) {
                    recognizer.downloadModelIfNeeded()
                }
                if (isTripActive && isBluetoothAudioRouteActive) {
                    recognizer.startListening()
                    scheduleTranscriptionQueueProcessing(reason = "trip_start")
                }
            }
        } else if ((modelChanged || engineChanged) && tripActive) {
            lifecycleScope.launch {
                val recognizer = getOrCreateSpeechRecognizerHelper()
                if (engineChanged) {
                    recognizer.setEngine(sttEngine)
                }
                if (nativeLanguageChanged) {
                    recognizer.setNativeLanguageTag(nativeSpeechLanguageTag)
                }
                recognizer.setWhisperModel(whisperModelId)
                if (sttEngine == SttEngine.WHISPER) {
                    recognizer.downloadModelIfNeeded()
                }
                if (isTripActive && isBluetoothAudioRouteActive) {
                    recognizer.startListening()
                    scheduleTranscriptionQueueProcessing(reason = "config_engine_or_model_changed")
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
        if (nativeLanguageChanged) {
            Log.i(TAG, "Native STT language changed locally. languageTag=$nativeSpeechLanguageTag")
        }
        Log.d(
            TAG,
            "Configuration updated: Mode=$mode, Start=$startCmd, Stop=$stopCmd, " +
                "VadStartDelayMs=$vadStartDelayMs, VadStopDelayMs=$vadStopDelayMs, " +
                "SttEngine=$sttEngine, NativeLanguage=$nativeSpeechLanguageTag, " +
                "WhisperModel=$whisperModelId, TripActive=$tripActive"
        )

        if (!isTripActive) {
            resetVadTimingState()
            setTransmitting(false, reason = "trip_inactive")
        } else {
            when (currentMode) {
                OperatingMode.CONTINUOUS_TRANSMISSION -> {
                    resetVadTimingState()
                    setTransmitting(true, reason = "continuous_mode")
                }
                OperatingMode.VOICE_ACTIVITY_DETECTION,
                OperatingMode.VOICE_COMMAND -> {
                    if (currentMode != OperatingMode.VOICE_ACTIVITY_DETECTION) {
                        resetVadTimingState()
                    }
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
        if (transcriptionQueue.snapshot().totalCount > 0) {
            scheduleTranscriptionQueueProcessing(reason = "configuration_update")
        }
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
            val mid = parts[0].takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
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

        if (isTripActive && !isBluetoothAudioRouteActive) {
            resetVadTimingState()
            synchronized(transcriptionStateLock) {
                if (audioBuffer.isNotEmpty()) {
                    resetTranscriptionStateLocked(clearAudio = true)
                }
            }
            return
        }

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
            handleVoiceActivityTransmission(isSpeechDetected = isSpeechDetected, nowMs = now)
        } else {
            resetVadTimingState()
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
        callback?.onTranscriptUpdate(
            transcript = results,
            isFinal = false,
            tripId = currentTripId,
            hostUid = currentTripHostUid,
            tripPath = currentTripPath,
            timestampMs = System.currentTimeMillis()
        )
    }

    override fun onFinalResults(results: String) {
        Log.i(TAG, "Final transcript len=${results.length}: $results")
        callback?.onTranscriptUpdate(
            transcript = results,
            isFinal = true,
            tripId = currentTripId,
            hostUid = currentTripHostUid,
            tripPath = currentTripPath,
            timestampMs = System.currentTimeMillis()
        )
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

    private fun handleVoiceActivityTransmission(isSpeechDetected: Boolean, nowMs: Long) {
        if (isSpeechDetected) {
            vadSilenceDetectedAtMs = null
            val speechStartMs = vadSpeechDetectedAtMs ?: nowMs.also { vadSpeechDetectedAtMs = it }
            if (!isTransmitting && nowMs - speechStartMs >= vadStartDelayMs) {
                setTransmitting(true, reason = "vad_speech_detected")
            }
            return
        }

        vadSpeechDetectedAtMs = null
        val silenceStartMs = vadSilenceDetectedAtMs ?: nowMs.also { vadSilenceDetectedAtMs = it }
        if (isTransmitting && nowMs - silenceStartMs >= vadStopDelayMs) {
            setTransmitting(false, reason = "vad_silence_detected")
        }
    }

    private fun resetVadTimingState() {
        vadSpeechDetectedAtMs = null
        vadSilenceDetectedAtMs = null
    }

    private fun setTransmitting(transmitting: Boolean, reason: String) {
        if (transmitting && !isBluetoothAudioRouteActive) {
            Log.w(TAG, "Blocking transmission because Bluetooth audio route is not active. reason=$reason")
            forceStopTransmissionDueToInvalidRoute(reason = "tx_blocked_bluetooth_unavailable")
            notifyAudioRouteChanged()
            syncOutgoingAudioState(reason = "tx_blocked_no_bluetooth")
            return
        }

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
        return isTripActive && isTransmitting && isRtcConnected && isBluetoothAudioRouteActive
    }

    private fun syncOutgoingAudioState(reason: String) {
        updateCommunicationAudioProfile(reason = "sync_outgoing_audio")
        val enabled = shouldEnableOutgoingAudio()
        webRtcClient.setLocalAudioEnabled(enabled)
        Log.d(
            TAG,
            "Sync outgoing audio. enabled=$enabled, reason=$reason, tripActive=$isTripActive, " +
                "transmitting=$isTransmitting, connectionStatus=$connectionStatus, " +
                "bluetoothRouteActive=$isBluetoothAudioRouteActive"
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

    private fun registerAudioRouteCallbacks() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener {
                updateCommunicationAudioProfile(reason = "communication_device_changed")
                refreshAudioRouteState(reason = "communication_device_changed")
            }
            communicationDeviceChangedListener = listener
            audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, listener)
        }
    }

    private fun unregisterAudioRouteCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationDeviceChangedListener?.let { listener ->
                audioManager.removeOnCommunicationDeviceChangedListener(listener)
            }
            communicationDeviceChangedListener = null
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    private fun handleAudioDeviceInventoryChanged(reason: String) {
        if (shouldUseCommunicationAudioProfile()) {
            updateCommunicationAudioProfile(reason)
        } else {
            refreshAudioRouteState(reason)
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
            refreshAudioRouteState(reason = "profile_restored:$reason")
        }
    }

    @Suppress("DEPRECATION")
    private fun applyCommunicationAudioProfile(reason: String) {
        if (!communicationProfileApplied) {
            savedAudioMode = audioManager.mode
            savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
            savedMicrophoneMute = audioManager.isMicrophoneMute
            savedVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                savedCommunicationDevice = audioManager.communicationDevice
            }
            communicationProfileApplied = true
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        audioManager.isMicrophoneMute = false
        boostStreamVolume(AudioManager.STREAM_VOICE_CALL, TARGET_VOICE_CALL_VOLUME_RATIO)
        boostStreamVolume(AudioManager.STREAM_MUSIC, TARGET_MUSIC_VOLUME_RATIO)

        enforceBluetoothAudioRoute()
        refreshAudioRouteState(reason = "profile_applied:$reason")
        updateTripAudioCaptureForRoute()

        if (!isBluetoothAudioRouteActive) {
            forceStopTransmissionDueToInvalidRoute(reason = "bluetooth_route_unavailable:$reason")
            webRtcClient.setLocalAudioEnabled(false)
        }
        Log.d(
            TAG,
            "Communication audio profile applied. reason=$reason, route=$currentAudioRouteLabel, " +
                "bluetoothRouteActive=$isBluetoothAudioRouteActive"
        )
    }

    @Suppress("DEPRECATION")
    private fun restoreCommunicationAudioProfile(reason: String) {
        if (!communicationProfileApplied) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
            savedCommunicationDevice?.let { device ->
                runCatching { audioManager.setCommunicationDevice(device) }
            }
        } else {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
            }
            if (bluetoothScoStartedByService) {
                audioManager.stopBluetoothSco()
                bluetoothScoStartedByService = false
            }
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
        savedCommunicationDevice = null
        Log.d(TAG, "Communication audio profile restored. reason=$reason")
    }

    @Suppress("DEPRECATION")
    private fun enforceBluetoothAudioRoute() {
        if (!hasBluetoothConnectPermission()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothDevice = findPreferredBluetoothCommunicationDevice()
            if (bluetoothDevice != null) {
                audioManager.setCommunicationDevice(bluetoothDevice)
            } else {
                audioManager.clearCommunicationDevice()
            }
            return
        }

        val hasBluetoothDevice = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            .any { isBluetoothCommunicationDevice(it) }
        if (hasBluetoothDevice) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            bluetoothScoStartedByService = true
        } else {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
            }
            if (bluetoothScoStartedByService) {
                audioManager.stopBluetoothSco()
                bluetoothScoStartedByService = false
            }
        }
    }

    private fun refreshAudioRouteState(reason: String) {
        val state = evaluateCurrentAudioRouteState()
        val changed = state.label != currentAudioRouteLabel || state.isBluetoothActive != isBluetoothAudioRouteActive
        currentAudioRouteLabel = state.label
        isBluetoothAudioRouteActive = state.isBluetoothActive

        if (changed) {
            Log.i(
                TAG,
                "Audio route changed. reason=$reason, label=${state.label}, " +
                    "bluetoothActive=${state.isBluetoothActive}"
            )
            notifyAudioRouteChanged()
        }
    }

    private fun notifyAudioRouteChanged() {
        callback?.onAudioRouteChanged(
            routeLabel = currentAudioRouteLabel,
            isBluetoothActive = isBluetoothAudioRouteActive,
            isBluetoothRequired = true
        )
    }

    private fun evaluateCurrentAudioRouteState(): AudioRouteState {
        if (!hasBluetoothConnectPermission()) {
            return AudioRouteState(AUDIO_ROUTE_LABEL_BT_PERMISSION_MISSING, isBluetoothActive = false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val currentDevice = audioManager.communicationDevice
            if (currentDevice != null) {
                if (isBluetoothCommunicationDevice(currentDevice)) {
                    return AudioRouteState(buildBluetoothRouteLabel(currentDevice), isBluetoothActive = true)
                }
                return AudioRouteState(mapNonBluetoothRouteLabel(currentDevice), isBluetoothActive = false)
            }
            val bluetoothAvailable = audioManager.availableCommunicationDevices.any { isBluetoothCommunicationDevice(it) }
            return if (bluetoothAvailable) {
                AudioRouteState(AUDIO_ROUTE_LABEL_BT_PENDING, isBluetoothActive = false)
            } else {
                AudioRouteState(AUDIO_ROUTE_LABEL_BT_UNAVAILABLE, isBluetoothActive = false)
            }
        }

        val bluetoothAvailable = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            .any { isBluetoothCommunicationDevice(it) }
        if (audioManager.isBluetoothScoOn && bluetoothAvailable) {
            return AudioRouteState(AUDIO_ROUTE_LABEL_BT_ACTIVE, isBluetoothActive = true)
        }
        return if (bluetoothAvailable) {
            AudioRouteState(AUDIO_ROUTE_LABEL_BT_PENDING, isBluetoothActive = false)
        } else {
            AudioRouteState(AUDIO_ROUTE_LABEL_BT_UNAVAILABLE, isBluetoothActive = false)
        }
    }

    private fun updateTripAudioCaptureForRoute() {
        if (!isTripActive) {
            return
        }

        if (!isBluetoothAudioRouteActive) {
            stopAudioCaptureIfNeeded(reason = "bluetooth_route_inactive")
            speechRecognizerHelper?.stopListening()
            return
        }

        val wasCaptureRunning = isAudioCaptureRunning
        startAudioCaptureIfNeeded()
        if (wasCaptureRunning) {
            return
        }
        lifecycleScope.launch {
            if (isTripActive && isBluetoothAudioRouteActive) {
                getOrCreateSpeechRecognizerHelper().startListening()
                scheduleTranscriptionQueueProcessing(reason = "audio_route_recovered")
            }
        }
    }

    private fun forceStopTransmissionDueToInvalidRoute(reason: String) {
        if (!isTransmitting) {
            return
        }
        isTransmitting = false
        releaseAudioDucking()
        sendTransmissionStateToPeer()
        notifyTransmissionStateChanged()
        Log.w(TAG, "Transmission forced OFF due to invalid audio route. reason=$reason")
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun findPreferredBluetoothCommunicationDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }
        return audioManager.availableCommunicationDevices
            .firstOrNull { isBluetoothCommunicationDevice(it) }
    }

    private fun isBluetoothCommunicationDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
            else -> false
        }
    }

    private fun buildBluetoothRouteLabel(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.trim().orEmpty()
        return if (name.isBlank()) {
            AUDIO_ROUTE_LABEL_BT_ACTIVE
        } else {
            "Bluetooth: $name"
        }
    }

    private fun mapNonBluetoothRouteLabel(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AUDIO_ROUTE_LABEL_EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AUDIO_ROUTE_LABEL_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AUDIO_ROUTE_LABEL_WIRED
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> AUDIO_ROUTE_LABEL_USB
            else -> AUDIO_ROUTE_LABEL_PHONE
        }
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

    private fun normalizeVadDelayMs(seconds: Float): Long {
        val clampedMs = (seconds * 1000f).roundToInt().toLong().coerceIn(MIN_VAD_DELAY_MS, MAX_VAD_DELAY_MS)
        return ((clampedMs + 50L) / 100L) * 100L
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

    private fun durationMsToBytes(durationMs: Long): Int {
        if (durationMs <= 0L) {
            return 0
        }
        val sampleCount = (AUDIO_SAMPLE_RATE_HZ * durationMs) / 1000L
        return (sampleCount * AUDIO_BYTES_PER_SAMPLE).toInt().coerceAtLeast(AUDIO_BYTES_PER_SAMPLE)
    }

    private data class TrimmedChunk(
        val bytes: ByteArray,
        val hadSpeech: Boolean,
        val removedLeadingMs: Long,
        val removedTrailingMs: Long
    )

    private fun flushTranscriptionBuffer(force: Boolean, reason: String) {
        val chunk: ByteArray
        val chunkDurationMs: Long
        val silenceMs: Long
        val hadSpeech: Boolean
        val tripIdSnapshot: String?
        val tripHostUidSnapshot: String?
        val tripPathSnapshot: String?
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
            tripIdSnapshot = currentTripId
            tripHostUidSnapshot = currentTripHostUid
            tripPathSnapshot = currentTripPath
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

        val tripId = tripIdSnapshot
        if (tripId.isNullOrBlank()) {
            Log.w(TAG, "Dropping transcription chunk because tripId is null. reason=$reason")
            return
        }

        val trimmedChunk = trimAbsoluteSilence(chunk)
        if (!force && !trimmedChunk.hadSpeech) {
            Log.d(
                TAG,
                "Dropping chunk after silence trimming (no speech). bytes=${chunk.size}, " +
                    "durationMs=$chunkDurationMs, reason=$reason"
            )
            return
        }

        val dataToQueue = when {
            trimmedChunk.bytes.isNotEmpty() -> trimmedChunk.bytes
            force && hadSpeech -> chunk
            else -> ByteArray(0)
        }
        if (dataToQueue.isEmpty()) {
            Log.d(TAG, "Dropping empty trimmed chunk. reason=$reason")
            return
        }

        val queueDurationMs = bytesToDurationMs(dataToQueue.size)
        val chunkCreatedAtMs = System.currentTimeMillis()
        val queuedChunk = transcriptionQueue.enqueue(
            chunk = dataToQueue,
            tripId = tripId,
            hostUid = tripHostUidSnapshot,
            tripPath = tripPathSnapshot,
            createdAtMs = chunkCreatedAtMs,
            durationMs = queueDurationMs
        )
        if (queuedChunk == null) {
            Log.e(TAG, "Failed to enqueue STT chunk for tripId=$tripId")
            return
        }

        totalChunksDispatched++
        val chunkId = totalChunksDispatched

        Log.i(
            TAG,
            "Queued chunk #$chunkId for STT. queueId=${queuedChunk.id}, bytes=${dataToQueue.size}, " +
                "durationMs=$queueDurationMs, originalDurationMs=$chunkDurationMs, silenceMs=$silenceMs, " +
                "hadSpeech=$hadSpeech, trimmedLeadingMs=${trimmedChunk.removedLeadingMs}, " +
                "trimmedTrailingMs=${trimmedChunk.removedTrailingMs}, reason=$reason, sttEngine=$sttEngine"
        )
        persistTranscriptChunkToFirebase(
            chunk = queuedChunk,
            status = TranscriptStatus.PROCESSING,
            text = "",
            errorMessage = null
        )
        publishTranscriptionQueueSnapshot(reason = "chunk_enqueued")
        scheduleTranscriptionQueueProcessing(reason = "chunk_enqueued")
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

    private fun trimAbsoluteSilence(chunk: ByteArray): TrimmedChunk {
        if (chunk.isEmpty()) {
            return TrimmedChunk(ByteArray(0), hadSpeech = false, removedLeadingMs = 0L, removedTrailingMs = 0L)
        }

        val frameBytes = durationMsToBytes(TRANSCRIPTION_TRIM_FRAME_MS).coerceAtLeast(AUDIO_BYTES_PER_SAMPLE)
        if (frameBytes <= 0 || chunk.size <= frameBytes) {
            val hasSpeech = vad.isSpeech(chunk)
            return if (hasSpeech) {
                TrimmedChunk(chunk, hadSpeech = true, removedLeadingMs = 0L, removedTrailingMs = 0L)
            } else {
                TrimmedChunk(ByteArray(0), hadSpeech = false, removedLeadingMs = 0L, removedTrailingMs = bytesToDurationMs(chunk.size))
            }
        }

        var firstSpeechStart = -1
        var lastSpeechEnd = -1
        var cursor = 0

        while (cursor < chunk.size) {
            val end = (cursor + frameBytes).coerceAtMost(chunk.size)
            val frame = chunk.copyOfRange(cursor, end)
            if (vad.isSpeech(frame)) {
                if (firstSpeechStart < 0) {
                    firstSpeechStart = cursor
                }
                lastSpeechEnd = end
            }
            cursor = end
        }

        if (firstSpeechStart < 0 || lastSpeechEnd <= 0) {
            return TrimmedChunk(
                bytes = ByteArray(0),
                hadSpeech = false,
                removedLeadingMs = bytesToDurationMs(chunk.size),
                removedTrailingMs = 0L
            )
        }

        val preRollBytes = durationMsToBytes(TRANSCRIPTION_TRIM_PREROLL_MS)
        val postRollBytes = durationMsToBytes(TRANSCRIPTION_TRIM_POSTROLL_MS)
        var start = (firstSpeechStart - preRollBytes).coerceAtLeast(0)
        var end = (lastSpeechEnd + postRollBytes).coerceAtMost(chunk.size)
        var trimmed = chunk.copyOfRange(start, end)

        if (bytesToDurationMs(trimmed.size) < TRANSCRIPTION_MIN_TRIMMED_MS) {
            start = 0
            end = chunk.size
            trimmed = chunk
        }

        return TrimmedChunk(
            bytes = trimmed,
            hadSpeech = true,
            removedLeadingMs = bytesToDurationMs(start),
            removedTrailingMs = bytesToDurationMs(chunk.size - end)
        )
    }

    private fun scheduleTranscriptionQueueProcessing(reason: String) {
        transcriptionExecutor.execute {
            processTranscriptionQueue(reason)
        }
    }

    private fun processTranscriptionQueue(triggerReason: String) {
        synchronized(transcriptionWorkerLock) {
            if (isTranscriptionWorkerRunning) {
                return
            }
            isTranscriptionWorkerRunning = true
        }

        try {
            val recognizer = getOrCreateSpeechRecognizerHelper()
            recognizer.startListening()
            while (true) {
                val queuedChunk = transcriptionQueue.pollNextPending() ?: break
                publishTranscriptionQueueSnapshot(reason = "chunk_processing_started")

                val audioBytes = transcriptionQueue.readAudioBytes(queuedChunk)
                if (audioBytes == null) {
                    val failureReason = "Missing persisted audio chunk file."
                    transcriptionQueue.markFailed(queuedChunk.id, failureReason)
                    persistTranscriptChunkToFirebase(
                        chunk = queuedChunk,
                        status = TranscriptStatus.ERROR,
                        text = "Transcription error",
                        errorMessage = failureReason
                    )
                    publishTranscriptionQueueSnapshot(reason = "chunk_processing_missing_audio")
                    continue
                }

                val startMs = System.currentTimeMillis()
                val result = runCatching { recognizer.transcribeChunk(audioBytes) }
                    .getOrElse { throwable ->
                        SpeechRecognizerHelper.ChunkTranscriptionResult(
                            error = throwable.message ?: "Unknown STT processing failure."
                        )
                    }
                val elapsedMs = System.currentTimeMillis() - startMs
                val finalText = result.text.orEmpty().trim()

                when {
                    !result.error.isNullOrBlank() -> {
                        transcriptionQueue.markFailed(queuedChunk.id, result.error)
                        persistTranscriptChunkToFirebase(
                            chunk = queuedChunk,
                            status = TranscriptStatus.ERROR,
                            text = "Transcription error",
                            errorMessage = result.error
                        )
                        publishTranscriptionQueueSnapshot(reason = "chunk_processing_error")
                        Log.e(
                            TAG,
                            "Queued STT chunk failed. queueId=${queuedChunk.id}, elapsedMs=$elapsedMs, error=${result.error}"
                        )
                    }
                    finalText.isBlank() -> {
                        val failureReason = "No final transcript generated."
                        transcriptionQueue.markFailed(queuedChunk.id, failureReason)
                        persistTranscriptChunkToFirebase(
                            chunk = queuedChunk,
                            status = TranscriptStatus.ERROR,
                            text = "Transcription error",
                            errorMessage = failureReason
                        )
                        publishTranscriptionQueueSnapshot(reason = "chunk_processing_empty_result")
                        Log.w(
                            TAG,
                            "Queued STT chunk produced empty result. queueId=${queuedChunk.id}, elapsedMs=$elapsedMs"
                        )
                    }
                    else -> {
                        transcriptionQueue.markSucceeded(queuedChunk.id)
                        persistTranscriptChunkToFirebase(
                            chunk = queuedChunk,
                            status = TranscriptStatus.SUCCESS,
                            text = finalText,
                            errorMessage = null
                        )
                        publishTranscriptionQueueSnapshot(reason = "chunk_processing_success")
                        callback?.onTranscriptUpdate(
                            transcript = finalText,
                            isFinal = true,
                            tripId = queuedChunk.tripId,
                            hostUid = queuedChunk.hostUid,
                            tripPath = queuedChunk.tripPath,
                            timestampMs = queuedChunk.createdAtMs
                        )
                        if (currentMode == OperatingMode.VOICE_COMMAND && currentTripId == queuedChunk.tripId && isTripActive) {
                            handleVoiceCommand(finalText)
                        }
                        Log.i(
                            TAG,
                            "Queued STT chunk transcribed. queueId=${queuedChunk.id}, elapsedMs=$elapsedMs, " +
                                "textLen=${finalText.length}"
                        )
                    }
                }
            }
            Log.d(TAG, "Transcription queue drained. triggerReason=$triggerReason")
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected transcription queue worker failure. triggerReason=$triggerReason", t)
        } finally {
            synchronized(transcriptionWorkerLock) {
                isTranscriptionWorkerRunning = false
            }
        }
    }

    private fun persistTranscriptChunkToFirebase(
        chunk: QueuedTranscriptionChunk,
        status: TranscriptStatus,
        text: String,
        errorMessage: String?
    ) {
        lifecycleScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser ?: return@launch
            val destinationUid = resolveTranscriptDestinationUid(chunk, fallbackUid = currentUser.uid)
            if (destinationUid.isNullOrBlank()) {
                Log.w(TAG, "Unable to persist transcript chunk: destination uid missing. chunkId=${chunk.id}")
                return@launch
            }

            val resolvedText = when {
                text.isNotBlank() -> text
                status == TranscriptStatus.PROCESSING -> "Processing audio..."
                status == TranscriptStatus.ERROR -> "Transcription error"
                else -> ""
            }

            val payload: MutableMap<String, Any?> = mutableMapOf(
                "tripId" to chunk.tripId,
                "authorId" to currentUser.uid,
                "authorName" to (currentUser.displayName ?: "Unknown"),
                "text" to resolvedText,
                "timestamp" to chunk.createdAtMs,
                "isPartial" to (status == TranscriptStatus.PROCESSING),
                "status" to status.name
            )
            payload["errorMessage"] = errorMessage

            try {
                firestore.collection(FirestorePaths.ACCOUNTS)
                    .document(destinationUid)
                    .collection(FirestorePaths.RIDES)
                    .document(chunk.tripId)
                    .collection(FirestorePaths.RIDE_TRANSCRIPTS)
                    .document(transcriptDocumentIdForChunk(chunk.id))
                    .set(payload, SetOptions.merge())
                    .await()
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Failed to persist transcript chunk status. chunkId=${chunk.id}, status=$status",
                    t
                )
            }
        }
    }

    private fun transcriptDocumentIdForChunk(chunkId: String): String {
        return "chunk_$chunkId"
    }

    private fun resolveTranscriptDestinationUid(
        chunk: QueuedTranscriptionChunk,
        fallbackUid: String
    ): String? {
        return chunk.hostUid
            ?: parseTripPath(chunk.tripPath).first
            ?: currentTripHostUid
            ?: fallbackUid
    }

    private fun publishTranscriptionQueueSnapshot(reason: String) {
        val snapshot = transcriptionQueue.snapshot()
        callback?.onTranscriptionQueueUpdated(snapshot)
        Log.d(
            TAG,
            "Transcription queue snapshot. reason=$reason, pending=${snapshot.pendingCount}, " +
                "processing=${snapshot.processingCount}, failed=${snapshot.failedCount}, total=${snapshot.totalCount}"
        )
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
            nativeLanguageTag = nativeSpeechLanguageTag,
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
        return SpeechRecognizerHelper(
            context = this,
            listener = this,
            initialModelId = whisperModelId,
            initialEngine = sttEngine,
            initialNativeLanguageTag = nativeSpeechLanguageTag
        ).also { created ->
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
