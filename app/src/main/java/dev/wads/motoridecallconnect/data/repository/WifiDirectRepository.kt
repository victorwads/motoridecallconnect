package dev.wads.motoridecallconnect.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import dev.wads.motoridecallconnect.data.model.ConnectionTransportMode
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.WifiDirectState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WifiDirectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val wifiP2pManager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = wifiP2pManager?.initialize(
        appContext,
        Looper.getMainLooper()
    ) {
        Log.w(TAG, "Wi-Fi Direct channel disconnected.")
        _state.update {
            it.copy(
                connected = false,
                groupFormed = false,
                groupOwner = false,
                groupOwnerIp = null,
                discovering = false,
                connecting = false,
                failureMessage = "Canal Wi-Fi Direct desconectado."
            )
        }
    }

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _state = MutableStateFlow(
        WifiDirectState(
            supported = wifiP2pManager != null,
            enabled = false,
            infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()
        )
    )
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private var receiverRegistered = false
    private var pendingConnect: CompletableDeferred<Result<Device>>? = null
    private var pendingConnectTemplate: Device? = null
    private var cachedPeers: List<WifiP2pDevice> = emptyList()
    private var connectAttemptCounter: Long = 0L
    private var activeConnectAttemptId: Long? = null

    private val peersChangedListener = WifiP2pManager.PeerListListener { peerList ->
        cachedPeers = peerList.deviceList.toList()
        Log.d(TAG, "Peer list updated: count=${cachedPeers.size}")
        _discoveredDevices.value = cachedPeers
            .map(Device::fromWifiP2pDevice)
            .distinctBy { it.wifiDirectDeviceAddress ?: it.id }
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        handleConnectionInfo(info)
    }

    private val groupInfoListener = WifiP2pManager.GroupInfoListener { group ->
        handleGroupInfo(group)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.update {
                        it.copy(
                            enabled = enabled,
                            infrastructureWifiConnected = isInfrastructureWifiConnectedInternal(),
                            failureMessage = if (enabled) null else "Wi-Fi Direct desativado no sistema."
                        )
                    }
                    if (!enabled) {
                        _discoveredDevices.value = emptyList()
                        failPendingConnect(IllegalStateException("Wi-Fi Direct desativado."))
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeersInternal()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.readNetworkInfo()
                    val directInfo = intent.readP2pInfo()
                    val isConnected = networkInfo?.isConnected == true
                    Log.i(
                        TAG,
                        "Broadcast CONNECTION_CHANGED connected=$isConnected " +
                            "networkInfo=$networkInfo groupFormed=${directInfo?.groupFormed} " +
                            "isGroupOwner=${directInfo?.isGroupOwner} ownerIp=${directInfo?.groupOwnerAddress?.hostAddress}"
                    )
                    if (!isConnected) {
                        _state.update {
                            it.copy(
                                connected = false,
                                groupFormed = false,
                                groupOwner = false,
                                groupOwnerIp = null,
                                infrastructureWifiConnected = isInfrastructureWifiConnectedInternal(),
                                connecting = false
                            )
                        }
                        activeConnectAttemptId = null
                    }
                    if (directInfo != null) {
                        handleConnectionInfo(directInfo)
                    }
                    requestConnectionInfoInternal()
                    requestGroupInfoInternal()
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val thisDevice = intent.readThisP2pDevice()
                    _state.update { it.copy(localDeviceName = thisDevice?.deviceName) }
                }
            }
        }
    }

    fun startDiscovery() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            _state.update { it.copy(failureMessage = "Wi-Fi Direct não suportado neste dispositivo.") }
            return
        }
        ensureReceiverRegistered()
        _state.update { it.copy(infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()) }
        _state.update { it.copy(discovering = true, failureMessage = null) }
        runCatching {
            manager.discoverPeers(currentChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    requestPeersInternal()
                    Log.i(TAG, "Wi-Fi Direct discovery started.")
                }

                override fun onFailure(reason: Int) {
                    val reasonText = reasonToMessage(reason)
                    _state.update {
                        it.copy(
                            discovering = false,
                            failureMessage = "Falha ao buscar dispositivos Wi-Fi Direct: $reasonText"
                        )
                    }
                    Log.w(TAG, "discoverPeers failed: $reasonText ($reason)")
                }
            })
        }.onFailure { error ->
            _state.update {
                it.copy(
                    discovering = false,
                    failureMessage = "Permissão/estado inválido para descoberta Wi-Fi Direct."
                )
            }
            Log.w(TAG, "discoverPeers threw exception", error)
        }
    }

    fun stopDiscovery() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            return
        }
        runCatching {
            manager.stopPeerDiscovery(currentChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _state.update { it.copy(discovering = false) }
                }

                override fun onFailure(reason: Int) {
                    _state.update { it.copy(discovering = false) }
                    Log.w(TAG, "stopPeerDiscovery failed: ${reasonToMessage(reason)} ($reason)")
                }
            })
        }.onFailure { error ->
            _state.update { it.copy(discovering = false) }
            Log.w(TAG, "stopPeerDiscovery threw exception", error)
        }
    }

    fun startHosting() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            _state.update { it.copy(failureMessage = "Wi-Fi Direct não suportado neste dispositivo.") }
            return
        }
        ensureReceiverRegistered()
        _state.update { it.copy(infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()) }
        _state.update { it.copy(connecting = true, failureMessage = null) }
        runCatching {
            manager.createGroup(currentChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct group creation requested.")
                    requestConnectionInfoInternal()
                    requestGroupInfoInternal()
                }

                override fun onFailure(reason: Int) {
                    _state.update {
                        it.copy(
                            connecting = false,
                            failureMessage = "Falha ao criar grupo Wi-Fi Direct: ${reasonToMessage(reason)}"
                        )
                    }
                    Log.w(TAG, "createGroup failed: ${reasonToMessage(reason)} ($reason)")
                }
            })
        }.onFailure { error ->
            _state.update {
                it.copy(
                    connecting = false,
                    failureMessage = "Permissão/estado inválido para criar grupo Wi-Fi Direct."
                )
            }
            Log.w(TAG, "createGroup threw exception", error)
        }
    }

    fun stopHosting() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            return
        }
        runCatching {
            manager.removeGroup(currentChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _state.update {
                        it.copy(
                            connecting = false,
                            connected = false,
                            groupFormed = false,
                            groupOwner = false,
                            groupOwnerIp = null,
                            infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()
                        )
                    }
                    Log.i(TAG, "Wi-Fi Direct group removed.")
                }

                override fun onFailure(reason: Int) {
                    _state.update {
                        it.copy(
                            connecting = false,
                            failureMessage = "Falha ao encerrar grupo Wi-Fi Direct: ${reasonToMessage(reason)}"
                        )
                    }
                    Log.w(TAG, "removeGroup failed: ${reasonToMessage(reason)} ($reason)")
                }
            })
        }.onFailure { error ->
            _state.update {
                it.copy(
                    connecting = false,
                    connected = false,
                    groupFormed = false,
                    groupOwner = false,
                    groupOwnerIp = null
                )
            }
            Log.w(TAG, "removeGroup threw exception", error)
        }
    }

    suspend fun connectToPeer(
        target: Device,
        port: Int = DEFAULT_SIGNALING_PORT
    ): Result<Device> {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            return Result.failure(IllegalStateException("Wi-Fi Direct não suportado neste dispositivo."))
        }
        val targetAddress = target.wifiDirectDeviceAddress?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("Dispositivo Wi-Fi Direct inválido."))
        val connectAttemptId = ++connectAttemptCounter
        activeConnectAttemptId = connectAttemptId

        return withContext(Dispatchers.Main.immediate) {
            Log.i(TAG, "[attempt=$connectAttemptId] Starting Wi-Fi Direct connect. targetAddress=$targetAddress target=${target.name}/${target.id}")
            ensureReceiverRegistered()
            _state.update { it.copy(infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()) }
            _state.update { it.copy(connecting = true, failureMessage = null) }
            pendingConnect?.complete(Result.failure(IllegalStateException("Nova conexão iniciada.")))
            val deferred = CompletableDeferred<Result<Device>>()
            pendingConnect = deferred
            pendingConnectTemplate = target.copy(
                port = port,
                connectionTransport = ConnectionTransportMode.WIFI_DIRECT,
                wifiDirectDeviceAddress = targetAddress
            )

            val config = WifiP2pConfig().apply {
                deviceAddress = targetAddress
                wps.setup = WpsInfo.PBC
            }

            runCatching {
                manager.connect(currentChannel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(
                            TAG,
                            "[attempt=$connectAttemptId] connect() accepted. Waiting OS confirmation + group info."
                        )
                        requestConnectionInfoInternal()
                        requestGroupInfoInternal()
                    }

                    override fun onFailure(reason: Int) {
                        val reasonText = reasonToMessage(reason)
                        val error = IllegalStateException("Falha ao conectar no peer Wi-Fi Direct: $reasonText")
                        _state.update { it.copy(connecting = false, failureMessage = error.message) }
                        failPendingConnect(error)
                        activeConnectAttemptId = null
                        Log.w(TAG, "[attempt=$connectAttemptId] connect failed: $reasonText ($reason)")
                    }
                })
            }.onFailure { error ->
                val securityError = IllegalStateException(
                    "Permissão/estado inválido para conectar via Wi-Fi Direct.",
                    error
                )
                _state.update { it.copy(connecting = false, failureMessage = securityError.message) }
                failPendingConnect(securityError)
                activeConnectAttemptId = null
                return@withContext Result.failure(securityError)
            }

            val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                while (isActive && !deferred.isCompleted) {
                    requestConnectionInfoInternal()
                    requestGroupInfoInternal()
                    delay(CONNECT_INFO_POLL_INTERVAL_MS)
                }
                deferred.await()
            } ?: run {
                val timeoutError = IllegalStateException("Tempo esgotado ao conectar via Wi-Fi Direct.")
                Log.w(TAG, "[attempt=$connectAttemptId] Connection timed out waiting group/address details.")
                failPendingConnect(timeoutError)
                _state.update { it.copy(connecting = false, failureMessage = timeoutError.message) }
                Result.failure(timeoutError)
            }
            activeConnectAttemptId = null
            result
        }
    }

    fun cancelPendingConnection() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager != null && currentChannel != null) {
            runCatching {
                manager.cancelConnect(currentChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "cancelConnect failed: ${reasonToMessage(reason)} ($reason)")
                    }
                })
            }.onFailure { error ->
                Log.w(TAG, "cancelConnect threw exception", error)
            }
        }
        failPendingConnect(IllegalStateException("Conexão cancelada pelo usuário."))
        _state.update { it.copy(connecting = false) }
        activeConnectAttemptId = null
    }

    fun refreshState() {
        ensureReceiverRegistered()
        _state.update { it.copy(infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()) }
        requestPeersInternal()
        requestConnectionInfoInternal()
        requestGroupInfoInternal()
    }

    fun disconnectInfrastructureWifi(): Boolean {
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        val result = runCatching {
            @Suppress("DEPRECATION")
            wifiManager.disconnect()
        }.getOrDefault(false)
        _state.update { it.copy(infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()) }
        if (!result) {
            Log.w(TAG, "Failed to disconnect infrastructure Wi-Fi automatically.")
        }
        return result
    }

    fun tearDown() {
        failPendingConnect(IllegalStateException("Sessão Wi-Fi Direct encerrada."))
        stopDiscovery()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (info == null) {
            return
        }
        val ownerIpRaw = info.groupOwnerAddress?.hostAddress
        val ownerIp = ownerIpRaw?.trim()
        Log.i(
            TAG,
            "ConnectionInfo update: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}, " +
                "ownerIp=${ownerIp ?: "null"}, pendingConnect=${pendingConnect != null}, attempt=${activeConnectAttemptId ?: -1}"
        )
        _state.update {
            it.copy(
                connecting = it.connecting && !info.groupFormed,
                connected = info.groupFormed,
                groupFormed = info.groupFormed,
                groupOwner = info.isGroupOwner,
                groupOwnerIp = ownerIp,
                infrastructureWifiConnected = isInfrastructureWifiConnectedInternal()
            )
        }

        if (!info.groupFormed) {
            return
        }

        val pending = pendingConnect ?: return
        if (pending.isCompleted) {
            return
        }
        val template = pendingConnectTemplate
        if (template == null) {
            val error = IllegalStateException("Grupo Wi-Fi Direct formado sem template de conexão.")
            failPendingConnect(error)
            _state.update { it.copy(connecting = false, failureMessage = error.message) }
            return
        }
        val resolvedIp = resolveOwnerIp(info, ownerIp)
        if (resolvedIp.isNullOrBlank()) {
            val error = if (info.isGroupOwner) {
                IllegalStateException(
                    "Seu aparelho virou Group Owner no Wi-Fi Direct. Refaça iniciando grupo no Host e conectando do Client."
                )
            } else {
                IllegalStateException("Grupo Wi-Fi Direct formado sem endereço do Group Owner.")
            }
            if (info.isGroupOwner) {
                Log.w(TAG, "Connection formed but local became Group Owner for a client-initiated connect. Failing attempt.")
                failPendingConnect(error)
                _state.update { it.copy(connecting = false, failureMessage = error.message) }
            } else {
                Log.w(TAG, "Connection formed without owner IP yet. Waiting for next info update.")
                requestConnectionInfoInternal()
                requestGroupInfoInternal()
            }
            return
        }

        pendingConnect = null
        pendingConnectTemplate = null
        pending.complete(
            Result.success(
                template.copy(
                    ip = resolvedIp,
                    port = template.port ?: DEFAULT_SIGNALING_PORT,
                    candidateIps = listOf(resolvedIp),
                    connectionTransport = ConnectionTransportMode.WIFI_DIRECT
                )
            )
        )
        Log.i(TAG, "Wi-Fi Direct connect resolved endpoint=$resolvedIp:${template.port ?: DEFAULT_SIGNALING_PORT}")
        _state.update { it.copy(connecting = false, connected = true, groupFormed = true, failureMessage = null) }
    }

    private fun handleGroupInfo(group: WifiP2pGroup?) {
        if (group == null) {
            return
        }
        Log.d(
            TAG,
            "Group info: networkName=${group.networkName}, isOwner=${group.isGroupOwner}, " +
                "ownerDevice=${group.owner?.deviceName}, ownerMac=${group.owner?.deviceAddress}, " +
                "clients=${group.clientList?.size ?: 0}"
        )
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        appContext.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun requestPeersInternal() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            return
        }
        runCatching {
            manager.requestPeers(currentChannel, peersChangedListener)
        }.onFailure { error ->
            Log.w(TAG, "requestPeers failed", error)
        }
    }

    private fun requestConnectionInfoInternal() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            return
        }
        runCatching {
            manager.requestConnectionInfo(currentChannel, connectionInfoListener)
        }.onFailure { error ->
            Log.w(TAG, "requestConnectionInfo failed", error)
        }
    }

    private fun requestGroupInfoInternal() {
        val manager = wifiP2pManager
        val currentChannel = channel
        if (manager == null || currentChannel == null) {
            return
        }
        runCatching {
            manager.requestGroupInfo(currentChannel, groupInfoListener)
        }.onFailure { error ->
            Log.w(TAG, "requestGroupInfo failed", error)
        }
    }

    private fun failPendingConnect(error: Throwable) {
        Log.w(TAG, "Failing pending Wi-Fi Direct connect: ${error.message}", error)
        val pending = pendingConnect ?: return
        if (!pending.isCompleted) {
            pending.complete(Result.failure(error))
        }
        pendingConnect = null
        pendingConnectTemplate = null
    }

    private fun resolveOwnerIp(info: WifiP2pInfo, ownerIp: String?): String? {
        if (!ownerIp.isNullOrBlank()) {
            return ownerIp
        }
        if (!info.groupFormed) {
            return null
        }
        if (info.isGroupOwner) {
            return null
        }
        // Android Wi-Fi P2P GO usually uses this default.
        return DEFAULT_GROUP_OWNER_FALLBACK_IP
    }

    private fun reasonToMessage(reason: Int): String {
        return when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "não suportado"
            WifiP2pManager.BUSY -> "sistema ocupado"
            WifiP2pManager.ERROR -> "erro interno"
            else -> "motivo desconhecido ($reason)"
        }
    }

    private fun isInfrastructureWifiConnectedInternal(): Boolean {
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            val connectionInfo = wifiManager.connectionInfo ?: return@runCatching false
            val hasKnownSsid = !connectionInfo.ssid.isNullOrBlank() && connectionInfo.ssid != "<unknown ssid>"
            connectionInfo.networkId != -1 && hasKnownSsid
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun Intent.readNetworkInfo(): NetworkInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.readThisP2pDevice(): WifiP2pDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
        } else {
            getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.readP2pInfo(): WifiP2pInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
        } else {
            getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
        }
    }

    companion object {
        private const val TAG = "WifiDirectRepo"
        private const val DEFAULT_SIGNALING_PORT = 8080
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val CONNECT_INFO_POLL_INTERVAL_MS = 1_200L
        private const val DEFAULT_GROUP_OWNER_FALLBACK_IP = "192.168.49.1"
    }
}
