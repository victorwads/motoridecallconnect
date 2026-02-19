package dev.wads.motoridecallconnect.data.model

import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import java.nio.charset.StandardCharsets

data class Device(
    val id: String,
    val name: String,
    val deviceName: String, // e.g., "Moto G100"
    val rssi: Int = -50, // Signal strength
    val ip: String? = null,
    val port: Int? = null,
    val candidateIps: List<String> = emptyList(),
    val connectionTransport: ConnectionTransportMode = ConnectionTransportMode.LOCAL_NETWORK,
    val wifiDirectDeviceAddress: String? = null
) {
    companion object {
        private const val DEFAULT_SIGNALING_PORT = 8080

        fun fromNsdServiceInfo(serviceInfo: NsdServiceInfo): Device {
            val rawName = serviceInfo.serviceName
            val parts = rawName.split("|")
            val userId = parts.getOrNull(0) ?: rawName
            val displayName = parts.getOrNull(1) ?: rawName
            val hostIp = serviceInfo.host?.hostAddress
            val candidateIps = buildCandidateIpList(
                hostIp = hostIp,
                machineIp = readAttribute(serviceInfo, "machine_ip"),
                machineIps = readAttribute(serviceInfo, "machine_ips")
            )

            return Device(
                id = userId,
                name = displayName,
                deviceName = "Android Device",
                ip = candidateIps.firstOrNull() ?: hostIp,
                port = serviceInfo.port,
                candidateIps = candidateIps,
                connectionTransport = ConnectionTransportMode.LOCAL_NETWORK
            )
        }

        fun fromWifiP2pDevice(device: WifiP2pDevice): Device {
            val displayName = device.deviceName?.trim().takeUnless { it.isNullOrBlank() } ?: "Wi-Fi Direct"
            val endpointId = device.deviceAddress?.trim().takeUnless { it.isNullOrBlank() } ?: displayName
            return Device(
                id = endpointId,
                name = displayName,
                deviceName = displayName,
                ip = null,
                port = DEFAULT_SIGNALING_PORT,
                candidateIps = emptyList(),
                connectionTransport = ConnectionTransportMode.WIFI_DIRECT,
                wifiDirectDeviceAddress = device.deviceAddress
            )
        }

        private fun readAttribute(serviceInfo: NsdServiceInfo, key: String): String? {
            val bytes = serviceInfo.attributes[key] ?: return null
            return String(bytes, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }

        private fun buildCandidateIpList(
            hostIp: String?,
            machineIp: String?,
            machineIps: String?
        ): List<String> {
            return buildList {
                if (!hostIp.isNullOrBlank()) add(hostIp)
                if (!machineIp.isNullOrBlank()) add(machineIp)
                if (!machineIps.isNullOrBlank()) {
                    machineIps.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { add(it) }
                }
            }.distinct()
        }
    }
}
