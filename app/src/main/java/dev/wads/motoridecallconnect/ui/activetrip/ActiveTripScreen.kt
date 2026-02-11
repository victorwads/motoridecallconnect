package dev.wads.motoridecallconnect.ui.activetrip

import android.net.nsd.NsdServiceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.ui.components.BadgeStatus
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonSize
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.StatusBadge
import dev.wads.motoridecallconnect.ui.components.StatusCard

@Composable
fun ActiveTripScreen(
    uiState: ActiveTripUiState,
    onStartTripClick: () -> Unit,
    onEndTripClick: () -> Unit,
    onStartDiscoveryClick: () -> Unit,
    onModeChange: (OperatingMode) -> Unit,
    onStartCommandChange: (String) -> Unit,
    onStopCommandChange: (String) -> Unit,
    onRecordingToggle: (Boolean) -> Unit,
    onConnectToService: (NsdServiceInfo) -> Unit = {},
    onDisconnectClick: () -> Unit = {}
) {
    val badgeStatus = when (uiState.connectionStatus) {
        ConnectionStatus.CONNECTED -> BadgeStatus.Connected
        ConnectionStatus.CONNECTING -> BadgeStatus.Connecting
        ConnectionStatus.ERROR -> BadgeStatus.Error
        else -> BadgeStatus.Disconnected
    }
    
    val connectionStatusLabel = when (uiState.connectionStatus) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
        ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionStatus.ERROR -> stringResource(R.string.status_error)
        else -> stringResource(R.string.status_disconnected)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Mic, // Placeholder for Radio
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.app_short_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (uiState.connectionStatus == ConnectionStatus.CONNECTED) {
                Text(
                    text = stringResource(R.string.live_indicator),
                    color = Color(0xFF22C55E), // SuccessGreen
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        // --- Connection Status ---
        StatusCard(title = stringResource(R.string.connection_header), icon = Icons.Default.Share) { // Placeholder for Link2
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status = badgeStatus, label = connectionStatusLabel)
                
                if (uiState.connectedPeer != null && (uiState.connectionStatus == ConnectionStatus.CONNECTED || uiState.connectionStatus == ConnectionStatus.CONNECTING)) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = uiState.connectedPeer.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "ID: ${uiState.connectedPeer.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (badgeStatus == BadgeStatus.Disconnected) {
                BigButton(
                    text = stringResource(R.string.pair_now),
                    onClick = onStartDiscoveryClick,
                    variant = ButtonVariant.Outline,
                    fullWidth = true
                )
                
                // Show list only if disconnected
                if (uiState.discoveredServices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.discovered_devices_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.discoveredServices.forEach { service ->
                        Button(
                            onClick = { onConnectToService(service) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.connect_to_device_format, service.serviceName))
                        }
                    }
                }
                
            } else if (badgeStatus == BadgeStatus.Connected || badgeStatus == BadgeStatus.Connecting) {
                 BigButton(
                    text = stringResource(R.string.disconnect),
                    onClick = onDisconnectClick,
                    variant = ButtonVariant.Destructive,
                    fullWidth = true
                 )
            }
        }

        // --- Trip Controls ---
        StatusCard(title = stringResource(R.string.trip_header), icon = Icons.Default.PlayArrow) {
            // Timer UI would be here
            
            if (uiState.isTripActive) {
                 BigButton(
                    text = stringResource(R.string.end_trip_action),
                    onClick = onEndTripClick,
                    variant = ButtonVariant.Destructive,
                    icon = Icons.Default.Stop,
                    size = ButtonSize.Xl,
                    fullWidth = true
                 )
            } else {
                 BigButton(
                    text = stringResource(R.string.start_trip_action),
                    onClick = onStartTripClick,
                    variant = ButtonVariant.Success,
                    icon = Icons.Default.PlayArrow,
                    size = ButtonSize.Xl,
                    fullWidth = true
                 )
            }
        }

        // --- Configuration ---
        StatusCard(title = stringResource(R.string.config_header), icon = Icons.Default.Settings) {
            Text(stringResource(R.string.operation_mode_header), style = MaterialTheme.typography.titleSmall)
            
            OperatingMode.values().forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (uiState.operatingMode == mode),
                            onClick = { onModeChange(mode) }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (uiState.operatingMode == mode),
                        onClick = { onModeChange(mode) }
                    )
                    val modeText = when(mode) {
                        OperatingMode.VOICE_COMMAND -> stringResource(R.string.mode_voice_command)
                        OperatingMode.VOICE_ACTIVITY_DETECTION -> stringResource(R.string.mode_vad)
                        OperatingMode.CONTINUOUS_TRANSMISSION -> stringResource(R.string.mode_continuous)
                    }
                    Text(text = modeText)
                }
            }

            if (uiState.operatingMode == OperatingMode.VOICE_COMMAND) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.startCommand,
                    onValueChange = onStartCommandChange,
                    label = { Text(stringResource(R.string.start_command_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.stopCommand,
                    onValueChange = onStopCommandChange,
                    label = { Text(stringResource(R.string.stop_command_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.record_transcript_label))
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = uiState.isRecordingTranscript, 
                    onCheckedChange = onRecordingToggle
                )
            }
        }

        // --- Transcript List ---
        if (uiState.transcript.isNotEmpty()) {
            StatusCard(title = stringResource(R.string.full_transcript_title), icon = Icons.Default.Call) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.transcript.takeLast(5).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (line.startsWith("Parcial:")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ActiveTripScreenPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
            ActiveTripScreen(
                uiState = ActiveTripUiState(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    discoveredServices = emptyList(),
                    transcript = listOf("Olá", "Tudo bem?", "Na escuta.")
                ),
                onStartTripClick = {},
                onEndTripClick = {},
                onStartDiscoveryClick = {},
                onModeChange = {},
                onStartCommandChange = {},
                onStopCommandChange = {},
                onRecordingToggle = {}
            )
        }
    }
}
