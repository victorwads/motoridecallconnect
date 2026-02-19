package dev.wads.motoridecallconnect.data.model

data class WifiDirectState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val discovering: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val groupFormed: Boolean = false,
    val groupOwner: Boolean = false,
    val groupOwnerIp: String? = null,
    val localDeviceName: String? = null,
    val failureMessage: String? = null
)
