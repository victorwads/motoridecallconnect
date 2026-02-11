package dev.wads.motoridecallconnect.ui.activetrip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onRecordingToggle: (Boolean) -> Unit
) {
    val badgeStatus = when (uiState.connectionStatus) {
        "Conectado" -> BadgeStatus.Connected
        "Conectando" -> BadgeStatus.Connecting
        "Erro" -> BadgeStatus.Error
        else -> BadgeStatus.Disconnected
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
                    text = "MotoTalk",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (uiState.connectionStatus == "Conectado") {
                Text(
                    text = "● AO VIVO",
                    color = Color(0xFF22C55E), // SuccessGreen
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        // --- Connection Status ---
        StatusCard(title = "Conexão", icon = Icons.Default.Share) { // Placeholder for Link2
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                StatusBadge(status = badgeStatus, label = uiState.connectionStatus)
            }

            if (badgeStatus == BadgeStatus.Disconnected) {
                BigButton(
                    text = "Parear agora",
                    onClick = onStartDiscoveryClick,
                    variant = ButtonVariant.Outline,
                    fullWidth = true
                )
                
                // Show list only if disconnected
                if (uiState.discoveredServices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Dispositivos encontrados:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.discoveredServices.forEach { service ->
                        // This button needs to trigger connection
                        Button(
                            onClick = { /* TODO connection callback */ },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("Conectar a ${service.serviceName}")
                        }
                    }
                }
                
            } else if (badgeStatus == BadgeStatus.Connected) {
                 BigButton(
                    text = "Desconectar",
                    onClick = { /* TODO: Disconnect */ },
                    variant = ButtonVariant.Destructive,
                    fullWidth = true
                 )
            }
        }

        // --- Trip Controls ---
        StatusCard(title = "Viagem", icon = Icons.Default.PlayArrow) {
            // Timer UI would be here
            
            if (uiState.connectionStatus == "Conectado") { // Assuming trip is started if connected for now
                 BigButton(
                    text = "Finalizar Viagem",
                    onClick = onEndTripClick,
                    variant = ButtonVariant.Destructive,
                    icon = Icons.Default.Stop,
                    size = ButtonSize.Xl,
                    fullWidth = true
                 )
            } else {
                 BigButton(
                    text = "Iniciar Viagem",
                    onClick = onStartTripClick,
                    variant = ButtonVariant.Success,
                    icon = Icons.Default.PlayArrow,
                    size = ButtonSize.Xl,
                    fullWidth = true
                 )
            }
        }

        // --- Configuration ---
        StatusCard(title = "Configuração", icon = Icons.Default.Settings) {
            Text("Modo de Operação", style = MaterialTheme.typography.titleSmall)
            
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
                    Text(text = mode.name.replace("_", " "))
                }
            }

            if (uiState.operatingMode == OperatingMode.VOICE_COMMAND) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.startCommand,
                    onValueChange = onStartCommandChange,
                    label = { Text("Comando para Iniciar") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.stopCommand,
                    onValueChange = onStopCommandChange,
                    label = { Text("Comando para Parar") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gravar Transcrição")
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = uiState.isRecordingTranscript, 
                    onCheckedChange = onRecordingToggle
                )
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
                    connectionStatus = "Conectado",
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
