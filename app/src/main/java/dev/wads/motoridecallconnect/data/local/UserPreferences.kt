package dev.wads.motoridecallconnect.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val NATIVE_SPEECH_LANGUAGE_TAG = stringPreferencesKey("native_speech_language_tag")
        val WHISPER_MODEL_ID = stringPreferencesKey("whisper_model_id")
        val VAD_START_DELAY_SECONDS = floatPreferencesKey("vad_start_delay_seconds")
        val VAD_STOP_DELAY_SECONDS = floatPreferencesKey("vad_stop_delay_seconds")
        val AUTO_CONNECT_NEARBY_FRIENDS = booleanPreferencesKey("auto_connect_nearby_friends")
        val PRESENCE_UPDATE_INTERVAL_SECONDS = intPreferencesKey("presence_update_interval_seconds")
        val MICROPHONE_GAIN = floatPreferencesKey("microphone_gain")
        val OUTPUT_VOLUME_RATIO = floatPreferencesKey("output_volume_ratio")
        val TRANSMISSION_BITRATE_KBPS = intPreferencesKey("transmission_bitrate_kbps")
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

    val nativeSpeechLanguageTag: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[NATIVE_SPEECH_LANGUAGE_TAG]
        }

    val whisperModelId: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[WHISPER_MODEL_ID]
        }

    val vadStartDelaySeconds: Flow<Float?> = context.dataStore.data
        .map { preferences ->
            preferences[VAD_START_DELAY_SECONDS]
        }

    val vadStopDelaySeconds: Flow<Float?> = context.dataStore.data
        .map { preferences ->
            preferences[VAD_STOP_DELAY_SECONDS]
        }

    val autoConnectNearbyFriends: Flow<Boolean?> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_CONNECT_NEARBY_FRIENDS]
        }

    val presenceUpdateIntervalSeconds: Flow<Int?> = context.dataStore.data
        .map { preferences ->
            preferences[PRESENCE_UPDATE_INTERVAL_SECONDS]
        }

    val microphoneGain: Flow<Float?> = context.dataStore.data
        .map { preferences ->
            preferences[MICROPHONE_GAIN]
        }

    val outputVolumeRatio: Flow<Float?> = context.dataStore.data
        .map { preferences ->
            preferences[OUTPUT_VOLUME_RATIO]
        }

    val transmissionBitrateKbps: Flow<Int?> = context.dataStore.data
        .map { preferences ->
            preferences[TRANSMISSION_BITRATE_KBPS]
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

    suspend fun setNativeSpeechLanguageTag(tag: String) {
        context.dataStore.edit { preferences ->
            preferences[NATIVE_SPEECH_LANGUAGE_TAG] = tag
        }
    }

    suspend fun setWhisperModelId(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[WHISPER_MODEL_ID] = modelId
        }
    }

    suspend fun setVadStartDelaySeconds(seconds: Float) {
        context.dataStore.edit { preferences ->
            preferences[VAD_START_DELAY_SECONDS] = seconds
        }
    }

    suspend fun setVadStopDelaySeconds(seconds: Float) {
        context.dataStore.edit { preferences ->
            preferences[VAD_STOP_DELAY_SECONDS] = seconds
        }
    }

    suspend fun setAutoConnectNearbyFriends(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CONNECT_NEARBY_FRIENDS] = enabled
        }
    }

    suspend fun setPresenceUpdateIntervalSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PRESENCE_UPDATE_INTERVAL_SECONDS] = seconds
        }
    }

    suspend fun setMicrophoneGain(gain: Float) {
        context.dataStore.edit { preferences ->
            preferences[MICROPHONE_GAIN] = gain
        }
    }

    suspend fun setOutputVolumeRatio(ratio: Float) {
        context.dataStore.edit { preferences ->
            preferences[OUTPUT_VOLUME_RATIO] = ratio
        }
    }

    suspend fun setTransmissionBitrateKbps(kbps: Int) {
        context.dataStore.edit { preferences ->
            preferences[TRANSMISSION_BITRATE_KBPS] = kbps
        }
    }
}
