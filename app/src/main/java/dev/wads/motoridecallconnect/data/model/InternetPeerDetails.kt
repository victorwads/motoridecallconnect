package dev.wads.motoridecallconnect.data.model

data class InternetPeerDetails(
    val uid: String,
    val displayName: String,
    val device: Device,
    val canConnect: Boolean,
    val usingPublicFallback: Boolean,
    val privateDataAccessible: Boolean,
    val acceptingInternetCalls: Boolean,
    val signalingMode: String?,
    val lastOnlineMs: Long?,
    val lastConnectionUpdateMs: Long?,
    val publicIpCandidates: List<String>,
    val privateIpCandidates: List<String>,
    val debugPublicJson: String,
    val debugPrivateJson: String,
    val warningMessage: String? = null
)
