package dev.wads.motoridecallconnect.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.local.UserPreferences
import dev.wads.motoridecallconnect.stt.SttEngine
import dev.wads.motoridecallconnect.stt.WhisperModelCatalog
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val operatingMode: OperatingMode = OperatingMode.VOICE_COMMAND,
    val startCommand: String = "iniciar",
    val stopCommand: String = "parar",
    val isRecordingTranscript: Boolean = true,
    val sttEngine: SttEngine = SttEngine.WHISPER,
    val whisperModelId: String = WhisperModelCatalog.defaultOption.id
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = UserPreferences(application.applicationContext)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.operatingMode.collect { raw ->
                val mode = parseOperatingMode(raw) ?: return@collect
                _uiState.update { it.copy(operatingMode = mode) }
            }
        }
        viewModelScope.launch {
            preferences.startCommand.collect { saved ->
                if (saved != null) {
                    _uiState.update { it.copy(startCommand = saved) }
                }
            }
        }
        viewModelScope.launch {
            preferences.stopCommand.collect { saved ->
                if (saved != null) {
                    _uiState.update { it.copy(stopCommand = saved) }
                }
            }
        }
        viewModelScope.launch {
            preferences.recordTranscript.collect { saved ->
                if (saved != null) {
                    _uiState.update { it.copy(isRecordingTranscript = saved) }
                }
            }
        }
        viewModelScope.launch {
            preferences.sttEngine.collect { raw ->
                val engine = parseSttEngine(raw) ?: return@collect
                _uiState.update { it.copy(sttEngine = engine) }
            }
        }
        viewModelScope.launch {
            preferences.whisperModelId.collect { saved ->
                val modelId = WhisperModelCatalog.findById(saved ?: "")?.id ?: return@collect
                _uiState.update { it.copy(whisperModelId = modelId) }
            }
        }
    }

    fun onModeChange(mode: OperatingMode) {
        _uiState.update { it.copy(operatingMode = mode) }
        viewModelScope.launch {
            preferences.setOperatingMode(mode.name)
        }
    }

    fun onStartCommandChange(command: String) {
        _uiState.update { it.copy(startCommand = command) }
        viewModelScope.launch {
            preferences.setStartCommand(command)
        }
    }

    fun onStopCommandChange(command: String) {
        _uiState.update { it.copy(stopCommand = command) }
        viewModelScope.launch {
            preferences.setStopCommand(command)
        }
    }

    fun onRecordingToggle(isRecording: Boolean) {
        _uiState.update { it.copy(isRecordingTranscript = isRecording) }
        viewModelScope.launch {
            preferences.setRecordTranscript(isRecording)
        }
    }

    fun onSttEngineChange(engine: SttEngine) {
        _uiState.update { it.copy(sttEngine = engine) }
        viewModelScope.launch {
            preferences.setSttEngine(engine.name)
        }
    }

    fun onWhisperModelChange(modelId: String) {
        val model = WhisperModelCatalog.findById(modelId) ?: return
        _uiState.update { it.copy(whisperModelId = model.id) }
        viewModelScope.launch {
            preferences.setWhisperModelId(model.id)
        }
    }

    private fun parseOperatingMode(raw: String?): OperatingMode? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { OperatingMode.valueOf(raw) }.getOrNull()
    }

    private fun parseSttEngine(raw: String?): SttEngine? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { SttEngine.valueOf(raw) }.getOrNull()
    }
}
