package dev.wads.motoridecallconnect.ui.pairing

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.EmptyState
import dev.wads.motoridecallconnect.ui.components.StatusCard
import dev.wads.motoridecallconnect.utils.QrCodeUtils
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onNavigateBack: () -> Unit,
    onConnectToDevice: (Device) -> Unit
) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isHosting by viewModel.isHosting.collectAsState()
    val qrCodeText by viewModel.qrCodeText.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Quick, 1: Rooms
    var viewState by remember { mutableStateOf<PairViewState>(PairViewState.List) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var pairState by remember { mutableStateOf<PairConnectionState>(PairConnectionState.Idle) }

    val tabs = listOf(stringResource(R.string.quick_pair_tab), stringResource(R.string.rooms_tab))

    // Handle view transitions logic
    when (val currentView = viewState) {
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

                // Host Mode Toggle
                StatusCard(title = stringResource(R.string.host_mode_label)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isHosting) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isHosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.be_host_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isHosting,
                            onCheckedChange = { 
                                if (it) viewModel.startHosting("Meu Intercom") // Should use real device name
                                else viewModel.stopHosting()
                            }
                        )
                    }
                    
                    if (isHosting && qrCodeText != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        qrCodeText?.let { text ->
                            val bitmap = remember(text) { QrCodeUtils.generateQrCode(text) }
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "QR Code",
                                    modifier = Modifier.size(200.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(8.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                if (selectedTab == 0) {
                    // Quick Pair List
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
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(discoveredDevices) { device ->
                                DeviceItem(device) {
                                    selectedDevice = device
                                    viewState = PairViewState.Detail
                                    pairState = PairConnectionState.Idle
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Code option
                    OutlinedButton(
                        onClick = { viewState = PairViewState.Code },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.use_pairing_code))
                    }
                } else {
                    // Rooms (Mock for now)
                    EmptyState(
                        icon = Icons.Default.Group,
                        title = stringResource(R.string.no_rooms_found),
                        description = stringResource(R.string.create_room_hint)
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
                        viewModel.connectToDevice(device)
                    }
                )
                
                LaunchedEffect(isConnected) {
                    if (isConnected) {
                        pairState = PairConnectionState.Connected
                    }
                }
            }
        }
        
        is PairViewState.Code -> {
            CodePairView(onBack = { viewState = PairViewState.List })
        }

        is PairViewState.Scanner -> {
            Box(modifier = Modifier.fillMaxSize()) {
                QrCodeScanner { code ->
                    viewModel.handleScannedCode(code)
                    viewState = PairViewState.List
                }
                IconButton(
                    onClick = { viewState = PairViewState.List },
                    modifier = Modifier.padding(16.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
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
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(text = device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = device.deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(device.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(device.deviceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                Spacer(modifier = Modifier.height(32.dp))
                
                when (state) {
                    PairConnectionState.Idle -> {
                        BigButton(text = stringResource(R.string.connect), onClick = onConnect, fullWidth = true)
                        Spacer(modifier = Modifier.height(12.dp))
                        BigButton(text = stringResource(R.string.connect_and_start_trip), variant = ButtonVariant.Success, onClick = onConnect, fullWidth = true)
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
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun CodePairView(onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).padding(top=16.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.back))
        }

        Spacer(modifier = Modifier.height(24.dp))

        StatusCard(title = stringResource(R.string.pairing_code_title), icon = Icons.Default.QrCode) {
            Column {
                Text(
                    text = stringResource(R.string.pairing_code_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.pairing_code_label)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                )

                BigButton(
                    text = stringResource(R.string.connect_devices_action),
                    onClick = { /* TODO Pair */ },
                    fullWidth = true
                )
            }
        }
    }
}

sealed class PairViewState {
    object List : PairViewState()
    object Detail : PairViewState()
    object Code : PairViewState()
    object Scanner : PairViewState()
}

enum class PairConnectionState {
    Idle, Connecting, Connected, Error
}
