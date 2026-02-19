package dev.wads.motoridecallconnect.ui.pairing

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import dev.wads.motoridecallconnect.data.model.ConnectionTransportMode
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.InternetPeerDetails
import dev.wads.motoridecallconnect.data.model.WifiDirectState
import dev.wads.motoridecallconnect.data.repository.DeviceDiscoveryRepository
import dev.wads.motoridecallconnect.data.repository.InternetConnectivityRepository
import dev.wads.motoridecallconnect.data.repository.SocialRepository
import dev.wads.motoridecallconnect.data.repository.WifiDirectRepository
import dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus
import dev.wads.motoridecallconnect.utils.NetworkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PairingViewModel(
    private val localRepository: DeviceDiscoveryRepository,
    private val wifiDirectRepository: WifiDirectRepository,
    private val internetRepository: InternetConnectivityRepository,
    private val socialRepository: SocialRepository
) : ViewModel() {

    private val _selectedTransport = MutableStateFlow(ConnectionTransportMode.LOCAL_NETWORK)
    val selectedTransport: StateFlow<ConnectionTransportMode> = _selectedTransport.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()
    private val _wifiDirectDiscoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val wifiDirectDiscoveredDevices: StateFlow<List<Device>> = _wifiDirectDiscoveredDevices.asStateFlow()
    private val _internetPeers = MutableStateFlow<List<InternetPeerDetails>>(emptyList())
    val internetPeers: StateFlow<List<InternetPeerDetails>> = _internetPeers.asStateFlow()
    private val _friendIds = MutableStateFlow<Set<String>>(emptySet())
    val friendIds: StateFlow<Set<String>> = _friendIds.asStateFlow()
    private val _currentUserId = MutableStateFlow(FirebaseAuth.getInstance().currentUser?.uid)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

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
    private val _presenceIntervalSeconds = MutableStateFlow(DEFAULT_PRESENCE_PUBLISH_INTERVAL_SECONDS)
    val presenceIntervalSeconds: StateFlow<Int> = _presenceIntervalSeconds.asStateFlow()

    private val _pendingDeviceToConnect = MutableSharedFlow<Device>(extraBufferCapacity = 1)
    val pendingDeviceToConnect: SharedFlow<Device> = _pendingDeviceToConnect.asSharedFlow()
    private val _friendRequestFeedback = MutableSharedFlow<FriendRequestFeedback>(extraBufferCapacity = 1)
    val friendRequestFeedback: SharedFlow<FriendRequestFeedback> = _friendRequestFeedback.asSharedFlow()

    private var lastHostDeviceName: String = Build.MODEL
    private var lastHostPort: Int = DEFAULT_SIGNALING_PORT
    private var internetDiscoveryJob: Job? = null
    private var presencePublisherJob: Job? = null

    init {
        refreshNetworkSnapshot()
        viewModelScope.launch {
            combine(
                localRepository.discoveredDevices,
                _selectedTransport
            ) { localDevices, selectedMode ->
                when (selectedMode) {
                    ConnectionTransportMode.LOCAL_NETWORK -> localDevices
                    ConnectionTransportMode.INTERNET -> emptyList()
                    ConnectionTransportMode.WIFI_DIRECT -> localDevices
                }
            }.collect { devices ->
                _discoveredDevices.value = devices
            }
        }
        viewModelScope.launch {
            wifiDirectRepository.discoveredDevices.collect { devices ->
                _wifiDirectDiscoveredDevices.value = devices
            }
        }
        viewModelScope.launch {
            wifiDirectRepository.state.collect { state ->
                _wifiDirectState.value = state
            }
        }
        viewModelScope.launch {
            socialRepository.getFriends()
                .catch { emit(emptyList()) }
                .collect { friends ->
                    _friendIds.value = friends
                        .mapNotNull { friend -> friend.uid.takeIf { it.isNotBlank() } }
                        .toSet()
                    _currentUserId.value = FirebaseAuth.getInstance().currentUser?.uid
                }
        }
        wifiDirectRepository.refreshState()
        startPresencePublisher()
    }

    fun setConnectionTransport(mode: ConnectionTransportMode) {
        val normalizedMode = when (mode) {
            ConnectionTransportMode.WIFI_DIRECT -> ConnectionTransportMode.LOCAL_NETWORK
            else -> mode
        }
        if (_selectedTransport.value == normalizedMode) {
            return
        }
        stopDiscovery()
        stopHosting()
        stopWifiDirectDiscovery()
        stopWifiDirectHosting()
        stopInternetDiscovery()
        _selectedTransport.value = normalizedMode
        _connectionErrorMessage.value = null
        when (normalizedMode) {
            ConnectionTransportMode.LOCAL_NETWORK -> refreshNetworkSnapshot()
            ConnectionTransportMode.INTERNET -> {
                _discoveredDevices.value = emptyList()
                refreshInternetPeers()
            }
            ConnectionTransportMode.WIFI_DIRECT -> Unit
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
            ConnectionTransportMode.INTERNET -> {
                _discoveredDevices.value = emptyList()
                startInternetDiscovery()
            }
            ConnectionTransportMode.WIFI_DIRECT -> localRepository.startDiscovery()
        }
    }

    fun stopDiscovery() {
        localRepository.stopDiscovery()
        stopInternetDiscovery()
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

            ConnectionTransportMode.INTERNET -> {
                _isHosting.value = false
                _qrCodeText.value = null
                startInternetDiscovery()
            }
            ConnectionTransportMode.WIFI_DIRECT -> Unit
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
        Log.i(
            TAG,
            "connectToDevice called: id=${device.id}, name=${device.name}, transport=${device.connectionTransport}, ip=${device.ip}, port=${device.port}"
        )
        _connectionErrorMessage.value = null
        if (device.connectionTransport == ConnectionTransportMode.WIFI_DIRECT) {
            viewModelScope.launch {
                wifiDirectRepository.disconnectInfrastructureWifi()
                val result = wifiDirectRepository.connectToPeer(
                    target = device,
                    port = device.port ?: DEFAULT_SIGNALING_PORT
                )
                result
                    .onSuccess { resolvedDevice ->
                        Log.i(
                            TAG,
                            "Wi-Fi Direct connect resolved. endpoint=${resolvedDevice.ip}:${resolvedDevice.port} candidates=${resolvedDevice.candidateIps}"
                        )
                        _pendingDeviceToConnect.emit(
                            resolvedDevice.copy(
                                connectionTransport = ConnectionTransportMode.WIFI_DIRECT
                            )
                        )
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Wi-Fi Direct connect failed", error)
                        _connectionErrorMessage.value =
                            error.message ?: "Falha ao conectar via Wi-Fi Direct."
                    }
            }
            return
        }

        if (device.connectionTransport == ConnectionTransportMode.INTERNET || _selectedTransport.value == ConnectionTransportMode.INTERNET) {
            val details = _internetPeers.value.firstOrNull { it.uid == device.id }
            if (details != null && !details.canConnect) {
                _connectionErrorMessage.value =
                    details.warningMessage ?: "Este amigo não está pronto para conexão via internet."
                return
            }
            _pendingDeviceToConnect.tryEmit(
                device.copy(
                    ip = null,
                    port = null,
                    connectionTransport = ConnectionTransportMode.INTERNET
                )
            )
            return
        }

        when (_selectedTransport.value) {
            ConnectionTransportMode.LOCAL_NETWORK,
            ConnectionTransportMode.WIFI_DIRECT -> {
                _pendingDeviceToConnect.tryEmit(
                    device.copy(
                        connectionTransport = ConnectionTransportMode.LOCAL_NETWORK
                    )
                )
            }

            ConnectionTransportMode.INTERNET -> {
                _connectionErrorMessage.value = "Não foi possível iniciar a conexão via internet."
            }
        }
    }

    fun startWifiDirectDiscovery() {
        Log.i(TAG, "startWifiDirectDiscovery")
        wifiDirectRepository.disconnectInfrastructureWifi()
        wifiDirectRepository.startDiscovery()
    }

    fun stopWifiDirectDiscovery() {
        wifiDirectRepository.stopDiscovery()
    }

    fun startWifiDirectHosting() {
        Log.i(TAG, "startWifiDirectHosting")
        wifiDirectRepository.disconnectInfrastructureWifi()
        wifiDirectRepository.startHosting()
    }

    fun stopWifiDirectHosting() {
        wifiDirectRepository.stopHosting()
    }

    fun disconnectRouterWifiForWifiDirect() {
        val success = wifiDirectRepository.disconnectInfrastructureWifi()
        if (!success) {
            _connectionErrorMessage.value =
                "Não foi possível desconectar automaticamente do Wi-Fi do roteador. Faça isso manualmente."
        }
        wifiDirectRepository.refreshState()
    }

    fun clearConnectionErrorMessage() {
        _connectionErrorMessage.value = null
    }

    fun updatePresencePublishIntervalSeconds(seconds: Int) {
        val normalized = seconds.coerceIn(MIN_PRESENCE_INTERVAL_SECONDS, MAX_PRESENCE_INTERVAL_SECONDS)
        if (_presenceIntervalSeconds.value != normalized) {
            _presenceIntervalSeconds.value = normalized
            Log.i(TAG, "Presence publish interval updated to ${normalized}s")
        }
    }

    fun publishPresenceNow() {
        viewModelScope.launch {
            publishPresenceSnapshot("manual_trigger")
        }
    }

    fun refreshInternetPeers() {
        viewModelScope.launch {
            val peers = runCatching { internetRepository.refreshFriendInternetPeersOnce() }
                .getOrElse { error ->
                    _connectionErrorMessage.value = error.message ?: "Falha ao atualizar amigos na internet."
                    emptyList()
                }
            _internetPeers.value = peers
        }
    }

    fun findInternetPeer(peerId: String): InternetPeerDetails? {
        return _internetPeers.value.firstOrNull { it.uid == peerId }
    }

    fun sendFriendRequestToNearbyDevice(device: Device) {
        val targetId = device.id.trim()
        val localUid = FirebaseAuth.getInstance().currentUser?.uid
        _currentUserId.value = localUid

        if (targetId.isBlank()) {
            _friendRequestFeedback.tryEmit(
                FriendRequestFeedback(
                    message = "Não foi possível enviar solicitação: ID inválido.",
                    isError = true
                )
            )
            return
        }
        if (!canDeviceReceiveFriendRequest(device)) {
            _friendRequestFeedback.tryEmit(
                FriendRequestFeedback(
                    message = "Este dispositivo próximo não expõe um ID de usuário válido para amizade.",
                    isError = true
                )
            )
            return
        }
        if (localUid.isNullOrBlank()) {
            _friendRequestFeedback.tryEmit(
                FriendRequestFeedback(
                    message = "Faça login para enviar solicitações de amizade.",
                    isError = true
                )
            )
            return
        }
        if (targetId == localUid) {
            _friendRequestFeedback.tryEmit(
                FriendRequestFeedback(
                    message = "Você não pode adicionar seu próprio usuário.",
                    isError = true
                )
            )
            return
        }
        if (_friendIds.value.contains(targetId)) {
            _friendRequestFeedback.tryEmit(
                FriendRequestFeedback(
                    message = "Este usuário já está na sua lista de amigos.",
                    isError = false
                )
            )
            return
        }

        viewModelScope.launch {
            runCatching {
                socialRepository.sendFriendRequest(targetId)
            }.onSuccess {
                _friendRequestFeedback.emit(
                    FriendRequestFeedback(
                        message = "Solicitação de amizade enviada para ${device.name}.",
                        isError = false
                    )
                )
            }.onFailure { error ->
                _friendRequestFeedback.emit(
                    FriendRequestFeedback(
                        message = error.message ?: "Falha ao enviar solicitação de amizade.",
                        isError = true
                    )
                )
            }
        }
    }

    fun canDeviceReceiveFriendRequest(device: Device): Boolean {
        val id = device.id.trim()
        if (id.isBlank()) return false
        if (id.contains(":")) return false // Common on Wi-Fi Direct MAC-based IDs
        return id.length >= MIN_FRIEND_ID_LENGTH
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
        stopWifiDirectDiscovery()
        stopWifiDirectHosting()
        stopInternetDiscovery()
        presencePublisherJob?.cancel()
        wifiDirectRepository.tearDown()
    }

    private fun startInternetDiscovery() {
        if (internetDiscoveryJob != null) {
            return
        }
        internetDiscoveryJob = viewModelScope.launch {
            internetRepository.observeFriendInternetPeers()
                .collect { peers ->
                    _internetPeers.value = peers
                }
        }
    }

    private fun stopInternetDiscovery() {
        internetDiscoveryJob?.cancel()
        internetDiscoveryJob = null
    }

    private fun startPresencePublisher() {
        presencePublisherJob?.cancel()
        presencePublisherJob = viewModelScope.launch {
            _presenceIntervalSeconds.collectLatest { intervalSeconds ->
                publishPresenceSnapshot("publisher_start_${intervalSeconds}s")
                while (isActive) {
                    delay(intervalSeconds * 1_000L)
                    publishPresenceSnapshot("periodic_${intervalSeconds}s")
                }
            }
        }
    }

    private suspend fun publishPresenceSnapshot(reason: String) {
        runCatching {
            val snapshot = NetworkUtils.getNetworkSnapshot()
            _networkSnapshot.value = snapshot
            internetRepository.publishLocalConnectionInfo(
                snapshot = snapshot,
                wifiDirectState = _wifiDirectState.value,
                isLocalHosting = _isHosting.value
            )
        }.onSuccess {
            Log.d(
                TAG,
                "Presence published. reason=$reason, primaryIp=${_networkSnapshot.value.primaryIpv4}, hosting=${_isHosting.value}"
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to publish presence. reason=$reason", error)
        }
    }

    companion object {
        private const val TAG = "PairingVM"
        private const val DEFAULT_SIGNALING_PORT = 8080
        private const val QR_PAYLOAD_TYPE = "motoride_pairing"
        private const val QR_PAYLOAD_VERSION = 2
        private const val PAIRING_JSON_PREFIX = "motoride-json://"
        private const val MAX_QR_INTERFACES = 12
        private const val MAX_QR_ADDRESSES_PER_INTERFACE = 8
        private const val MAX_QR_IP_CANDIDATES = 16
        private const val DEFAULT_PRESENCE_PUBLISH_INTERVAL_SECONDS = 30
        private const val MIN_PRESENCE_INTERVAL_SECONDS = 10
        private const val MAX_PRESENCE_INTERVAL_SECONDS = 300
        private const val MIN_FRIEND_ID_LENGTH = 10
    }
}

data class FriendRequestFeedback(
    val message: String,
    val isError: Boolean
)
