package com.docpilot.qwen.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "docpilot_settings")

class SettingsStore(private val context: Context) {
    val cloudEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CLOUD_ENABLED] ?: false
    }

    val defaultLocalMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DEFAULT_LOCAL_MODE] ?: true
    }

    val autoClearClipboardAndCache: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_CLEAR_CLIPBOARD_AND_CACHE] ?: true
    }

    val performanceMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PERFORMANCE_MODE] ?: "均衡模式"
    }

    val parseThreadCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[PARSE_THREAD_COUNT] ?: 4
    }

    val accelerationEngine: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ACCELERATION_ENGINE] ?: "系统 IO"
    }

    val cloudModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CLOUD_MODEL] ?: "qwen3.6-plus"
    }

    suspend fun setCloudEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[CLOUD_ENABLED] = enabled
        }
    }

    suspend fun setDefaultLocalMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DEFAULT_LOCAL_MODE] = enabled
        }
    }

    suspend fun setAutoClearClipboardAndCache(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_CLEAR_CLIPBOARD_AND_CACHE] = enabled
        }
    }

    suspend fun setPerformanceMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[PERFORMANCE_MODE] = mode
        }
    }

    suspend fun setParseThreadCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[PARSE_THREAD_COUNT] = count.coerceIn(1, 8)
        }
    }

    suspend fun setAccelerationEngine(engine: String) {
        context.dataStore.edit { prefs ->
            prefs[ACCELERATION_ENGINE] = engine
        }
    }

    suspend fun setCloudModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[CLOUD_MODEL] = model
        }
    }

    companion object {
        private val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        private val DEFAULT_LOCAL_MODE = booleanPreferencesKey("default_local_mode")
        private val AUTO_CLEAR_CLIPBOARD_AND_CACHE = booleanPreferencesKey("auto_clear_clipboard_and_cache")
        private val PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
        private val PARSE_THREAD_COUNT = intPreferencesKey("parse_thread_count")
        private val ACCELERATION_ENGINE = stringPreferencesKey("acceleration_engine")
        private val CLOUD_MODEL = stringPreferencesKey("cloud_model")
    }
}
