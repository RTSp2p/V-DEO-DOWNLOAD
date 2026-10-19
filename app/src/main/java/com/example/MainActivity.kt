package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SessionManager
import com.example.data.repository.ThemeMode
import com.example.ui.browser.BrowserViewModel
import com.example.ui.downloads.DownloadsViewModel
import com.example.ui.home.HomeViewModel
import com.example.ui.main.MainScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as VideoHunterApplication
        val repository = app.repository
        val settingsRepository = app.settingsRepository
        val sessionManager = SessionManager(applicationContext)

        val homeViewModel: HomeViewModel by viewModels {
            HomeViewModel.Factory(repository, settingsRepository)
        }

        val browserViewModel: BrowserViewModel by viewModels {
            BrowserViewModel.Factory(repository, sessionManager, settingsRepository)
        }

        val downloadsViewModel: DownloadsViewModel by viewModels {
            DownloadsViewModel.Factory(repository)
        }

        setContent {
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.DARK
            )

            MyApplicationTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        homeViewModel = homeViewModel,
                        browserViewModel = browserViewModel,
                        downloadsViewModel = downloadsViewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
