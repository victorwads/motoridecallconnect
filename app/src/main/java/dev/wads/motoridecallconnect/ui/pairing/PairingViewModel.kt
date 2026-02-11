package dev.wads.motoridecallconnect.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
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
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
        val registrationName = "$userId|$deviceName"
        repository.registerService(port, registrationName)
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            _qrCodeText.value = "motoride://$ip:$port/$registrationName"
        }
    }

    fun stopHosting() {
        _isHosting.value = false
        _qrCodeText.value = null
        repository.unregisterService()
    }

    fun updateConnectionStatus(connected: Boolean) {
        _isConnected.value = connected
    }

    fun connectToDevice(device: Device) {
        // Handled via onConnectToDevice callback and AudioService status updates
    }

    fun handleScannedCode(code: String) {
        // Expected format: motoride://ip:port/userId|deviceName
        if (code.startsWith("motoride://")) {
            val parts = code.removePrefix("motoride://").split("/")
            if (parts.isNotEmpty()) {
                val addressParts = parts[0].split(":")
                if (addressParts.size == 2) {
                    val ip = addressParts[0]
                    val port = addressParts[1].toIntOrNull() ?: 8080
                    val rawName = if (parts.size > 1) parts[1] else "Unknown"
                    
                    val nameParts = rawName.split("|")
                    val userId = nameParts.getOrNull(0) ?: rawName
                    val displayName = nameParts.getOrNull(1) ?: rawName
                    
                    connectToDevice(Device(id = userId, name = displayName, deviceName = displayName, ip = ip, port = port))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopDiscovery()
    }
}
