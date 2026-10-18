package com.example.data

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("browser_session_prefs", Context.MODE_PRIVATE)

    fun saveLastUrl(url: String) {
        if (url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("chrome:")) {
            prefs.edit().putString(KEY_LAST_URL, url).apply()
        }
    }

    fun getLastUrl(): String? {
        return prefs.getString(KEY_LAST_URL, null)
    }

    companion object {
        private const val KEY_LAST_URL = "last_visited_url"
    }
}
