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
        private const val LOCAL_TRACK_GAIN = 1.0
    }

    private val pendingIceLock = Any()
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    @Volatile
    private var isRemoteDescriptionSet = false

    private val peerConnectionFactory: PeerConnectionFactory

    init {
        // Disable mDNS ICE candidate obfuscation to improve LAN/hotspot-only interoperability.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("WebRTC-MDNS/Disabled/")
                .createInitializationOptions()
        )
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
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        iceCandidatePoolSize = 2
    }

    private var peerConnection: PeerConnection? = null

    private val audioConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    }
    private val audioSource: AudioSource? = peerConnectionFactory.createAudioSource(audioConstraints)
    private val localAudioTrack: AudioTrack? = peerConnectionFactory
        .createAudioTrack("local_audio_track", audioSource)
        .apply {
            this?.setVolume(LOCAL_TRACK_GAIN)
            this?.setEnabled(false)
        }

    init {
        createPeerConnection()
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
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                if (sdp.type == SessionDescription.Type.OFFER || sdp.type == SessionDescription.Type.ANSWER) {
                    isRemoteDescriptionSet = true
                    flushPendingIceCandidates()
                }
                sdpObserver.onSetSuccess()
            }

            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sdpObserver.onCreateSuccess(sessionDescription)
            }

            override fun onCreateFailure(reason: String?) {
                sdpObserver.onCreateFailure(reason)
            }

            override fun onSetFailure(reason: String?) {
                sdpObserver.onSetFailure(reason)
            }
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (!isRemoteDescriptionSet) {
            synchronized(pendingIceLock) {
                pendingRemoteIceCandidates.add(candidate)
            }
            Log.d(TAG, "Queued remote ICE candidate until remote description is ready.")
            return
        }
        val added = peerConnection?.addIceCandidate(candidate) ?: false
        if (!added) {
            Log.w(TAG, "Failed to add remote ICE candidate after remote description was set.")
        }
    }

    fun setLocalAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Local audio track enabled=$enabled")
    }

    fun close() {
        synchronized(pendingIceLock) {
            pendingRemoteIceCandidates.clear()
            isRemoteDescriptionSet = false
        }
        peerConnection?.close()
        peerConnection = null
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnectionFactory.dispose()
    }

    private fun createPeerConnection() {
        isRemoteDescriptionSet = false
        synchronized(pendingIceLock) {
            pendingRemoteIceCandidates.clear()
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        if (peerConnection == null) {
            Log.e(TAG, "Failed to create PeerConnection instance.")
            return
        }
        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track)
        }
    }

    private fun flushPendingIceCandidates() {
        val candidates = synchronized(pendingIceLock) {
            if (pendingRemoteIceCandidates.isEmpty()) {
                return
            }
            val copy = pendingRemoteIceCandidates.toList()
            pendingRemoteIceCandidates.clear()
            copy
        }
        candidates.forEach { candidate ->
            val added = peerConnection?.addIceCandidate(candidate) ?: false
            if (!added) {
                Log.w(TAG, "Failed to flush queued ICE candidate after remote description.")
            }
        }
        Log.d(TAG, "Flushed ${candidates.size} queued ICE candidates.")
    }
}
