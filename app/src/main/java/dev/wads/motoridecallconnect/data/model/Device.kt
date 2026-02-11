package dev.wads.motoridecallconnect.data.model

import android.net.nsd.NsdServiceInfo

data class Device(
    val id: String,
    val name: String,
    val deviceName: String, // e.g., "Moto G100"
    val rssi: Int = -50, // Signal strength
    val ip: String? = null,
    val port: Int? = null
) {
    companion object {
        fun fromNsdServiceInfo(serviceInfo: NsdServiceInfo): Device {
            val rawName = serviceInfo.serviceName
            val parts = rawName.split("|")
            val userId = parts.getOrNull(0) ?: rawName
            val displayName = parts.getOrNull(1) ?: rawName

            return Device(
                id = userId,
                name = displayName,
                deviceName = "Android Device",
                ip = serviceInfo.host?.hostAddress,
                port = serviceInfo.port
            )
        }
    }
}
