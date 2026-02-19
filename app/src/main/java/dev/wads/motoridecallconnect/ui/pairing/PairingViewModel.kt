package dev.wads.motoridecallconnect.ui.pairing

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.data.model.ConnectionTransportMode
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.WifiDirectState
import dev.wads.motoridecallconnect.data.repository.DeviceDiscoveryRepository
import dev.wads.motoridecallconnect.data.repository.WifiDirectRepository
import dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus
import dev.wads.motoridecallconnect.utils.NetworkUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PairingViewModel(
    private val localRepository: DeviceDiscoveryRepository,
    private val wifiDirectRepository: WifiDirectRepository
) : ViewModel() {

    private val _selectedTransport = MutableStateFlow(ConnectionTransportMode.LOCAL_NETWORK)
    val selectedTransport: StateFlow<ConnectionTransportMode> = _selectedTransport.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting.asStateFlow()

    private val _qrCodeText = MutableStateFlow<String?>(null)
    val qrCodeText: StateFlow<String?> = _qrCodeText.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedPeer = MutableStateFlow<Device?>(null)
    val connectedPeer: StateFlow<Device?> = _connectedPeer.asStateFlow()

    private val _networkSnapshot = MutableStateFlow(NetworkUtils.getNetworkSnapshot())
    val networkSnapshot: StateFlow<NetworkUtils.NetworkSnapshot> = _networkSnapshot.asStateFlow()

    private val _wifiDirectState = MutableStateFlow(WifiDirectState(supported = true))
    val wifiDirectState: StateFlow<WifiDirectState> = _wifiDirectState.asStateFlow()

    private val _connectionErrorMessage = MutableStateFlow<String?>(null)
    val connectionErrorMessage: StateFlow<String?> = _connectionErrorMessage.asStateFlow()

    private val _pendingDeviceToConnect = MutableSharedFlow<Device>(extraBufferCapacity = 1)
    val pendingDeviceToConnect: SharedFlow<Device> = _pendingDeviceToConnect.asSharedFlow()

    private var lastHostDeviceName: String = Build.MODEL
    private var lastHostPort: Int = DEFAULT_SIGNALING_PORT

    init {
        refreshNetworkSnapshot()
        viewModelScope.launch {
            combine(
                localRepository.discoveredDevices,
                wifiDirectRepository.discoveredDevices,
                _selectedTransport
            ) { localDevices, wifiDirectDevices, selectedMode ->
                when (selectedMode) {
                    ConnectionTransportMode.LOCAL_NETWORK -> localDevices
                    ConnectionTransportMode.WIFI_DIRECT -> wifiDirectDevices
                    ConnectionTransportMode.INTERNET -> emptyList()
                }
            }.collect { devices ->
                _discoveredDevices.value = devices
            }
        }
        viewModelScope.launch {
            wifiDirectRepository.state.collect { state ->
                _wifiDirectState.value = state
            }
        }
        wifiDirectRepository.refreshState()
    }

    fun setConnectionTransport(mode: ConnectionTransportMode) {
        if (_selectedTransport.value == mode) {
            return
        }
        stopDiscovery()
        stopHosting()
        _selectedTransport.value = mode
        _connectionErrorMessage.value = null
        when (mode) {
            ConnectionTransportMode.LOCAL_NETWORK -> refreshNetworkSnapshot()
            ConnectionTransportMode.WIFI_DIRECT -> wifiDirectRepository.refreshState()
            ConnectionTransportMode.INTERNET -> _discoveredDevices.value = emptyList()
        }
        startDiscovery()
    }

    fun isAutoConnectSupported(): Boolean {
        return _selectedTransport.value == ConnectionTransportMode.LOCAL_NETWORK
    }

    fun startDiscovery() {
        if (_isHosting.value) {
            return
        }
        _connectionErrorMessage.value = null
        when (_selectedTransport.value) {
            ConnectionTransportMode.LOCAL_NETWORK -> {
                refreshNetworkSnapshot()
                localRepository.startDiscovery()
            }
            ConnectionTransportMode.WIFI_DIRECT -> {
                wifiDirectRepository.startDiscovery()
            }
            ConnectionTransportMode.INTERNET -> {
                _discoveredDevices.value = emptyList()
            }
        }
    }

    fun stopDiscovery() {
        localRepository.stopDiscovery()
        wifiDirectRepository.stopDiscovery()
    }

    fun startHosting(deviceName: String, port: Int = DEFAULT_SIGNALING_PORT) {
        lastHostDeviceName = deviceName
        lastHostPort = port
        _connectionErrorMessage.value = null
        stopDiscovery()

        when (_selectedTransport.value) {
            ConnectionTransportMode.LOCAL_NETWORK -> {
                refreshNetworkSnapshot()
                _isHosting.value = true
                val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
                val registrationName = "$userId|$deviceName"
                localRepository.registerService(port, registrationName)
                _qrCodeText.value = buildPairingPayload(
                    userId = userId,
                    deviceName = deviceName,
                    registrationName = registrationName,
                    port = port,
                    networkSnapshot = _networkSnapshot.value
                )
            }

            ConnectionTransportMode.WIFI_DIRECT -> {
                _isHosting.value = true
                _qrCodeText.value = null
                wifiDirectRepository.startHosting()
            }

            ConnectionTransportMode.INTERNET -> {
                _isHosting.value = false
                _qrCodeText.value = null
                _connectionErrorMessage.value =
                    "Modo Internet/WebRTC ainda não está habilitado nesta versão."
            }
        }
    }

    fun stopHosting() {
        if (!_isHosting.value && _qrCodeText.value == null) {
            return
        }
        _isHosting.value = false
        _qrCodeText.value = null
        localRepository.unregisterService()
        wifiDirectRepository.cancelPendingConnection()
        wifiDirectRepository.stopHosting()
    }

    fun activateHostMode(deviceName: String, port: Int = DEFAULT_SIGNALING_PORT) {
        startHosting(deviceName, port)
    }

    fun activateClientMode() {
        stopHosting()
        startDiscovery()
    }

    fun refreshNetworkSnapshot() {
        _networkSnapshot.value = NetworkUtils.getNetworkSnapshot()
    }

    fun updateConnectionStatus(status: ConnectionStatus, peer: Device?) {
        _connectionStatus.value = status
        _isConnected.value = status == ConnectionStatus.CONNECTED
        _connectedPeer.value = peer
    }

    fun connectToDevice(device: Device) {
        _connectionErrorMessage.value = null
        when (_selectedTransport.value) {
            ConnectionTransportMode.LOCAL_NETWORK -> {
                _pendingDeviceToConnect.tryEmit(
                    device.copy(
                        connectionTransport = ConnectionTransportMode.LOCAL_NETWORK
                    )
                )
            }

            ConnectionTransportMode.WIFI_DIRECT -> {
                viewModelScope.launch {
                    val result = wifiDirectRepository.connectToPeer(
                        target = device,
                        port = device.port ?: DEFAULT_SIGNALING_PORT
                    )
                    result
                        .onSuccess { resolvedDevice ->
                            _pendingDeviceToConnect.emit(
                                resolvedDevice.copy(
                                    connectionTransport = ConnectionTransportMode.WIFI_DIRECT
                                )
                            )
                        }
                        .onFailure { error ->
                            _connectionErrorMessage.value =
                                error.message ?: "Falha ao conectar via Wi-Fi Direct."
                        }
                }
            }

            ConnectionTransportMode.INTERNET -> {
                _connectionErrorMessage.value =
                    "Modo Internet/WebRTC ainda não está habilitado nesta versão."
            }
        }
    }

    fun clearConnectionErrorMessage() {
        _connectionErrorMessage.value = null
    }

    fun cancelPendingConnection() {
        wifiDirectRepository.cancelPendingConnection()
    }

    fun restartCurrentHostingIfNeeded() {
        if (!_isHosting.value) return
        startHosting(lastHostDeviceName, lastHostPort)
    }

    fun handleScannedCode(code: String): Device? {
        parseJsonPairingCode(code)?.let { return it }

        // Legacy format: motoride://ip:port/userId|deviceName
        if (!code.startsWith("motoride://")) {
            return null
        }
        val parts = code.removePrefix("motoride://").split("/")
        if (parts.isEmpty()) {
            return null
        }
        val addressParts = parts[0].split(":")
        if (addressParts.size != 2) {
            return null
        }
        val ip = addressParts[0].trim()
        if (ip.isEmpty()) {
            return null
        }
        val port = addressParts[1].toIntOrNull() ?: DEFAULT_SIGNALING_PORT
        val rawName = if (parts.size > 1) parts[1] else "Unknown"
        val nameParts = rawName.split("|")
        val userId = nameParts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: rawName
        val displayName = nameParts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: rawName

        return Device(
            id = userId,
            name = displayName,
            deviceName = displayName,
            ip = ip,
            port = port,
            candidateIps = listOf(ip),
            connectionTransport = ConnectionTransportMode.LOCAL_NETWORK
        )
    }

    private fun parseJsonPairingCode(code: String): Device? {
        val payloadText = code.removePrefix(PAIRING_JSON_PREFIX).trim()
        if (!payloadText.startsWith("{")) {
            return null
        }
        return runCatching {
            val root = JSONObject(payloadText)
            if (root.optString("type") != QR_PAYLOAD_TYPE) {
                return@runCatching null
            }
            val registrationName = root.optString("registrationName")
            val userId = root.optString("userId").ifBlank {
                registrationName.split("|").getOrNull(0).orEmpty()
            }
            val displayName = root.optString("deviceName").ifBlank {
                registrationName.split("|").getOrNull(1).orEmpty()
            }.ifBlank { "Unknown" }
            val port = root.optInt("port", DEFAULT_SIGNALING_PORT)
                .takeIf { it in 1..65535 } ?: DEFAULT_SIGNALING_PORT

            val candidateIps = mutableListOf<String>()
            root.optString("primaryIp").takeIf { it.isNotBlank() }?.let(candidateIps::add)
            root.optJSONArray("ipCandidates")
                ?.toTrimmedStringList()
                ?.forEach(candidateIps::add)

            if (candidateIps.isEmpty()) {
                val interfaceCandidates = mutableListOf<String>()
                root.optJSONArray("interfaces")?.let { interfaces ->
                    for (index in 0 until interfaces.length()) {
                        val interfaceJson = interfaces.optJSONObject(index) ?: continue
                        interfaceJson.optJSONArray("ipv4")
                            ?.toTrimmedStringList()
                            ?.forEach(interfaceCandidates::add)
                    }
                }
                candidateIps += interfaceCandidates
            }

            val normalizedIps = candidateIps
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val selectedIp = normalizedIps.firstOrNull() ?: return@runCatching null

            Device(
                id = userId.ifBlank { displayName },
                name = displayName,
                deviceName = displayName,
                ip = selectedIp,
                port = port,
                candidateIps = normalizedIps,
                connectionTransport = ConnectionTransportMode.LOCAL_NETWORK
            )
        }.getOrNull()
    }

    private fun buildPairingPayload(
        userId: String,
        deviceName: String,
        registrationName: String,
        port: Int,
        networkSnapshot: NetworkUtils.NetworkSnapshot
    ): String {
        val payload = JSONObject().apply {
            put("type", QR_PAYLOAD_TYPE)
            put("version", QR_PAYLOAD_VERSION)
            put("userId", userId)
            put("deviceName", deviceName)
            put("registrationName", registrationName)
            put("port", port)
            put("generatedAtMs", System.currentTimeMillis())
            put("primaryIp", networkSnapshot.primaryIpv4 ?: JSONObject.NULL)
            put("ipCandidates", JSONArray().apply {
                networkSnapshot.ipv4Candidates
                    .take(MAX_QR_IP_CANDIDATES)
                    .forEach(::put)
            })
            put("interfaces", JSONArray().apply {
                networkSnapshot.interfaceAddresses
                    .groupBy { it.interfaceName to it.displayName }
                    .toList()
                    .sortedByDescending { (_, addresses) -> addresses.maxOfOrNull { it.score } ?: 0 }
                    .take(MAX_QR_INTERFACES)
                    .forEach { (identity, addresses) ->
                        val interfaceJson = JSONObject().apply {
                            put("name", identity.first)
                            put("displayName", identity.second)
                            put("score", addresses.maxOfOrNull { it.score } ?: 0)
                            put("addresses", JSONArray().apply {
                                addresses
                                    .map { it.address }
                                    .distinct()
                                    .take(MAX_QR_ADDRESSES_PER_INTERFACE)
                                    .forEach(::put)
                            })
                            put("ipv4", JSONArray().apply {
                                addresses
                                    .filter { it.isIpv4 }
                                    .map { it.address }
                                    .distinct()
                                    .take(MAX_QR_ADDRESSES_PER_INTERFACE)
                                    .forEach(::put)
                            })
                        }
                        put(interfaceJson)
                    }
            })
        }
        return "$PAIRING_JSON_PREFIX${payload.toString()}"
    }

    private fun JSONArray.toTrimmedStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopHosting()
        stopDiscovery()
        wifiDirectRepository.tearDown()
    }

    companion object {
        private const val DEFAULT_SIGNALING_PORT = 8080
        private const val QR_PAYLOAD_TYPE = "motoride_pairing"
        private const val QR_PAYLOAD_VERSION = 2
        private const val PAIRING_JSON_PREFIX = "motoride-json://"
        private const val MAX_QR_INTERFACES = 12
        private const val MAX_QR_ADDRESSES_PER_INTERFACE = 8
        private const val MAX_QR_IP_CANDIDATES = 16
    }
}
