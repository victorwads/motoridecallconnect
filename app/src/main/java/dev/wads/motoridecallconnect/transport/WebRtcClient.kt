package dev.wads.motoridecallconnect.transport

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.DataChannel

class WebRtcClient(
    context: Context,
    private val observer: PeerConnection.Observer
) {
    companion object {
        private const val TAG = "WebRtcClient"
    }

    private val peerConnectionFactory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val options = PeerConnectionFactory.Options()
        
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(null, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(null)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
        iceTransportsType = PeerConnection.IceTransportsType.ALL
    }

    private var peerConnection: PeerConnection? = peerConnectionFactory.createPeerConnection(rtcConfig, observer).apply {
        // Add existing tracks if any
    }

    private val audioConstraints = MediaConstraints()
    private val audioSource: AudioSource? = peerConnectionFactory.createAudioSource(audioConstraints)
    private val localAudioTrack: AudioTrack? = peerConnectionFactory.createAudioTrack("local_audio_track", audioSource).apply {
        this?.setEnabled(false)
        this?.let { peerConnection?.addTrack(it) }
    }

    fun createOffer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(sdpObserver, constraints)
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createAnswer(sdpObserver, constraints)
    }

    fun createDataChannel(label: String): DataChannel? {
        val init = DataChannel.Init().apply {
            ordered = true
            negotiated = false
        }
        return peerConnection?.createDataChannel(label, init)
    }

    fun setLocalDescription(sdpObserver: SdpObserver, sdp: SessionDescription) {
        peerConnection?.setLocalDescription(sdpObserver, sdp)
    }

    fun setRemoteDescription(sdpObserver: SdpObserver, sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(sdpObserver, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setLocalAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Local audio track enabled=$enabled")
    }

    fun close() {
        peerConnection?.close()
        audioSource?.dispose()
        peerConnectionFactory.dispose()
    }
}
