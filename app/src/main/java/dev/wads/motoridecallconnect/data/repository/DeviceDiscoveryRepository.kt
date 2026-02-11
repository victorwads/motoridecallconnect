package dev.wads.motoridecallconnect.data.repository

import android.content.Context
import android.net.nsd.NsdServiceInfo
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.transport.NsdHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceDiscoveryRepository(context: Context) : NsdHelper.NsdListener {

    private val nsdHelper = NsdHelper(context, this)
    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    fun startDiscovery() {
        _discoveredDevices.value = emptyList()
        nsdHelper.discoverServices()
    }

    fun stopDiscovery() {
        nsdHelper.stopDiscovery()
    }

    fun registerService(port: Int, name: String) {
        nsdHelper.registerService(port, name)
    }

    fun unregisterService() {
        nsdHelper.unregisterService()
    }

    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        val device = Device(
            id = serviceInfo.serviceName,
            name = serviceInfo.serviceName,
            deviceName = "Android Device", // Could be refined
            ip = serviceInfo.host?.hostAddress,
            port = serviceInfo.port
        )
        if (!_discoveredDevices.value.any { it.id == device.id }) {
            _discoveredDevices.value = _discoveredDevices.value + device
        }
    }

    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        _discoveredDevices.value = _discoveredDevices.value.filter { it.id != serviceInfo.serviceName }
    }
}
