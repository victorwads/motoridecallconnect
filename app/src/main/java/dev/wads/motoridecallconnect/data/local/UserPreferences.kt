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
}
