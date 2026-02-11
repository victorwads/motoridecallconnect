package dev.wads.motoridecallconnect.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.wads.motoridecallconnect.utils.NetworkUtils
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class NsdHelper(private val context: Context, private val listener: NsdListener) {

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var serviceName: String? = null

    interface NsdListener {
        fun onServiceFound(serviceInfo: NsdServiceInfo)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
    }

    companion object {
        const val SERVICE_TYPE = "_motoride._tcp."
        const val TAG = "NsdHelper"
    }

    fun registerService(port: Int, customName: String = "MotoRideConnect") {
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                serviceName = nsdServiceInfo.serviceName
                Log.d(TAG, "Service registered: $serviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: Error code: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: Error code: $errorCode")
            }
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = customName
            this.serviceType = SERVICE_TYPE
            this.port = port

            // Publish all prioritized local IPv4 candidates to work around wrong-interface mDNS resolution.
            val networkSnapshot = NetworkUtils.getNetworkSnapshot()
            val primaryIp = networkSnapshot.primaryIpv4
            val candidateIps = networkSnapshot.ipv4Candidates
            if (!primaryIp.isNullOrBlank()) {
                setAttribute("machine_ip", primaryIp)
                Log.i(TAG, "Added primary IP to NSD attributes: $primaryIp")
            } else {
                Log.w(TAG, "Could not detect primary local IP for NSD attributes.")
            }
            if (candidateIps.isNotEmpty()) {
                // Keep TXT value compact to avoid Android DNS-SD attribute limits.
                val compactCandidates = candidateIps.take(8).joinToString(separator = ",")
                setAttribute("machine_ips", compactCandidates)
                Log.i(TAG, "Added candidate IP list to NSD attributes: $compactCandidates")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun discoverServices() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service discovery success: $service")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "Resolve Succeeded. $serviceInfo")

                        // Prefer manual IP attributes from TXT record when available.
                        val attributes = serviceInfo.attributes
                        val preferredIp = readPreferredIpFromAttributes(attributes)
                        if (!preferredIp.isNullOrBlank()) {
                            try {
                                val manualInetAddress = InetAddress.getByName(preferredIp)
                                serviceInfo.host = manualInetAddress
                                Log.i(TAG, "Overriding resolved host with preferred TXT IP: $preferredIp")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse preferred IP from NSD attributes: $preferredIp", e)
                            }
                        }

                        listener.onServiceFound(serviceInfo)
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "service lost: $service")
                listener.onServiceLost(service)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
    }

    fun unregisterService() {
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering service", e)
            }
            registrationListener = null
            serviceName = null
        }
    }

    fun getRegisteredServiceName(): String? = serviceName

    fun tearDown() {
        if (registrationListener != null) {
            nsdManager.unregisterService(registrationListener)
        }
    }

    private fun readPreferredIpFromAttributes(attributes: Map<String, ByteArray>?): String? {
        if (attributes == null) return null

        val candidateListRaw = attributes["machine_ips"]?.let { bytes ->
            String(bytes, StandardCharsets.UTF_8)
        }
        val candidates = candidateListRaw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (candidates.isNotEmpty()) {
            return candidates.first()
        }

        val machineIpRaw = attributes["machine_ip"]?.let { bytes ->
            String(bytes, StandardCharsets.UTF_8).trim()
        }
        return machineIpRaw?.takeIf { it.isNotEmpty() }
    }
}
