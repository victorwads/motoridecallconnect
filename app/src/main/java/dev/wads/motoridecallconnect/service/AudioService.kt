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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.audio.AudioCapturer
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.stt.SpeechRecognizerHelper
import dev.wads.motoridecallconnect.transport.SignalingClient
import dev.wads.motoridecallconnect.transport.WebRtcClient
import dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import dev.wads.motoridecallconnect.vad.SimpleVad
import kotlinx.coroutines.launch
import org.webrtc.*
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioService : LifecycleService(), AudioCapturer.AudioCapturerListener, SpeechRecognizerHelper.SpeechRecognitionListener, SignalingClient.SignalingListener {

    companion object {
        private const val TAG = "AudioService"
        private const val PIPELINE_LOG_INTERVAL_MS = 5_000L
        private const val AUDIO_SAMPLE_RATE_HZ = 48_000
        private const val AUDIO_BYTES_PER_SAMPLE = 2

        // Tuning knobs for chunk-based transcription behavior.
        private const val TRANSCRIPTION_MIN_CONTEXT_MS = 3_000L
        private const val TRANSCRIPTION_SILENCE_FLUSH_MS = 5_000L
        private const val TRANSCRIPTION_MAX_CHUNK_MS = 45_000L
    }

    private val binder = LocalBinder()
    private val CHANNEL_ID = "AudioServiceChannel"
    private lateinit var audioCapturer: AudioCapturer
    private lateinit var speechRecognizerHelper: SpeechRecognizerHelper
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
    private var connectedPeer: Device? = null
    private var connectionStatus = ConnectionStatus.DISCONNECTED
    private val audioBuffer = mutableListOf<Byte>()
    private var lastTransmissionTime = 0L
    private var totalFramesReceived = 0L
    private var totalChunksDispatched = 0L
    private var bufferedAudioDurationMs = 0L
    private var consecutiveSilenceDurationMs = 0L

    interface ServiceCallback {
        fun onTranscriptUpdate(transcript: String, isFinal: Boolean)
        fun onConnectionStatusChanged(status: dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus, peer: Device?)
        fun onTripStatusChanged(isActive: Boolean, tripId: String? = null)
        fun onModelDownloadProgress(progress: Int)
        fun onModelDownloadStateChanged(isDownloading: Boolean, isSuccess: Boolean? = null)
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
             signalingClient.sendMessage("ICE:${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp.replace("\n", "|")}")
        }
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d("AudioService", "IceConnectionChange: $state")
            connectionStatus = when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> 
                    ConnectionStatus.CONNECTED
                PeerConnection.IceConnectionState.CHECKING -> 
                    ConnectionStatus.CONNECTING
                PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.CLOSED -> 
                    ConnectionStatus.DISCONNECTED
                else -> ConnectionStatus.DISCONNECTED
            }
            callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onAddStream(p0: MediaStream?) { Log.d("AudioService", "Received remote stream") }
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
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
        speechRecognizerHelper = SpeechRecognizerHelper(this, this)
        signalingClient = SignalingClient(this)
        webRtcClient = WebRtcClient(this, peerConnectionObserver)
        Log.i(TAG, "Service created. WhisperAvailable=${speechRecognizerHelper.isUsingWhisper}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = createNotification()
        startForeground(1, notification)
        audioCapturer.startCapture()
        
        lifecycleScope.launch {
            speechRecognizerHelper.downloadModelIfNeeded()
        }

        speechRecognizerHelper.startListening()
        signalingClient.startServer(8080) 
        Log.i(
            TAG,
            "Service started. Mode=$currentMode, TripActive=$isTripActive, Whisper=${speechRecognizerHelper.isUsingWhisper}"
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        releaseAudioDucking()
        audioCapturer.stopCapture()
        flushTranscriptionBuffer(force = true, reason = "service_destroy")
        transcriptionExecutor.shutdown()
        if (!transcriptionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            transcriptionExecutor.shutdownNow()
        }
        speechRecognizerHelper.destroy()
        signalingClient.close()
    }

    fun registerCallback(callback: ServiceCallback) {
        this.callback = callback
        // Update immediately with current state
        callback.onConnectionStatusChanged(connectionStatus, connectedPeer)
    }

    fun connectToPeer(device: Device) {
        connectedPeer = device
        connectionStatus = ConnectionStatus.CONNECTING
        callback?.onConnectionStatusChanged(connectionStatus, device)
        if (device.ip != null && device.port != null) {
            signalingClient.connectToPeer(InetAddress.getByName(device.ip), device.port)
        }
    }

    fun disconnect() {
        signalingClient.close()
        webRtcClient.close()
        // Re-initialize for next potential connection
        signalingClient = SignalingClient(this)
        webRtcClient = WebRtcClient(this, peerConnectionObserver)
        connectedPeer = null
        connectionStatus = ConnectionStatus.DISCONNECTED
        callback?.onConnectionStatusChanged(connectionStatus, null)
        signalingClient.startServer(8080)
    }

    fun updateConfiguration(mode: OperatingMode, startCmd: String, stopCmd: String, tripActive: Boolean, tripId: String? = null) {
        val tripChanged = isTripActive != tripActive
        val wasTripActive = isTripActive
        currentMode = mode
        startCommand = startCmd
        stopCommand = stopCmd
        isTripActive = tripActive

        if (tripChanged && wasTripActive && !tripActive) {
            flushTranscriptionBuffer(force = true, reason = "trip_end")
        }
        if (tripChanged && tripActive) {
            resetTranscriptionState(clearAudio = true)
        }

        if (tripChanged) {
            val msg = if (isTripActive) "TRIP:START${if (tripId != null) ":$tripId" else ""}" else "TRIP:STOP"
            signalingClient.sendMessage(msg)
            Log.i(TAG, "Trip status changed locally. isTripActive=$isTripActive, tripId=$tripId")
        }

        Log.d(TAG, "Configuration updated: Mode=$mode, Start=$startCmd, Stop=$stopCmd, TripActive=$tripActive")
        
        if (currentMode == OperatingMode.CONTINUOUS_TRANSMISSION && !isTransmitting) {
            isTransmitting = true
            requestAudioDucking()
        } else if (currentMode != OperatingMode.CONTINUOUS_TRANSMISSION && isTransmitting) {
            if (currentMode != OperatingMode.VOICE_COMMAND) {
                isTransmitting = false
                releaseAudioDucking()
            }
        }
    }

    // --- SignalingListener Callbacks ---
    override fun onPeerConnected() {
        Log.d("AudioService", "Peer connected, sending name and creating offer")
        connectionStatus = ConnectionStatus.CONNECTING
        callback?.onConnectionStatusChanged(connectionStatus, connectedPeer)
        signalingClient.sendMessage("NAME:${Build.MODEL}")
        webRtcClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                webRtcClient.setLocalDescription(this, sdp)
                signalingClient.sendMessage("OFFER:${sdp.description.replace("\n", "|")}")
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        })
    }

    override fun onPeerInfoReceived(name: String) {
        Log.d("AudioService", "Peer info received: $name")
        if (connectedPeer == null) {
            connectedPeer = Device(id = name, name = name, deviceName = name)
        } else {
            connectedPeer = connectedPeer?.copy(name = name, deviceName = name)
        }
        callback?.onConnectionStatusChanged(dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus.CONNECTING, connectedPeer)
    }

    override fun onOfferReceived(description: String) {
        val sdpString = description.replace("|", "\n")
        val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
        
        webRtcClient.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                webRtcClient.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription) {
                        webRtcClient.setLocalDescription(this, answerSdp)
                        signalingClient.sendMessage("ANSWER:${answerSdp.description.replace("\n", "|")}")
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(s: String?) {}
                    override fun onSetFailure(s: String?) {}
                })
            }
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, sdp)
    }

    override fun onAnswerReceived(description: String) {
        val sdpString = description.replace("|", "\n")
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
        webRtcClient.setRemoteDescription(object : SdpObserver {
             override fun onSetSuccess() {}
             override fun onCreateSuccess(s: SessionDescription?) {}
             override fun onCreateFailure(s: String?) {}
             override fun onSetFailure(s: String?) {}
        }, sdp)
    }

    override fun onIceCandidateReceived(candidate: String) {
        val parts = candidate.split(":", limit = 3)
        if (parts.size >= 3) {
            val mid = parts[0]
            val index = parts[1].toInt()
            val sdp = parts[2].replace("|", "\n")
            webRtcClient.addIceCandidate(IceCandidate(mid, index, sdp))
        }
    }

    override fun onTripStatusReceived(active: Boolean, tripId: String?) {
        Log.d(TAG, "Trip status received: $active, id=$tripId")
        isTripActive = active
        callback?.onTripStatusChanged(active, tripId)
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
                    "mode=$currentMode, whisper=${speechRecognizerHelper.isUsingWhisper}, transmitting=$isTransmitting"
            )
        }
        
        // 1. Handle Intercom Transmission (VAD Logic)
        if (currentMode == OperatingMode.VOICE_ACTIVITY_DETECTION) {
            if (isSpeechDetected) {
                if (!isTransmitting) {
                    isTransmitting = true
                    requestAudioDucking()
                    Log.i("AudioService", "Speech detected. Transmission ON.")
                }
            } else {
                if (isTransmitting) {
                    isTransmitting = false
                    releaseAudioDucking()
                    Log.i("AudioService", "Silence detected. Transmission OFF.")
                }
            }
        }

        // 2. Handle Transcription Recording
        // We accumulate audio if trip is active and flush after sustained silence.
        if (isTripActive) {
            audioBuffer.addAll(currentData.toList())

            val frameDurationMs = bytesToDurationMs(size)
            bufferedAudioDurationMs += frameDurationMs

            if (isSpeechDetected) {
                consecutiveSilenceDurationMs = 0L
            } else {
                consecutiveSilenceDurationMs += frameDurationMs
            }

            val hasMinimumContext = bufferedAudioDurationMs >= TRANSCRIPTION_MIN_CONTEXT_MS
            val silenceFlushReached = consecutiveSilenceDurationMs >= TRANSCRIPTION_SILENCE_FLUSH_MS
            val maxChunkReached = bufferedAudioDurationMs >= TRANSCRIPTION_MAX_CHUNK_MS

            if (hasMinimumContext && (silenceFlushReached || maxChunkReached)) {
                val reason = if (maxChunkReached) "max_chunk_timeout" else "silence_timeout"
                flushTranscriptionBuffer(force = false, reason = reason)
            }
        } else if (audioBuffer.isNotEmpty()) {
            resetTranscriptionState(clearAudio = true)
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
        Log.i(TAG, "Model download finished. Success: $success. Whisper=${speechRecognizerHelper.isUsingWhisper}")
        callback?.onModelDownloadStateChanged(false, success)
    }

    override fun onError(error: String) {
        Log.e(TAG, "Speech Recognizer Error: $error")
    }

    private fun handleVoiceCommand(command: String) {
        if (command.contains(startCommand, ignoreCase = true) && !isTransmitting) {
            isTransmitting = true
            requestAudioDucking()
            Log.i("AudioService", "Start command detected. Transmission ON.")
        } else if (command.contains(stopCommand, ignoreCase = true) && isTransmitting) {
            isTransmitting = false
            releaseAudioDucking()
            Log.i("AudioService", "Stop command detected. Transmission OFF.")
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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
            .build()
    }

    private fun bytesToDurationMs(byteCount: Int): Long {
        if (byteCount <= 0) return 0L
        val sampleCount = byteCount / AUDIO_BYTES_PER_SAMPLE
        return (sampleCount * 1000L) / AUDIO_SAMPLE_RATE_HZ
    }

    private fun flushTranscriptionBuffer(force: Boolean, reason: String) {
        if (audioBuffer.isEmpty()) {
            resetTranscriptionState(clearAudio = false)
            return
        }

        if (!force && bufferedAudioDurationMs < TRANSCRIPTION_MIN_CONTEXT_MS) {
            return
        }

        val chunk = audioBuffer.toByteArray()
        val chunkDurationMs = bufferedAudioDurationMs
        val silenceMs = consecutiveSilenceDurationMs
        resetTranscriptionState(clearAudio = true)

        totalChunksDispatched++
        val chunkId = totalChunksDispatched

        Log.i(
            TAG,
            "Dispatching chunk #$chunkId to STT. bytes=${chunk.size}, durationMs=$chunkDurationMs, " +
                "silenceMs=$silenceMs, reason=$reason, whisper=${speechRecognizerHelper.isUsingWhisper}"
        )

        transcriptionExecutor.execute {
            try {
                speechRecognizerHelper.processAudio(chunk)
            } catch (t: Throwable) {
                Log.e(TAG, "STT chunk #$chunkId failed", t)
            }
        }
    }

    private fun resetTranscriptionState(clearAudio: Boolean) {
        if (clearAudio) {
            audioBuffer.clear()
        }
        bufferedAudioDurationMs = 0L
        consecutiveSilenceDurationMs = 0L
    }
}
