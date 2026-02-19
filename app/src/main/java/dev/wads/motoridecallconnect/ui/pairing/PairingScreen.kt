package dev.wads.motoridecallconnect.ui.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.model.ConnectionTransportMode
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.WifiDirectState
import dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus
import dev.wads.motoridecallconnect.ui.components.BadgeStatus
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.StatusBadge
import dev.wads.motoridecallconnect.ui.components.StatusCard
import dev.wads.motoridecallconnect.ui.components.UserProfileView
import dev.wads.motoridecallconnect.utils.NetworkUtils
import dev.wads.motoridecallconnect.utils.QrCodeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onNavigateBack: () -> Unit,
    onConnectToDevice: (Device) -> Unit,
    onDisconnectClick: () -> Unit
) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val wifiDirectDiscoveredDevices by viewModel.wifiDirectDiscoveredDevices.collectAsState()
    val isHosting by viewModel.isHosting.collectAsState()
    val qrCodeText by viewModel.qrCodeText.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectedPeer by viewModel.connectedPeer.collectAsState()
    val networkSnapshot by viewModel.networkSnapshot.collectAsState()
    val selectedTransport by viewModel.selectedTransport.collectAsState()
    val wifiDirectState by viewModel.wifiDirectState.collectAsState()
    val connectionErrorMessage by viewModel.connectionErrorMessage.collectAsState()

    var selectedRoleTab by remember { mutableIntStateOf(if (isHosting) 1 else 0) } // 0: Client, 1: Host
    var viewState by remember { mutableStateOf<PairViewState>(PairViewState.List) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var pairState by remember { mutableStateOf<PairConnectionState>(PairConnectionState.Idle) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredWifiDirectPermissions = remember { requiredWifiDirectPermissions() }
    var wifiDirectChecks by remember {
        mutableStateOf(context.readWifiDirectSystemChecks(requiredWifiDirectPermissions))
    }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        wifiDirectChecks = context.readWifiDirectSystemChecks(requiredWifiDirectPermissions)
        if (wifiDirectChecks.ready && selectedTransport == ConnectionTransportMode.LOCAL_NETWORK) {
            viewModel.startWifiDirectDiscovery()
        }
    }

    DisposableEffect(lifecycleOwner, selectedTransport) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && selectedTransport == ConnectionTransportMode.LOCAL_NETWORK) {
                wifiDirectChecks = context.readWifiDirectSystemChecks(requiredWifiDirectPermissions)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.pendingDeviceToConnect.collectLatest { device ->
            onConnectToDevice(device)
        }
    }

    LaunchedEffect(connectionErrorMessage) {
        if (!connectionErrorMessage.isNullOrBlank()) {
            pairState = PairConnectionState.Error
        }
    }

    LaunchedEffect(isHosting) {
        selectedRoleTab = if (isHosting) 1 else 0
    }

    LaunchedEffect(selectedTransport) {
        if (selectedTransport == ConnectionTransportMode.INTERNET && selectedRoleTab != 0) {
            selectedRoleTab = 0
        }
    }

    if (connectionStatus == ConnectionStatus.CONNECTED) {
        PairConnectedView(
            connectedPeer = connectedPeer,
            onDisconnectClick = onDisconnectClick
        )
        return
    }

    when (viewState) {
        is PairViewState.List -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .padding(top = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.pairing_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                val transportModes = listOf(
                    ConnectionTransportMode.LOCAL_NETWORK,
                    ConnectionTransportMode.INTERNET
                )

                TabRow(
                    selectedTabIndex = transportModes.indexOf(selectedTransport).coerceAtLeast(0),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        val selectedIndex = transportModes.indexOf(selectedTransport).coerceAtLeast(0)
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    transportModes.forEach { mode ->
                        Tab(
                            selected = selectedTransport == mode,
                            onClick = {
                                viewModel.setConnectionTransport(mode)
                                if (mode == ConnectionTransportMode.INTERNET) {
                                    selectedRoleTab = 0
                                }
                                if (selectedRoleTab == 1 && mode == ConnectionTransportMode.LOCAL_NETWORK) {
                                    viewModel.activateHostMode(Build.MODEL)
                                } else {
                                    viewModel.activateClientMode()
                                }
                                wifiDirectChecks =
                                    context.readWifiDirectSystemChecks(requiredWifiDirectPermissions)
                            },
                            text = { Text(stringResource(mode.toLabelRes())) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedTransport == ConnectionTransportMode.LOCAL_NETWORK) {
                    val roleTabs = listOf(
                        stringResource(R.string.client_mode_tab),
                        stringResource(R.string.host_mode_tab)
                    )
                    TabRow(
                        selectedTabIndex = selectedRoleTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedRoleTab]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    ) {
                        roleTabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedRoleTab == index,
                                onClick = {
                                    selectedRoleTab = index
                                    if (index == 1) {
                                        viewModel.activateHostMode(Build.MODEL)
                                    } else {
                                        viewModel.activateClientMode()
                                    }
                                },
                                text = { Text(title) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }

                if (!connectionErrorMessage.isNullOrBlank()) {
                    StatusCard(title = stringResource(R.string.connection_error_title)) {
                        Text(
                            text = connectionErrorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.clearConnectionErrorMessage() }) {
                            Text(stringResource(R.string.close_desc))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (selectedRoleTab == 0) {
                    ClientModeContent(
                        selectedTransport = selectedTransport,
                        discoveredDevices = discoveredDevices,
                        wifiDirectDiscoveredDevices = wifiDirectDiscoveredDevices,
                        networkSnapshot = networkSnapshot,
                        wifiDirectState = wifiDirectState,
                        wifiDirectChecks = wifiDirectChecks,
                        onRequestWifiDirectPermission = {
                            wifiDirectPermissionLauncher.launch(requiredWifiDirectPermissions.toTypedArray())
                        },
                        onOpenWifiSettings = {
                            context.openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
                        },
                        onOpenLocationSettings = {
                            context.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        },
                        onOpenScanner = { viewState = PairViewState.Scanner },
                        onSelectDevice = { device ->
                            selectedDevice = device
                            viewState = PairViewState.Detail
                            pairState = PairConnectionState.Idle
                        },
                        onRefreshNetwork = { viewModel.refreshNetworkSnapshot() },
                        onRefreshWifiDirect = {
                            wifiDirectChecks =
                                context.readWifiDirectSystemChecks(requiredWifiDirectPermissions)
                            if (wifiDirectChecks.ready) {
                                viewModel.startWifiDirectDiscovery()
                            }
                        },
                        onDisconnectRouterWifi = { viewModel.disconnectRouterWifiForWifiDirect() }
                    )
                } else {
                    HostModeContent(
                        selectedTransport = selectedTransport,
                        qrCodeText = qrCodeText,
                        networkSnapshot = networkSnapshot,
                        wifiDirectState = wifiDirectState,
                        wifiDirectChecks = wifiDirectChecks,
                        onRequestWifiDirectPermission = {
                            wifiDirectPermissionLauncher.launch(requiredWifiDirectPermissions.toTypedArray())
                        },
                        onOpenWifiSettings = {
                            context.openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
                        },
                        onOpenLocationSettings = {
                            context.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        },
                        onRefreshNetwork = { viewModel.refreshNetworkSnapshot() },
                        onRetryWifiDirectHosting = {
                            wifiDirectChecks =
                                context.readWifiDirectSystemChecks(requiredWifiDirectPermissions)
                            if (wifiDirectChecks.ready) {
                                viewModel.startWifiDirectHosting()
                            }
                        },
                        onStopWifiDirectHosting = { viewModel.stopWifiDirectHosting() },
                        onDisconnectRouterWifi = { viewModel.disconnectRouterWifiForWifiDirect() }
                    )
                }
            }
        }

        is PairViewState.Detail -> {
            selectedDevice?.let { device ->
                DeviceDetailView(
                    device = device,
                    state = pairState,
                    errorMessage = connectionErrorMessage,
                    onBack = {
                        if (pairState == PairConnectionState.Connecting) {
                            viewModel.cancelPendingConnection()
                        }
                        viewState = PairViewState.List
                    },
                    onConnect = {
                        pairState = PairConnectionState.Connecting
                        viewModel.connectToDevice(device)
                    }
                )

                LaunchedEffect(connectionStatus, pairState, selectedDevice?.id) {
                    if (pairState == PairConnectionState.Connecting) {
                        when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> pairState = PairConnectionState.Connected
                            ConnectionStatus.CONNECTING,
                            ConnectionStatus.ERROR,
                            ConnectionStatus.DISCONNECTED -> Unit
                        }
                    }
                }

                LaunchedEffect(pairState, selectedDevice?.id) {
                    if (pairState == PairConnectionState.Connecting) {
                        delay(30_000)
                        if (pairState == PairConnectionState.Connecting) {
                            pairState = PairConnectionState.Error
                        }
                    }
                }
            }
        }

        is PairViewState.Scanner -> {
            ScannerView(
                onBack = { viewState = PairViewState.List },
                onCodeScanned = { code ->
                    val parsedDevice = viewModel.handleScannedCode(code)
                    if (parsedDevice != null) {
                        selectedDevice = parsedDevice
                        pairState = PairConnectionState.Connecting
                        viewModel.connectToDevice(parsedDevice)
                        viewState = PairViewState.Detail
                    } else {
                        viewState = PairViewState.List
                    }
                }
            )
        }
    }
}

@Composable
private fun ClientModeContent(
    selectedTransport: ConnectionTransportMode,
    discoveredDevices: List<Device>,
    wifiDirectDiscoveredDevices: List<Device>,
    networkSnapshot: NetworkUtils.NetworkSnapshot,
    wifiDirectState: WifiDirectState,
    wifiDirectChecks: WifiDirectSystemChecks,
    onRequestWifiDirectPermission: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenScanner: () -> Unit,
    onSelectDevice: (Device) -> Unit,
    onRefreshNetwork: () -> Unit,
    onRefreshWifiDirect: () -> Unit,
    onDisconnectRouterWifi: () -> Unit
) {
    when (selectedTransport) {
        ConnectionTransportMode.LOCAL_NETWORK -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.nearby_devices_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onOpenScanner) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            DeviceList(
                devices = discoveredDevices,
                onSelectDevice = onSelectDevice
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onOpenScanner,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.scan_qr_code))
            }
            Spacer(modifier = Modifier.height(16.dp))
            NetworkDiagnosticsCard(
                snapshot = networkSnapshot,
                onRefresh = onRefreshNetwork
            )
            Spacer(modifier = Modifier.height(12.dp))
            StatusCard(title = stringResource(R.string.wifi_direct_in_local_card_title), icon = Icons.Default.Wifi) {
                Text(
                    text = stringResource(R.string.wifi_direct_in_local_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                WifiDirectStatusCard(state = wifiDirectState)
                Spacer(modifier = Modifier.height(10.dp))
                if (!wifiDirectChecks.ready) {
                    WifiDirectPrerequisitesCard(
                        checks = wifiDirectChecks,
                        wifiDirectState = wifiDirectState,
                        onRequestPermission = onRequestWifiDirectPermission,
                        onOpenWifiSettings = onOpenWifiSettings,
                        onOpenLocationSettings = onOpenLocationSettings,
                        onDisconnectRouterWifi = onDisconnectRouterWifi
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRefreshWifiDirect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.wifi_direct_search_button))
                        }
                        OutlinedButton(
                            onClick = onDisconnectRouterWifi,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.wifi_direct_disconnect_router))
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    DeviceList(
                        devices = wifiDirectDiscoveredDevices,
                        emptyLabel = stringResource(R.string.wifi_direct_no_peers),
                        onSelectDevice = onSelectDevice
                    )
                }
            }
        }

        ConnectionTransportMode.INTERNET -> {
            StatusCard(title = stringResource(R.string.transport_internet_label)) {
                Text(
                    text = stringResource(R.string.internet_mode_future_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ConnectionTransportMode.WIFI_DIRECT -> Unit
    }
}

@Composable
private fun HostModeContent(
    selectedTransport: ConnectionTransportMode,
    qrCodeText: String?,
    networkSnapshot: NetworkUtils.NetworkSnapshot,
    wifiDirectState: WifiDirectState,
    wifiDirectChecks: WifiDirectSystemChecks,
    onRequestWifiDirectPermission: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onRetryWifiDirectHosting: () -> Unit,
    onStopWifiDirectHosting: () -> Unit,
    onDisconnectRouterWifi: () -> Unit
) {
    when (selectedTransport) {
        ConnectionTransportMode.LOCAL_NETWORK -> {
            StatusCard(title = stringResource(R.string.host_mode_label)) {
                Text(
                    text = stringResource(R.string.be_host_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.status_connected),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (qrCodeText != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val bitmap = remember(qrCodeText) { QrCodeUtils.generateQrCode(qrCodeText) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(200.dp)
                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            NetworkDiagnosticsCard(
                snapshot = networkSnapshot,
                onRefresh = onRefreshNetwork
            )
            Spacer(modifier = Modifier.height(12.dp))
            StatusCard(title = stringResource(R.string.wifi_direct_in_local_card_title), icon = Icons.Default.Wifi) {
                Text(
                    text = stringResource(R.string.wifi_direct_host_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                WifiDirectStatusCard(state = wifiDirectState)
                Spacer(modifier = Modifier.height(10.dp))
                if (!wifiDirectChecks.ready) {
                    WifiDirectPrerequisitesCard(
                        checks = wifiDirectChecks,
                        wifiDirectState = wifiDirectState,
                        onRequestPermission = onRequestWifiDirectPermission,
                        onOpenWifiSettings = onOpenWifiSettings,
                        onOpenLocationSettings = onOpenLocationSettings,
                        onDisconnectRouterWifi = onDisconnectRouterWifi
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRetryWifiDirectHosting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.retry_wifi_direct_group))
                        }
                        OutlinedButton(
                            onClick = onStopWifiDirectHosting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.stop_hosting))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDisconnectRouterWifi,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.wifi_direct_disconnect_router))
                    }
                }
            }
        }

        ConnectionTransportMode.INTERNET -> {
            StatusCard(title = stringResource(R.string.transport_internet_label)) {
                Text(
                    text = stringResource(R.string.internet_mode_future_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ConnectionTransportMode.WIFI_DIRECT -> Unit
    }
}

@Composable
private fun DeviceList(
    devices: List<Device>,
    emptyLabel: String? = null,
    onSelectDevice: (Device) -> Unit
) {
    if (devices.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(32.dp)
        ) {
            if (emptyLabel.isNullOrBlank()) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = emptyLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(devices) { device ->
            DeviceItem(device) {
                onSelectDevice(device)
            }
        }
    }
}

@Composable
private fun WifiDirectStatusCard(state: WifiDirectState) {
    StatusCard(title = stringResource(R.string.transport_wifi_direct_label), icon = Icons.Default.Wifi) {
        val statusText = when {
            !state.supported -> stringResource(R.string.wifi_direct_not_supported)
            !state.enabled -> stringResource(R.string.wifi_direct_system_disabled)
            state.connected -> stringResource(R.string.status_connected)
            state.connecting -> stringResource(R.string.status_connecting)
            state.discovering -> stringResource(R.string.wifi_direct_searching)
            else -> stringResource(R.string.status_disconnected)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (!state.groupOwnerIp.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.wifi_direct_group_owner_ip, state.groupOwnerIp.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!state.failureMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.failureMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun WifiDirectPrerequisitesCard(
    checks: WifiDirectSystemChecks,
    wifiDirectState: WifiDirectState,
    onRequestPermission: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onDisconnectRouterWifi: () -> Unit
) {
    StatusCard(title = stringResource(R.string.wifi_direct_requirements_title)) {
        Text(
            text = stringResource(R.string.wifi_direct_requirements_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (checks.hasPermission) {
                stringResource(R.string.wifi_direct_requirement_permissions_ok)
            } else {
                stringResource(R.string.wifi_direct_requirement_permissions_missing)
            },
            color = if (checks.hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            text = if (checks.wifiEnabled) {
                stringResource(R.string.wifi_direct_requirement_wifi_ok)
            } else {
                stringResource(R.string.wifi_direct_requirement_wifi_missing)
            },
            color = if (checks.wifiEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        if (checks.locationRequired) {
            Text(
                text = if (checks.locationEnabled) {
                    stringResource(R.string.wifi_direct_requirement_location_ok)
                } else {
                    stringResource(R.string.wifi_direct_requirement_location_missing)
                },
                color = if (checks.locationEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = if (wifiDirectState.infrastructureWifiConnected) {
                stringResource(R.string.wifi_direct_requirement_router_connected)
            } else {
                stringResource(R.string.wifi_direct_requirement_router_disconnected)
            },
            color = if (wifiDirectState.infrastructureWifiConnected) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (!checks.hasPermission) {
            OutlinedButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wifi_direct_grant_permissions))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (!checks.wifiEnabled) {
            OutlinedButton(onClick = onOpenWifiSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wifi_direct_open_wifi_settings))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (checks.locationRequired && !checks.locationEnabled) {
            OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wifi_direct_open_location_settings))
            }
        }
        if (wifiDirectState.infrastructureWifiConnected) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onDisconnectRouterWifi, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wifi_direct_disconnect_router))
            }
        }
    }
}

@Composable
fun DeviceItem(device: Device, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            UserProfileView(
                userId = device.id,
                avatarSize = 48,
                fallbackName = device.name
            )
            val endpoint = when {
                !device.ip.isNullOrBlank() -> "${device.ip}:${device.port ?: 8080}"
                device.connectionTransport == ConnectionTransportMode.WIFI_DIRECT -> stringResource(R.string.wifi_direct_endpoint_pending)
                else -> "-"
            }
            Text(
                text = endpoint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (device.candidateIps.size > 1) {
                Text(
                    text = stringResource(
                        R.string.connection_candidates_count,
                        device.candidateIps.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun DeviceDetailView(
    device: Device,
    state: PairConnectionState,
    errorMessage: String?,
    onBack: () -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 16.dp)
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.back))
        }

        Spacer(modifier = Modifier.height(24.dp))

        StatusCard(title = stringResource(R.string.pairing_card_title)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                UserProfileView(
                    userId = device.id,
                    avatarSize = 80,
                    fallbackName = device.name
                )
                Spacer(modifier = Modifier.height(12.dp))
                val targetText = when {
                    !device.ip.isNullOrBlank() -> stringResource(
                        R.string.connection_target,
                        device.ip.orEmpty(),
                        device.port ?: 8080
                    )
                    device.connectionTransport == ConnectionTransportMode.WIFI_DIRECT ->
                        stringResource(R.string.wifi_direct_endpoint_pending)
                    else -> stringResource(R.string.connection_target, "-", device.port ?: 8080)
                }
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (device.candidateIps.size > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = device.candidateIps.joinToString(separator = ", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                when (state) {
                    PairConnectionState.Idle -> {
                        BigButton(text = stringResource(R.string.connect), onClick = onConnect, fullWidth = true)
                    }

                    PairConnectionState.Connecting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.connecting), color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    PairConnectionState.Connected -> {
                        Text(
                            stringResource(R.string.connected),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        LaunchedEffect(Unit) {
                            delay(1000)
                            onBack()
                        }
                    }

                    PairConnectionState.Error -> {
                        Text(
                            text = errorMessage ?: stringResource(R.string.connection_failed),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        BigButton(
                            text = stringResource(R.string.connect),
                            onClick = onConnect,
                            variant = ButtonVariant.Outline,
                            fullWidth = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerView(
    onBack: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            QrCodeScanner(onCodeScanned = onCodeScanned)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                StatusCard(title = stringResource(R.string.camera_permission_title), icon = Icons.Default.QrCodeScanner) {
                    Text(
                        text = stringResource(R.string.camera_permission_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    BigButton(
                        text = stringResource(R.string.grant_camera_permission),
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        fullWidth = true
                    )
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopEnd)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_desc))
        }
    }
}

@Composable
private fun NetworkDiagnosticsCard(
    snapshot: NetworkUtils.NetworkSnapshot,
    onRefresh: () -> Unit
) {
    StatusCard(
        title = stringResource(R.string.network_diagnostics_title),
        icon = Icons.Default.Wifi
    ) {
        Text(
            text = stringResource(
                R.string.network_primary_ip,
                snapshot.primaryIpv4 ?: stringResource(R.string.unknown_device)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.network_candidate_ips,
                if (snapshot.ipv4Candidates.isEmpty()) "-" else snapshot.ipv4Candidates.joinToString(", ")
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.network_interfaces_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (snapshot.interfaceAddresses.isEmpty()) {
            Text(
                text = stringResource(R.string.network_interfaces_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            snapshot.interfaceAddresses.take(10).forEach { info ->
                Text(
                    text = "${info.interfaceName} (${if (info.isIpv4) "IPv4" else "IPv6"}) -> ${info.address} [${info.score}]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.refresh_network_info))
        }
    }
}

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun PairConnectedView(
    connectedPeer: Device?,
    onDisconnectClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        StatusCard(title = stringResource(R.string.connection_header)) {
            connectedPeer?.let { peer ->
                UserProfileView(
                    userId = peer.id,
                    fallbackName = peer.name,
                    avatarSize = 40,
                    showId = false
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    status = BadgeStatus.Connected,
                    label = stringResource(R.string.status_connected)
                )
                TextButton(onClick = onDisconnectClick) {
                    Text(
                        text = stringResource(R.string.disconnect),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

sealed class PairViewState {
    object List : PairViewState()
    object Detail : PairViewState()
    object Scanner : PairViewState()
}

enum class PairConnectionState {
    Idle, Connecting, Connected, Error
}

private fun ConnectionTransportMode.toLabelRes(): Int {
    return when (this) {
        ConnectionTransportMode.LOCAL_NETWORK -> R.string.transport_local_network_label
        ConnectionTransportMode.WIFI_DIRECT -> R.string.transport_local_network_label
        ConnectionTransportMode.INTERNET -> R.string.transport_internet_label
    }
}

private fun requiredWifiDirectPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun Context.readWifiDirectSystemChecks(requiredPermissions: List<String>): WifiDirectSystemChecks {
    val hasPermission = requiredPermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
    val wifiEnabled = runCatching {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.isWifiEnabled
    }.getOrDefault(false)

    val locationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    val locationEnabled = if (!locationRequired) {
        true
    } else {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }
    }
    return WifiDirectSystemChecks(
        hasPermission = hasPermission,
        wifiEnabled = wifiEnabled,
        locationRequired = locationRequired,
        locationEnabled = locationEnabled
    )
}

private fun Context.openSystemSettings(action: String) {
    runCatching {
        startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private data class WifiDirectSystemChecks(
    val hasPermission: Boolean,
    val wifiEnabled: Boolean,
    val locationRequired: Boolean,
    val locationEnabled: Boolean
) {
    val ready: Boolean
        get() = hasPermission && wifiEnabled && (!locationRequired || locationEnabled)
}
