package com.example.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.browser.BrowserScreen
import com.example.ui.browser.BrowserViewModel
import com.example.ui.downloads.DownloadsScreen
import com.example.ui.downloads.DownloadsViewModel
import com.example.ui.theme.AmoledOutline
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

enum class NavTab {
    BROWSER,
    DOWNLOADS
}

@Composable
fun MainScreen(
    browserViewModel: BrowserViewModel,
    downloadsViewModel: DownloadsViewModel,
    modifier: Modifier = Modifier
) {
    var currentTab by rememberSaveable { mutableStateOf(NavTab.BROWSER) }

    // Request POST_NOTIFICATIONS runtime permission on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Handled gracefully */ }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Custom back handler: If in Downloads, return to Browser
    BackHandler(enabled = currentTab == NavTab.DOWNLOADS) {
        currentTab = NavTab.BROWSER
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = AmoledSurface,
                contentColor = TextPrimary,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier
                    .background(AmoledSurface)
                    .testTag("main_bottom_nav_bar")
            ) {
                // Tab 1: Browser
                NavigationBarItem(
                    selected = currentTab == NavTab.BROWSER,
                    onClick = { currentTab = NavTab.BROWSER },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavTab.BROWSER) {
                                Icons.Filled.Language
                            } else {
                                Icons.Outlined.Language
                            },
                            contentDescription = stringResource(R.string.nav_browser)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.nav_browser),
                            fontSize = 12.sp,
                            fontWeight = if (currentTab == NavTab.BROWSER) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("tab_browser")
                )

                // Tab 2: Downloads Library
                NavigationBarItem(
                    selected = currentTab == NavTab.DOWNLOADS,
                    onClick = { currentTab = NavTab.DOWNLOADS },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavTab.DOWNLOADS) {
                                Icons.Filled.VideoLibrary
                            } else {
                                Icons.Outlined.VideoLibrary
                            },
                            contentDescription = stringResource(R.string.nav_downloads)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.nav_downloads),
                            fontSize = 12.sp,
                            fontWeight = if (currentTab == NavTab.DOWNLOADS) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = NeonPurple,
                        indicatorColor = NeonPurple,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("tab_downloads")
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = currentTab, label = "tab_crossfade") { tab ->
                when (tab) {
                    NavTab.BROWSER -> {
                        BrowserScreen(
                            viewModel = browserViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    NavTab.DOWNLOADS -> {
                        DownloadsScreen(
                            viewModel = downloadsViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
