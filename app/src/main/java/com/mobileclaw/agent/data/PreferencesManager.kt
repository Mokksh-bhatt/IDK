package com.mobileclaw.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mobileclaw_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val API_KEY = stringPreferencesKey("openrouter_api_key")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val CAPTURE_INTERVAL = intPreferencesKey("capture_interval_ms")
    }

    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[API_KEY] ?: ""
    }

    val maxSteps: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[MAX_STEPS] ?: 50
    }

    val captureInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[CAPTURE_INTERVAL] ?: 2000
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = key
        }
    }

    suspend fun setMaxSteps(steps: Int) {
        context.dataStore.edit { prefs ->
            prefs[MAX_STEPS] = steps
        }
    }

    suspend fun setCaptureInterval(ms: Int) {
        context.dataStore.edit { prefs ->
            prefs[CAPTURE_INTERVAL] = ms
        }
    }
}
