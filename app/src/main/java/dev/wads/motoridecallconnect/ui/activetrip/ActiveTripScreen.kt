package dev.wads.motoridecallconnect.ui.activetrip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonSize
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.ChronometerView
import dev.wads.motoridecallconnect.ui.components.StatusCard

@Composable
fun ActiveTripScreen(
    uiState: ActiveTripUiState,
    onStartTripClick: () -> Unit,
    onEndTripClick: () -> Unit
) {
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
                BigButton(
                    text = stringResource(R.string.start_trip_action),
                    onClick = onStartTripClick,
                    variant = ButtonVariant.Primary,
                    icon = Icons.Default.PlayArrow,
                    size = ButtonSize.Xl,
                    fullWidth = true
                )
            }
        }

        TransmissionStatusCard(uiState = uiState)

        if (!uiState.tripPath.isNullOrBlank()) {
            StatusCard(title = "Trip Sync Path", icon = Icons.Default.Call) {
                Text(
                    text = uiState.tripPath,
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

@Composable
private fun TransmissionStatusCard(uiState: ActiveTripUiState) {
    val peerName = uiState.connectedPeer?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.trip_peer_default_name)

    val isConnected = uiState.connectionStatus == ConnectionStatus.CONNECTED
    val localStatusText = if (uiState.isLocalTransmitting) {
        stringResource(R.string.transmission_local_active)
    } else {
        stringResource(R.string.transmission_local_idle)
    }
    val remoteStatusText = when {
        !isConnected -> stringResource(R.string.transmission_waiting_connection)
        uiState.isRemoteTransmitting -> stringResource(R.string.transmission_remote_active)
        else -> stringResource(R.string.transmission_remote_idle)
    }

    StatusCard(title = stringResource(R.string.transmission_status_title), icon = Icons.Default.Mic) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ParticipantTransmissionTile(
                modifier = Modifier.weight(1f),
                name = stringResource(R.string.transmission_you_label),
                statusText = localStatusText,
                isActive = uiState.isLocalTransmitting
            )
            ParticipantTransmissionTile(
                modifier = Modifier.weight(1f),
                name = peerName,
                statusText = remoteStatusText,
                isActive = isConnected && uiState.isRemoteTransmitting
            )
        }
    }
}

@Composable
private fun ParticipantTransmissionTile(
    modifier: Modifier = Modifier,
    name: String,
    statusText: String,
    isActive: Boolean
) {
    val borderColor = if (isActive) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline
    val fillColor = if (isActive) Color(0xFF22C55E).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val circleSize: Dp = 54.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(circleSize)
                .border(width = 3.dp, color = borderColor, shape = CircleShape)
                .background(fillColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
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
                onEndTripClick = {}
            )
        }
    }
}
