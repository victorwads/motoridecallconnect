package dev.wads.motoridecallconnect.ui.pairing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.model.Device
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

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onNavigateBack: () -> Unit,
    onConnectToDevice: (Device) -> Unit,
    onDisconnectClick: () -> Unit
) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isHosting by viewModel.isHosting.collectAsState()
    val qrCodeText by viewModel.qrCodeText.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectedPeer by viewModel.connectedPeer.collectAsState()
    val networkSnapshot by viewModel.networkSnapshot.collectAsState()

    var selectedModeTab by remember { mutableIntStateOf(if (isHosting) 1 else 0) } // 0: Client, 1: Host
    var viewState by remember { mutableStateOf<PairViewState>(PairViewState.List) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var pairState by remember { mutableStateOf<PairConnectionState>(PairConnectionState.Idle) }

    val modeTabs = listOf(
        stringResource(R.string.client_mode_tab),
        stringResource(R.string.host_mode_tab)
    )

    LaunchedEffect(isHosting) {
        selectedModeTab = if (isHosting) 1 else 0
    }

    if (connectionStatus == ConnectionStatus.CONNECTED) {
        PairConnectedView(
            connectedPeer = connectedPeer,
            onDisconnectClick = onDisconnectClick
        )
        return
    }

    // Handle view transitions logic
    when (viewState) {
        is PairViewState.List -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .padding(top = 16.dp)
            ) {
                // Header
                Text(
                    text = stringResource(R.string.pairing_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Exclusive role tabs: Client OR Host
                TabRow(
                    selectedTabIndex = selectedModeTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedModeTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    modeTabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedModeTab == index,
                            onClick = {
                                selectedModeTab = index
                                if (index == 1) {
                                    viewModel.activateHostMode(android.os.Build.MODEL)
                                } else {
                                    viewModel.activateClientMode()
                                }
                            },
                            text = { Text(title) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                if (selectedModeTab == 0) {
                    // Client mode: discover and connect
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
                        IconButton(onClick = { viewState = PairViewState.Scanner }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (discoveredDevices.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true)
                                .padding(32.dp)
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(discoveredDevices) { device ->
                                DeviceItem(device) {
                                    selectedDevice = device
                                    viewState = PairViewState.Detail
                                    pairState = PairConnectionState.Idle
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { viewState = PairViewState.Scanner },
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
                        onRefresh = { viewModel.refreshNetworkSnapshot() }
                    )
                } else {
                    // Host mode: only publish and show QR.
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
                            qrCodeText?.let { text ->
                                val bitmap = remember(text) { QrCodeUtils.generateQrCode(text) }
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
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    NetworkDiagnosticsCard(
                        snapshot = networkSnapshot,
                        onRefresh = { viewModel.refreshNetworkSnapshot() }
                    )
                }
            }
        }
        
        is PairViewState.Detail -> {
            selectedDevice?.let { device ->
                DeviceDetailView(
                    device = device,
                    state = pairState,
                    onBack = { viewState = PairViewState.List },
                    onConnect = {
                        pairState = PairConnectionState.Connecting
                        onConnectToDevice(device)
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
                        delay(15_000)
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
                        onConnectToDevice(parsedDevice)
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
            val endpoint = "${device.ip ?: "-"}:${device.port ?: 8080}"
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
    onBack: () -> Unit,
    onConnect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).padding(top=16.dp)) {
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
                Text(
                    text = stringResource(
                        R.string.connection_target,
                        device.ip ?: "-",
                        device.port ?: 8080
                    ),
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
                        Text(stringResource(R.string.connected), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        LaunchedEffect(Unit) {
                            delay(1000)
                            onBack()
                        }
                    }
                    PairConnectionState.Error -> {
                        Text(
                            text = stringResource(R.string.connection_failed),
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
