package dev.wads.motoridecallconnect.ui.activetrip

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Status: ${uiState.connectionStatus}", fontSize = 20.sp)
        Spacer(modifier = Modifier.height(16.dp))

        Row {
            Button(onClick = onStartTripClick, modifier = Modifier.weight(1f)) {
                Text("Iniciar Viagem")
            }
            Spacer(modifier = Modifier.weight(0.1f))
            Button(onClick = onEndTripClick, modifier = Modifier.weight(1f)) {
                Text("Finalizar Viagem")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Modo de Operação", fontSize = 18.sp)
        OperatingMode.values().forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = (uiState.operatingMode == mode), onClick = { onModeChange(mode) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = (uiState.operatingMode == mode), onClick = { onModeChange(mode) })
                Text(text = mode.name.replace("_", " "))
            }
        }

        if (uiState.operatingMode == OperatingMode.VOICE_COMMAND) {
            OutlinedTextField(
                value = uiState.startCommand,
                onValueChange = onStartCommandChange,
                label = { Text("Comando para Iniciar") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.stopCommand,
                onValueChange = onStopCommandChange,
                label = { Text("Comando para Parar") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gravar Transcrição da Viagem")
            Spacer(modifier = Modifier.weight(1f))
            Switch(checked = uiState.isRecordingTranscript, onCheckedChange = onRecordingToggle)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStartDiscoveryClick) {
            Text("Buscar Dispositivos")
        }
        LazyColumn(modifier = Modifier.height(100.dp)) {
            items(uiState.discoveredServices) { service ->
                Text(text = service.serviceName)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Transcrição ao vivo:", fontSize = 18.sp)
        Card(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                // A transcrição aparecerá aqui
            }
        }
    }
}