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

class WebRtcClient(
    context: Context,
    private val observer: PeerConnection.Observer
) {

    private val peerConnectionFactory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(null, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(null)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
        iceTransportsType = PeerConnection.IceTransportsType.ALL
    }

    private var peerConnection: PeerConnection? = peerConnectionFactory.createPeerConnection(rtcConfig, observer)

    private val audioConstraints = MediaConstraints()
    private val audioSource: AudioSource? = peerConnectionFactory.createAudioSource(audioConstraints)
    val localAudioTrack: AudioTrack? = peerConnectionFactory.createAudioTrack("local_audio_track", audioSource)

    fun createOffer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(sdpObserver, constraints)
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(sdpObserver, constraints)
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

    fun close() {
        peerConnection?.close()
        audioSource?.dispose()
        peerConnectionFactory.dispose()
    }
}