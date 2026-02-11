package dev.wads.motoridecallconnect.ui.settings

import androidx.lifecycle.ViewModel
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val operatingMode: OperatingMode = OperatingMode.VOICE_COMMAND,
    val startCommand: String = "iniciar",
    val stopCommand: String = "parar",
    val isRecordingTranscript: Boolean = true
)

class SettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
}