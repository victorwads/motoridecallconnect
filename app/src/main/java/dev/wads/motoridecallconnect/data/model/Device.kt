package dev.wads.motoridecallconnect.data.model

data class Device(
    val id: String,
    val name: String,
    val deviceName: String, // e.g., "Moto G100"
    val rssi: Int = -50, // Signal strength
    val ip: String? = null,
    val port: Int? = null
)
