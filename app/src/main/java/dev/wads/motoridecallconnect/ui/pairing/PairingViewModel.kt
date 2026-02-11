package dev.wads.motoridecallconnect.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.repository.DeviceDiscoveryRepository
import dev.wads.motoridecallconnect.utils.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PairingViewModel(private val repository: DeviceDiscoveryRepository) : ViewModel() {

    val discoveredDevices: StateFlow<List<Device>> = repository.discoveredDevices

    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting.asStateFlow()

    private val _qrCodeText = MutableStateFlow<String?>(null)
    val qrCodeText: StateFlow<String?> = _qrCodeText.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun startDiscovery() {
        repository.startDiscovery()
    }

    fun stopDiscovery() {
        repository.stopDiscovery()
    }

    fun startHosting(deviceName: String, port: Int = 8080) {
        _isHosting.value = true
        repository.registerService(port, deviceName)
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            _qrCodeText.value = "motoride://$ip:$port/$deviceName"
        }
    }

    fun stopHosting() {
        _isHosting.value = false
        _qrCodeText.value = null
        repository.unregisterService()
    }

    fun connectToDevice(device: Device) {
        // Logic to connect via AudioService will be handled in the UI 
        // by starting the service or calling a method on it.
        _isConnected.value = true
    }

    fun handleScannedCode(code: String) {
        // Expected format: motoride://ip:port/name
        if (code.startsWith("motoride://")) {
            val parts = code.removePrefix("motoride://").split("/")
            if (parts.isNotEmpty()) {
                val addressParts = parts[0].split(":")
                if (addressParts.size == 2) {
                    val ip = addressParts[0]
                    val port = addressParts[1].toIntOrNull() ?: 8080
                    val name = if (parts.size > 1) parts[1] else "Unknown"
                    connectToDevice(Device(id = name, name = name, deviceName = name, ip = ip, port = port))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopDiscovery()
    }
}
