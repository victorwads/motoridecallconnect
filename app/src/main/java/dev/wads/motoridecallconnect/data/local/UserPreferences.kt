package dev.wads.motoridecallconnect.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferences(private val context: Context) {
    companion object {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val USAGE_MODE = stringPreferencesKey("usage_mode")
        val LOCAL_MODE = booleanPreferencesKey("local_mode")
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val START_COMMAND = stringPreferencesKey("start_command")
        val STOP_COMMAND = stringPreferencesKey("stop_command")
        val RECORD_TRANSCRIPT = booleanPreferencesKey("record_transcript")
        val STT_ENGINE = stringPreferencesKey("stt_engine")
        val WHISPER_MODEL_ID = stringPreferencesKey("whisper_model_id")
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ONBOARDING_COMPLETED] ?: false
        }

    val usageMode: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[USAGE_MODE]
        }

    val localMode: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[LOCAL_MODE] ?: false
        }

    val operatingMode: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[OPERATING_MODE]
        }

    val startCommand: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[START_COMMAND]
        }

    val stopCommand: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[STOP_COMMAND]
        }

    val recordTranscript: Flow<Boolean?> = context.dataStore.data
        .map { preferences ->
            preferences[RECORD_TRANSCRIPT]
        }

    val sttEngine: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[STT_ENGINE]
        }

    val whisperModelId: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[WHISPER_MODEL_ID]
        }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setUsageMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[USAGE_MODE] = mode
        }
    }

    suspend fun setLocalMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOCAL_MODE] = enabled
        }
    }

    suspend fun setOperatingMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[OPERATING_MODE] = mode
        }
    }

    suspend fun setStartCommand(command: String) {
        context.dataStore.edit { preferences ->
            preferences[START_COMMAND] = command
        }
    }

    suspend fun setStopCommand(command: String) {
        context.dataStore.edit { preferences ->
            preferences[STOP_COMMAND] = command
        }
    }

    suspend fun setRecordTranscript(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[RECORD_TRANSCRIPT] = enabled
        }
    }

    suspend fun setSttEngine(engine: String) {
        context.dataStore.edit { preferences ->
            preferences[STT_ENGINE] = engine
        }
    }

    suspend fun setWhisperModelId(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[WHISPER_MODEL_ID] = modelId
        }
    }
}
