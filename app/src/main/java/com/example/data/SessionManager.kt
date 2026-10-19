package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "browser_session_datastore"
)

class SessionManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val lastUrlFlow: Flow<String?> = context.sessionDataStore.data.map { prefs ->
        prefs[KEY_LAST_URL]
    }

    fun saveLastUrl(url: String) {
        if (url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("chrome:")) {
            scope.launch {
                context.sessionDataStore.edit { prefs ->
                    prefs[KEY_LAST_URL] = url
                }
            }
        }
    }

    fun getLastUrl(): String? {
        return try {
            runBlocking(Dispatchers.IO) {
                context.sessionDataStore.data.map { it[KEY_LAST_URL] }.firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val KEY_LAST_URL = stringPreferencesKey("last_visited_url")
    }
}
