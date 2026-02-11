package dev.wads.motoridecallconnect.ui.activetrip

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.wads.motoridecallconnect.ui.components.ChronometerView
import dev.wads.motoridecallconnect.ui.components.StatusBadge
import dev.wads.motoridecallconnect.ui.components.StatusCard
import dev.wads.motoridecallconnect.ui.components.UserProfileView

@Composable
fun ActiveTripScreen(
    uiState: ActiveTripUiState,
    isHost: Boolean,
    onEndTripClick: () -> Unit,
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
            if (uiState.connectionStatus == ConnectionStatus.CONNECTED && uiState.isTripActive) {
                Text(
                    text = stringResource(R.string.live_indicator),
                    color = Color(0xFF22C55E), // SuccessGreen
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        if (uiState.isModelDownloading) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.downloading_model),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { uiState.modelDownloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${uiState.modelDownloadProgress}%", 
                        style = MaterialTheme.typography.bodySmall, 
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }

        // --- Connection Status (Smaller) ---
        StatusCard(title = stringResource(R.string.connection_header), icon = Icons.Default.Share) { // Placeholder for Link2
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    StatusBadge(status = badgeStatus, label = connectionStatusLabel)
                    
                    if (uiState.connectedPeer != null && (uiState.connectionStatus == ConnectionStatus.CONNECTED || uiState.connectionStatus == ConnectionStatus.CONNECTING)) {
                        Spacer(modifier = Modifier.width(12.dp))
                        UserProfileView(
                            userId = uiState.connectedPeer.id,
                            fallbackName = uiState.connectedPeer.name,
                            avatarSize = 32,
                            showId = false
                        )
                    }
                }

                if (badgeStatus == BadgeStatus.Connected || badgeStatus == BadgeStatus.Connecting) {
                    TextButton(onClick = onDisconnectClick) {
                        Text(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (badgeStatus == BadgeStatus.Disconnected) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Conexão e pareamento são controlados apenas pela aba Pairing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- Trip Controls ---
        StatusCard(title = stringResource(R.string.trip_header), icon = Icons.Default.PlayArrow) {
            if (uiState.isTripActive) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    uiState.tripStartTime?.let {
                        ChronometerView(startTimeMillis = it)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    BigButton(
                        text = stringResource(R.string.end_trip_action),
                        onClick = onEndTripClick,
                        variant = ButtonVariant.Destructive,
                        icon = Icons.Default.Stop,
                        size = ButtonSize.Xl,
                        fullWidth = true
                    )
                }
            } else {
                Text(
                    text = "Trip inativa. O início da trip não é feito por este botão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- Transcription List ---
        if (uiState.isTripActive || uiState.transcript.isNotEmpty()) {
            StatusCard(title = stringResource(R.string.full_transcript_title), icon = Icons.Default.Call) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.transcript.isEmpty()) {
                        Text(
                            text = "Aguardando falas...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    uiState.transcript.takeLast(10).forEach { line ->
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
                isHost = true,
                onEndTripClick = {}
            )
        }
    }
}
