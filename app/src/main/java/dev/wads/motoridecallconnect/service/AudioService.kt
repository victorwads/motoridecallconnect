package dev.wads.motoridecallconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.audio.AudioCapturer
import dev.wads.motoridecallconnect.stt.SpeechRecognizerHelper
import dev.wads.motoridecallconnect.transport.SignalingClient
import dev.wads.motoridecallconnect.transport.WebRtcClient
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import dev.wads.motoridecallconnect.vad.SimpleVad
import org.webrtc.*

class AudioService : Service(), AudioCapturer.AudioCapturerListener, SpeechRecognizerHelper.SpeechRecognitionListener, SignalingClient.SignalingListener {

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

    private var callback: ServiceCallback? = null

    // Service State
    private var currentMode = OperatingMode.VOICE_COMMAND
    private var startCommand = "iniciar"
    private var stopCommand = "parar"
    private var isTransmitting = false

    interface ServiceCallback {
        fun onTranscriptUpdate(transcript: String, isFinal: Boolean)
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
             signalingClient.sendMessage("ICE:${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp.replace("\n", "|")}")
        }
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)
        audioCapturer.startCapture()
        speechRecognizerHelper.startListening()
        signalingClient.startServer(8080) // Same fixed port for now
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        releaseAudioDucking()
        audioCapturer.stopCapture()
        speechRecognizerHelper.destroy()
        signalingClient.close()
    }

    fun registerCallback(callback: ServiceCallback) {
        this.callback = callback
    }

    fun connectToPeer(serviceInfo: NsdServiceInfo) {
        signalingClient.connectToPeer(serviceInfo.host, serviceInfo.port)
        // TODO: Once connected, create WebRTC PeerConnection and send offer
    }

    fun updateConfiguration(mode: OperatingMode, startCmd: String, stopCmd: String) {
        currentMode = mode
        startCommand = startCmd
        stopCommand = stopCmd
        Log.d("AudioService", "Configuration updated: Mode=$mode, Start=$startCmd, Stop=$stopCmd")
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
        Log.d("AudioService", "Peer connected, creating offer")
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

    // --- AudioCapturerListener Callbacks ---
    override fun onAudioData(data: ByteArray, size: Int) {
        when (currentMode) {
            OperatingMode.VOICE_ACTIVITY_DETECTION -> {
                if (vad.isSpeech(data.sliceArray(0 until size))) {
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
            else -> { /* Do nothing for other modes here */ }
        }

        if (isTransmitting) {
            // TODO: Send audio data via WebRTC
        }
    }

    // --- SpeechRecognitionListener Callbacks ---
    override fun onPartialResults(results: String) {
        callback?.onTranscriptUpdate(results, false)
    }

    override fun onFinalResults(results: String) {
        callback?.onTranscriptUpdate(results, true)
        if (currentMode == OperatingMode.VOICE_COMMAND) {
            handleVoiceCommand(results)
        }
    }

    override fun onError(error: String) {
        Log.e("AudioService", "Speech Recognizer Error: $error")
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
}