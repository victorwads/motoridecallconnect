package dev.wads.motoridecallconnect.data.repository

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.transport.NsdHelper
import dev.wads.motoridecallconnect.utils.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceDiscoveryRepository(context: Context) : NsdHelper.NsdListener {

    private val nsdHelper = NsdHelper(context, this)
    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()
    private var localRegistrationName: String? = null
    private var localRegistrationPort: Int? = null

    fun startDiscovery() {
        _discoveredDevices.value = emptyList()
        nsdHelper.discoverServices()
    }

    fun stopDiscovery() {
        nsdHelper.stopDiscovery()
    }

    fun registerService(port: Int, name: String) {
        localRegistrationName = name
        localRegistrationPort = port
        nsdHelper.registerService(port, name)
    }

    fun unregisterService() {
        localRegistrationName = null
        localRegistrationPort = null
        nsdHelper.unregisterService()
    }

    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (isSelfService(serviceInfo)) {
            Log.d(TAG, "Ignoring own discovered service: ${serviceInfo.serviceName}")
            return
        }

        val device = Device.fromNsdServiceInfo(serviceInfo)
        if (!_discoveredDevices.value.any { it.id == device.id }) {
            _discoveredDevices.value = _discoveredDevices.value + device
        }
    }

    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        val lostDeviceId = Device.fromNsdServiceInfo(serviceInfo).id
        _discoveredDevices.value = _discoveredDevices.value.filter { it.id != lostDeviceId }
    }

    private fun isSelfService(serviceInfo: NsdServiceInfo): Boolean {
        val resolvedServiceName = serviceInfo.serviceName
        val registeredServiceName = nsdHelper.getRegisteredServiceName() ?: localRegistrationName
        if (!registeredServiceName.isNullOrBlank() && resolvedServiceName == registeredServiceName) {
            return true
        }

        val localIp = NetworkUtils.getLocalIpAddress()
        val resolvedIp = serviceInfo.host?.hostAddress
        val registeredPort = localRegistrationPort
        return !localIp.isNullOrBlank() &&
            !resolvedIp.isNullOrBlank() &&
            localIp == resolvedIp &&
            (registeredPort == null || serviceInfo.port == registeredPort)
    }

    companion object {
        private const val TAG = "DeviceDiscoveryRepo"
    }
}
