package dev.wads.motoridecallconnect.utils

import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    data class InterfaceAddressInfo(
        val interfaceName: String,
        val displayName: String,
        val address: String,
        val isIpv4: Boolean,
        val isSiteLocal: Boolean,
        val isLinkLocal: Boolean,
        val score: Int
    )

    data class NetworkSnapshot(
        val primaryIpv4: String?,
        val ipv4Candidates: List<String>,
        val interfaceAddresses: List<InterfaceAddressInfo>
    )

    /**
     * Get the local IP address, prioritizing interfaces likely to be Wi-Fi or Hotspot.
     * This avoids returning the cellular IP address (rmnet) which is often unreachable from peers.
     */
    fun getLocalIpAddress(): String? {
        return getNetworkSnapshot().primaryIpv4
    }

    fun getPrioritizedIpv4Addresses(): List<String> {
        return getNetworkSnapshot().ipv4Candidates
    }

    fun getNetworkSnapshot(): NetworkSnapshot {
        val interfaceAddresses = mutableListOf<InterfaceAddressInfo>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr.isLoopbackAddress) continue
                    val sAddr = addr.hostAddress ?: continue
                    val isIpv4 = sAddr.indexOf(':') < 0
                    val cleanedAddress = sAddr.substringBefore('%') // Strip IPv6 scope ID for readability.
                    interfaceAddresses += InterfaceAddressInfo(
                        interfaceName = intf.name,
                        displayName = intf.displayName ?: intf.name,
                        address = cleanedAddress,
                        isIpv4 = isIpv4,
                        isSiteLocal = addr.isSiteLocalAddress,
                        isLinkLocal = addr.isLinkLocalAddress,
                        score = scoreAddress(
                            interfaceName = intf.name,
                            address = cleanedAddress,
                            isIpv4 = isIpv4,
                            isSiteLocal = addr.isSiteLocalAddress,
                            isLinkLocal = addr.isLinkLocalAddress
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        val sortedIpv4 = interfaceAddresses
            .asSequence()
            .filter { it.isIpv4 }
            .sortedWith(compareByDescending<InterfaceAddressInfo> { it.score }.thenBy { it.address })
            .map { it.address }
            .distinct()
            .toList()

        return NetworkSnapshot(
            primaryIpv4 = sortedIpv4.firstOrNull(),
            ipv4Candidates = sortedIpv4,
            interfaceAddresses = interfaceAddresses.sortedWith(
                compareByDescending<InterfaceAddressInfo> { it.score }
                    .thenBy { it.interfaceName }
                    .thenBy { it.address }
            )
        )
    }

    private fun scoreAddress(
        interfaceName: String,
        address: String,
        isIpv4: Boolean,
        isSiteLocal: Boolean,
        isLinkLocal: Boolean
    ): Int {
        var score = 0
        val name = interfaceName.lowercase()

        if (name.contains("wlan") || name.contains("ap") || name.contains("swlan")) score += 400
        if (name.contains("rndis")) score += 350
        if (name.contains("eth")) score += 300
        if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp") || name.contains("cell")) {
            score -= 350
        }
        if (name.contains("tun") || name.contains("lo") || name.contains("docker") || name.contains("veth")) {
            score -= 250
        }

        if (isIpv4) {
            score += 120
        } else {
            score -= 50
        }

        if (isSiteLocal) score += 120
        if (isLinkLocal) score -= 120

        if (address.startsWith("192.168.")) score += 80
        if (address.startsWith("10.")) score += 70
        if (isPrivate172Range(address)) score += 70

        return score
    }

    private fun isPrivate172Range(address: String): Boolean {
        if (!address.startsWith("172.")) return false
        val secondOctet = address.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return secondOctet in 16..31
    }
}
