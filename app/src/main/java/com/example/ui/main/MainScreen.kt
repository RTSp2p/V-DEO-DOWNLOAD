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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
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
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel

enum class NavTab {
    HOME,
    BROWSER,
    LIBRARY
}

@Composable
fun MainScreen(
    homeViewModel: HomeViewModel,
    browserViewModel: BrowserViewModel,
    downloadsViewModel: DownloadsViewModel,
    modifier: Modifier = Modifier
) {
    var currentTab by rememberSaveable { mutableStateOf(NavTab.HOME) }

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

    // Custom back handler: Se não estiver na Home, volta para a Home
    BackHandler(enabled = currentTab != NavTab.HOME) {
        currentTab = NavTab.HOME
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .testTag("main_bottom_nav_bar")
            ) {
                // Destino 1: Home
                NavigationBarItem(
                    selected = currentTab == NavTab.HOME,
                    onClick = { currentTab = NavTab.HOME },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavTab.HOME) {
                                Icons.Filled.Home
                            } else {
                                Icons.Outlined.Home
                            },
                            contentDescription = stringResource(R.string.nav_home)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.nav_home),
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == NavTab.HOME) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_home")
                )

                // Destino 2: Navegador
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
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == NavTab.BROWSER) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_browser")
                )

                // Destino 3: Biblioteca
                NavigationBarItem(
                    selected = currentTab == NavTab.LIBRARY,
                    onClick = { currentTab = NavTab.LIBRARY },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavTab.LIBRARY) {
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
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == NavTab.LIBRARY) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = MaterialTheme.colorScheme.secondary,
                        indicatorColor = MaterialTheme.colorScheme.secondary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_library")
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
            Crossfade(targetState = currentTab, label = "main_tab_crossfade") { tab ->
                when (tab) {
                    NavTab.HOME -> {
                        HomeScreen(
                            viewModel = homeViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    NavTab.BROWSER -> {
                        BrowserScreen(
                            viewModel = browserViewModel,
                            onNavigateToHome = { currentTab = NavTab.HOME },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    NavTab.LIBRARY -> {
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
