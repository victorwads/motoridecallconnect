package dev.wads.motoridecallconnect.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wads.motoridecallconnect.data.local.UserPreferences
import dev.wads.motoridecallconnect.stt.NativeSpeechLanguageCatalog
import dev.wads.motoridecallconnect.stt.SttEngine
import dev.wads.motoridecallconnect.stt.WhisperModelCatalog
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DEFAULT_VAD_START_DELAY_SECONDS = 0f
private const val DEFAULT_VAD_STOP_DELAY_SECONDS = 1.5f
private const val MIN_VAD_DELAY_SECONDS = 0f
private const val MAX_VAD_DELAY_SECONDS = 5f
private const val DEFAULT_PRESENCE_UPDATE_INTERVAL_SECONDS = 30
private const val MIN_PRESENCE_UPDATE_INTERVAL_SECONDS = 10
private const val MAX_PRESENCE_UPDATE_INTERVAL_SECONDS = 300

data class SettingsUiState(
    val operatingMode: OperatingMode = OperatingMode.VOICE_COMMAND,
    val startCommand: String = "iniciar",
    val stopCommand: String = "parar",
    val isRecordingTranscript: Boolean = true,
    val sttEngine: SttEngine = SttEngine.WHISPER,
    val nativeSpeechLanguageTag: String = NativeSpeechLanguageCatalog.defaultOption.tag,
    val whisperModelId: String = WhisperModelCatalog.defaultOption.id,
    val vadStartDelaySeconds: Float = DEFAULT_VAD_START_DELAY_SECONDS,
    val vadStopDelaySeconds: Float = DEFAULT_VAD_STOP_DELAY_SECONDS,
    val preferBluetoothAutomatically: Boolean = true,
    val autoConnectNearbyFriends: Boolean = false,
    val presenceUpdateIntervalSeconds: Int = DEFAULT_PRESENCE_UPDATE_INTERVAL_SECONDS
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
            preferences.nativeSpeechLanguageTag.collect { saved ->
                val tag = NativeSpeechLanguageCatalog.normalizeTag(saved)
                _uiState.update { it.copy(nativeSpeechLanguageTag = tag) }
            }
        }
        viewModelScope.launch {
            preferences.whisperModelId.collect { saved ->
                val modelId = WhisperModelCatalog.findById(saved ?: "")?.id ?: return@collect
                _uiState.update { it.copy(whisperModelId = modelId) }
            }
        }
        viewModelScope.launch {
            preferences.vadStartDelaySeconds.collect { saved ->
                if (saved != null) {
                    _uiState.update { it.copy(vadStartDelaySeconds = normalizeDelaySeconds(saved)) }
                }
            }
        }
        viewModelScope.launch {
            preferences.vadStopDelaySeconds.collect { saved ->
                if (saved != null) {
                    _uiState.update { it.copy(vadStopDelaySeconds = normalizeDelaySeconds(saved)) }
                }
            }
        }
        viewModelScope.launch {
            preferences.preferBluetoothAutomatically.collect { enabled ->
                _uiState.update { it.copy(preferBluetoothAutomatically = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.autoConnectNearbyFriends.collect { saved ->
                if (saved != null) {
                    _uiState.update { it.copy(autoConnectNearbyFriends = saved) }
                }
            }
        }
        viewModelScope.launch {
            preferences.presenceUpdateIntervalSeconds.collect { saved ->
                val normalized = normalizePresenceIntervalSeconds(saved ?: DEFAULT_PRESENCE_UPDATE_INTERVAL_SECONDS)
                _uiState.update { it.copy(presenceUpdateIntervalSeconds = normalized) }
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

    fun onNativeSpeechLanguageChange(languageTag: String) {
        val normalized = NativeSpeechLanguageCatalog.normalizeTag(languageTag)
        _uiState.update { it.copy(nativeSpeechLanguageTag = normalized) }
        viewModelScope.launch {
            preferences.setNativeSpeechLanguageTag(normalized)
        }
    }

    fun onWhisperModelChange(modelId: String) {
        val model = WhisperModelCatalog.findById(modelId) ?: return
        _uiState.update { it.copy(whisperModelId = model.id) }
        viewModelScope.launch {
            preferences.setWhisperModelId(model.id)
        }
    }

    fun onVadStartDelayChange(seconds: Float) {
        val normalized = normalizeDelaySeconds(seconds)
        _uiState.update { it.copy(vadStartDelaySeconds = normalized) }
        viewModelScope.launch {
            preferences.setVadStartDelaySeconds(normalized)
        }
    }

    fun onVadStopDelayChange(seconds: Float) {
        val normalized = normalizeDelaySeconds(seconds)
        _uiState.update { it.copy(vadStopDelaySeconds = normalized) }
        viewModelScope.launch {
            preferences.setVadStopDelaySeconds(normalized)
        }
    }

    fun onPreferBluetoothAutoChange(enabled: Boolean) {
        _uiState.update { it.copy(preferBluetoothAutomatically = enabled) }
        viewModelScope.launch {
            preferences.setPreferBluetoothAutomatically(enabled)
        }
    }

    fun onAutoConnectNearbyFriendsChange(enabled: Boolean) {
        _uiState.update { it.copy(autoConnectNearbyFriends = enabled) }
        viewModelScope.launch {
            preferences.setAutoConnectNearbyFriends(enabled)
        }
    }

    fun onPresenceUpdateIntervalChange(seconds: Int) {
        val normalized = normalizePresenceIntervalSeconds(seconds)
        _uiState.update { it.copy(presenceUpdateIntervalSeconds = normalized) }
        viewModelScope.launch {
            preferences.setPresenceUpdateIntervalSeconds(normalized)
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

    private fun normalizeDelaySeconds(seconds: Float): Float {
        val clamped = seconds.coerceIn(MIN_VAD_DELAY_SECONDS, MAX_VAD_DELAY_SECONDS)
        return (clamped * 10f).roundToInt() / 10f
    }

    private fun normalizePresenceIntervalSeconds(seconds: Int): Int {
        return seconds.coerceIn(
            minimumValue = MIN_PRESENCE_UPDATE_INTERVAL_SECONDS,
            maximumValue = MAX_PRESENCE_UPDATE_INTERVAL_SECONDS
        )
    }
}
