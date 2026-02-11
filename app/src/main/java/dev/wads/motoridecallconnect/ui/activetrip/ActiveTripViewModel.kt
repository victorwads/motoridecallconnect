package dev.wads.motoridecallconnect.ui.activetrip

import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class OperatingMode {
    VOICE_COMMAND,
    VOICE_ACTIVITY_DETECTION,
    CONTINUOUS_TRANSMISSION
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ActiveTripUiState(
    val discoveredServices: List<NsdServiceInfo> = emptyList(),
    val operatingMode: OperatingMode = OperatingMode.VOICE_COMMAND,
    val startCommand: String = "iniciar",
    val stopCommand: String = "parar",
    val isRecordingTranscript: Boolean = true,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val transcript: List<String> = emptyList()
)

class ActiveTripViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ActiveTripUiState())
    val uiState: StateFlow<ActiveTripUiState> = _uiState.asStateFlow()

    fun addService(serviceInfo: NsdServiceInfo) {
        _uiState.update {
            val currentServices = it.discoveredServices.toMutableList()
            if (currentServices.none { s -> s.serviceName == serviceInfo.serviceName }) {
                currentServices.add(serviceInfo)
            }
            it.copy(discoveredServices = currentServices)
        }
    }

    fun removeService(serviceInfo: NsdServiceInfo) {
        _uiState.update {
            val currentServices = it.discoveredServices.toMutableList()
            currentServices.removeAll { s -> s.serviceName == serviceInfo.serviceName }
            it.copy(discoveredServices = currentServices)
        }
    }

    fun onModeChange(mode: OperatingMode) {
        _uiState.update { it.copy(operatingMode = mode) }
    }

    fun onStartCommandChange(command: String) {
        _uiState.update { it.copy(startCommand = command) }
    }

    fun onStopCommandChange(command: String) {
        _uiState.update { it.copy(stopCommand = command) }
    }

    fun onRecordingToggle(isRecording: Boolean) {
        _uiState.update { it.copy(isRecordingTranscript = isRecording) }
    }

    fun updateTranscript(newTranscript: String, isFinal: Boolean) {
        _uiState.update {
            val currentTranscript = it.transcript.toMutableList()
            if (isFinal) {
                // If final, remove last partial and add the final one
                if (currentTranscript.isNotEmpty()) currentTranscript.removeAt(currentTranscript.lastIndex)
                currentTranscript.add(newTranscript)
            } else {
                // If partial, add or replace the last one
                if (currentTranscript.isEmpty() || it.transcript.last().startsWith("Parcial:").not()) {
                    currentTranscript.add("Parcial: $newTranscript")
                } else {
                    currentTranscript[currentTranscript.lastIndex] = "Parcial: $newTranscript"
                }
            }
            it.copy(transcript = currentTranscript)
        }
    }
}