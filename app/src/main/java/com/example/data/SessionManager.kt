package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "browser_session_datastore"
)

class SessionManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val lastUrlFlow: Flow<String?> = appContext.sessionDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            prefs[KEY_LAST_URL]?.takeIf { isValidPersistableUrl(it) }
        }

    val isCaptureEnabledFlow: Flow<Boolean> = appContext.sessionDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            prefs[KEY_IS_CAPTURE_ENABLED] ?: true
        }

    fun saveLastUrl(url: String) {
        val trimmed = url.trim()
        if (isValidPersistableUrl(trimmed)) {
            scope.launch {
                appContext.sessionDataStore.edit { prefs ->
                    prefs[KEY_LAST_URL] = trimmed
                }
            }
        }
    }

    fun getLastUrl(): String? {
        return try {
            runBlocking(Dispatchers.IO) {
                appContext.sessionDataStore.data
                    .map { it[KEY_LAST_URL] }
                    .firstOrNull()
                    ?.takeIf { isValidPersistableUrl(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveCaptureEnabled(enabled: Boolean) {
        scope.launch {
            appContext.sessionDataStore.edit { prefs ->
                prefs[KEY_IS_CAPTURE_ENABLED] = enabled
            }
        }
    }

    fun isCaptureEnabled(): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                appContext.sessionDataStore.data
                    .map { it[KEY_IS_CAPTURE_ENABLED] ?: true }
                    .firstOrNull() ?: true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun isValidPersistableUrl(url: String): Boolean {
        return url.isNotBlank() &&
                url != "about:blank" &&
                !url.startsWith("about:") &&
                !url.startsWith("chrome:") &&
                !url.startsWith("javascript:") &&
                !url.startsWith("data:")
    }

    companion object {
        private val KEY_LAST_URL = stringPreferencesKey("last_visited_url")
        private val KEY_IS_CAPTURE_ENABLED = booleanPreferencesKey("is_capture_enabled")
    }
}
