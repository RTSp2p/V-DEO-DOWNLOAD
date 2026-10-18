package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.data.SessionManager
import com.example.ui.browser.BrowserViewModel
import com.example.ui.downloads.DownloadsViewModel
import com.example.ui.home.HomeViewModel
import com.example.ui.main.MainScreen
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as VideoHunterApplication
        val repository = app.repository
        val sessionManager = SessionManager(applicationContext)

        val homeViewModel: HomeViewModel by viewModels {
            HomeViewModel.Factory(repository)
        }

        val browserViewModel: BrowserViewModel by viewModels {
            BrowserViewModel.Factory(repository, sessionManager)
        }

        val downloadsViewModel: DownloadsViewModel by viewModels {
            DownloadsViewModel.Factory(repository)
        }

        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AmoledBlack
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
