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
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import dev.wads.motoridecallconnect.vad.SimpleVad

class AudioService : Service(), AudioCapturer.AudioCapturerListener, SpeechRecognizerHelper.SpeechRecognitionListener, SignalingClient.SignalingListener {

    private val binder = LocalBinder()
    private val CHANNEL_ID = "AudioServiceChannel"
    private lateinit var audioCapturer: AudioCapturer
    private lateinit var speechRecognizerHelper: SpeechRecognizerHelper
    private lateinit var signalingClient: SignalingClient
    private lateinit var audioManager: AudioManager
    private lateinit var vad: SimpleVad
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
    override fun onOfferReceived(description: String) {
        // TODO: Handle received offer with WebRtcClient
    }

    override fun onAnswerReceived(description: String) {
        // TODO: Handle received answer with WebRtcClient
    }

    override fun onIceCandidateReceived(candidate: String) {
        // TODO: Handle received ICE candidate with WebRtcClient
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