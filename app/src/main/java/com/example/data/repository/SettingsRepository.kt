package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.URLDecoder

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "video_hunter_settings"
)

enum class ThemeMode(val value: String, val label: String) {
    SYSTEM("SYSTEM", "Padrão do Sistema"),
    LIGHT("LIGHT", "Tema Claro"),
    DARK("DARK", "Tema Escuro");

    companion object {
        fun fromValue(value: String?): ThemeMode {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: DARK
        }
    }
}

class SettingsRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.settingsDataStore
) {

    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            ThemeMode.fromValue(preferences[KEY_THEME_MODE])
        }

    val downloadFolderUri: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_DOWNLOAD_FOLDER_URI]?.takeIf { it.isNotBlank() }
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = mode.value
        }
    }

    suspend fun setDownloadFolderUri(uriString: String?) {
        dataStore.edit { preferences ->
            if (uriString.isNullOrBlank()) {
                preferences.remove(KEY_DOWNLOAD_FOLDER_URI)
            } else {
                preferences[KEY_DOWNLOAD_FOLDER_URI] = uriString
            }
        }
    }

    suspend fun getDownloadFolderUriSnapshot(): String? {
        return try {
            downloadFolderUri.first()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")

        fun formatReadableFolderPath(uriString: String?): String {
            if (uriString.isNullOrBlank()) {
                return "Padrão (Downloads do Sistema)"
            }
            return try {
                val uri = Uri.parse(uriString)
                val treeDocId = DocumentsContract.getTreeDocumentId(uri)
                if (!treeDocId.isNullOrBlank()) {
                    val clean = treeDocId.replace("primary:", "Armazenamento Interno/")
                    clean
                } else {
                    URLDecoder.decode(uri.lastPathSegment ?: uriString, "UTF-8")
                }
            } catch (_: Exception) {
                uriString
            }
        }
    }
}
