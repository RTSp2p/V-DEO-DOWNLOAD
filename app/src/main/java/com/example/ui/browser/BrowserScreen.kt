package com.example.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.browser.AdBlocker
import com.example.browser.VideoSnifferJsBridge
import com.example.browser.VideoSnifferWebViewClient
import com.example.ui.player.InternalVideoPlayer
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import kotlinx.coroutines.flow.collectLatest

private data class QuickBookmark(
    val title: String,
    val url: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detectedVideos by viewModel.detectedVideos.collectAsStateWithLifecycle()
    val downloadFolderUri by viewModel.downloadFolderUri.collectAsStateWithLifecycle()
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isBatchSheetVisible by viewModel.isBatchSheetVisible.collectAsStateWithLifecycle()
    val isCaptureEnabled by viewModel.isCaptureEnabled.collectAsStateWithLifecycle()
    val browsingHistory by viewModel.browsingHistory.collectAsStateWithLifecycle()
    val isHistoryDialogVisible by viewModel.isHistoryDialogVisible.collectAsStateWithLifecycle()
    val qualityDialogState by viewModel.qualityDialogState.collectAsStateWithLifecycle()
    val previewPlayingVideo by viewModel.previewPlayingVideo.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val isTabsSheetVisible by viewModel.isTabsSheetVisible.collectAsStateWithLifecycle()
    val isDesktopMode by viewModel.isDesktopMode.collectAsStateWithLifecycle()
    val isAdBlockerEnabled by viewModel.isAdBlockerEnabled.collectAsStateWithLifecycle()

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var urlInputText by remember { mutableStateOf(currentUrl) }
    var isShowingStartPage by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Bundle para salvar e restaurar o estado da WebView no escopo da ViewModel
    val webViewStateBundle = viewModel.webViewStateBundle

    // SAF OpenDocumentTree Launcher para seleção de pasta de download a partir do menu da janela de vídeos detectados
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            viewModel.setDownloadFolderUri(uri.toString())
        }
    }

    // Listen to messages
    LaunchedEffect(Unit) {
        viewModel.userMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(currentUrl) {
        if (!isShowingStartPage && currentUrl != "about:blank") {
            urlInputText = currentUrl
        }
    }

    // Quando a captura é reativada pelo usuário, força uma nova varredura imediata na página atual
    LaunchedEffect(isCaptureEnabled) {
        if (isCaptureEnabled) {
            webViewInstance?.evaluateJavascript(
                "if (window.__1dmScanMedia) window.__1dmScanMedia();",
                null
            )
        }
    }

    // Handle Hardware / Gesture Back for WebView
    BackHandler(enabled = webViewInstance?.canGoBack() == true) {
        webViewInstance?.goBack()
    }

    // Salvar estado ao sair
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let { activeUrl ->
                viewModel.saveLastVisitedUrl(activeUrl)
            }
            webViewInstance?.saveState(webViewStateBundle)
        }
    }

    var lastHandledTabId by remember { mutableStateOf(activeTabId) }

    // Sincronização ao alternar entre abas abertas
    LaunchedEffect(activeTabId) {
        if (activeTabId != lastHandledTabId) {
            lastHandledTabId = activeTabId
            val activeTab = tabs.find { it.id == activeTabId }
            if (activeTab != null && webViewInstance != null) {
                urlInputText = activeTab.url
                webViewInstance?.settings?.apply {
                    userAgentString = if (activeTab.isDesktopMode) BrowserViewModel.DESKTOP_USER_AGENT else VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
                    useWideViewPort = activeTab.isDesktopMode
                    loadWithOverviewMode = activeTab.isDesktopMode
                }
                if (!activeTab.webStateBundle.isEmpty) {
                    webViewInstance?.restoreState(activeTab.webStateBundle)
                } else if (activeTab.url.isNotBlank() && activeTab.url != "about:blank") {
                    webViewInstance?.loadUrl(activeTab.url)
                }
            }
        }
    }

    val quickBookmarks = remember {
        listOf(
            QuickBookmark("HTML5 Test Mídia", "https://html5test.com", Icons.Default.Language, NeonCyan),
            QuickBookmark("W3Schools MP4 Demo", "https://www.w3schools.com/html/html5_video.asp", Icons.Default.Movie, NeonPurple),
            QuickBookmark("Wikimedia Commons", "https://commons.wikimedia.org/wiki/Category:WebM_videos", Icons.Default.Language, Color(0xFF38BDF8)),
            QuickBookmark("Internet Archive", "https://archive.org/details/movies", Icons.Default.Movie, Color(0xFFF59E0B)),
            QuickBookmark("Google Busca", "https://www.google.com", Icons.Default.Search, Color(0xFF10B981)),
            QuickBookmark("DuckDuckGo", "https://duckduckgo.com", Icons.Default.Search, Color(0xFFEC4899))
        )
    }

    fun navigateTo(rawTarget: String) {
        focusManager.clearFocus()
        val target = rawTarget.trim()
        val finalUrl = when {
            target.startsWith("http://") || target.startsWith("https://") -> target
            target.contains(".") && !target.contains(" ") -> "https://$target"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target, "UTF-8")
        }
        urlInputText = finalUrl
        isShowingStartPage = false
        viewModel.saveLastVisitedUrl(finalUrl)
        webViewInstance?.loadUrl(finalUrl)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                // Barra de Navegação Superior Integrada em Linha única:
                // [ < ] [ > ] [ ⟳ ] [ Omnibox URL / Busca ] [ ⋮ ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botão Voltar (canGoBack)
                    IconButton(
                        onClick = { webViewInstance?.goBack() },
                        enabled = webViewInstance?.canGoBack() == true,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("browser_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = if (webViewInstance?.canGoBack() == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Botão Avançar (canGoForward)
                    IconButton(
                        onClick = { webViewInstance?.goForward() },
                        enabled = webViewInstance?.canGoForward() == true,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("browser_forward_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Avançar",
                            tint = if (webViewInstance?.canGoForward() == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Botão Recarregar (reload)
                    IconButton(
                        onClick = {
                            if (isShowingStartPage) {
                                navigateTo(urlInputText.ifBlank { "https://html5test.com" })
                            } else {
                                webViewInstance?.reload()
                                webViewInstance?.evaluateJavascript(
                                    "if (window.__1dmScanMedia) window.__1dmScanMedia();",
                                    null
                                )
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("browser_reload_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Recarregar",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Omnibox URL / Campo de Pesquisa
                    OutlinedTextField(
                        value = urlInputText,
                        onValueChange = { urlInputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("browser_url_input"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.browser_search_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { navigateTo(urlInputText) }
                        ),
                        trailingIcon = {
                            if (urlInputText.isNotEmpty()) {
                                IconButton(onClick = { urlInputText = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Limpar",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // 1. Botão Alternador de Modo Computador / Mobile (Desktop User-Agent)
                    IconButton(
                        onClick = {
                            val newMode = viewModel.toggleDesktopMode()
                            webViewInstance?.settings?.apply {
                                userAgentString = if (newMode) BrowserViewModel.DESKTOP_USER_AGENT else VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
                                useWideViewPort = newMode
                                loadWithOverviewMode = newMode
                            }
                            webViewInstance?.reload()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("desktop_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (isDesktopMode) Icons.Default.DesktopWindows else Icons.Default.Smartphone,
                            contentDescription = if (isDesktopMode) "Modo Computador ativado (toque para alternar)" else "Modo Celular ativado (toque para alternar)",
                            tint = if (isDesktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 2. Botão Gerenciador de Abas com Contador Visual de Abas
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.testTag("tabs_badge_count")
                            ) {
                                Text(
                                    text = "${tabs.size}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    ) {
                        IconButton(
                            onClick = {
                                val bundle = Bundle()
                                webViewInstance?.saveState(bundle)
                                viewModel.saveActiveTabBundle(bundle)
                                viewModel.showTabsSheet()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("tabs_manager_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tab,
                                contentDescription = "Gerenciar abas (${tabs.size})",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 3. Menu de Opções Adicionais (Três Pontos)
                    Box {
                        IconButton(
                            onClick = { showOptionsMenu = true },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("browser_options_menu_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Mais opções",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            // Alternar AdBlocker
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = isAdBlockerEnabled,
                                            onCheckedChange = { viewModel.setAdBlockerEnabled(it) },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MaterialTheme.colorScheme.primary,
                                                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Bloqueador de Anúncios",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setAdBlockerEnabled(!isAdBlockerEnabled)
                                },
                                modifier = Modifier.testTag("toggle_adblocker_menu_item")
                            )

                            // Alternar Modo Desktop
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = isDesktopMode,
                                            onCheckedChange = {
                                                val newMode = viewModel.toggleDesktopMode()
                                                webViewInstance?.settings?.apply {
                                                    userAgentString = if (newMode) BrowserViewModel.DESKTOP_USER_AGENT else VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
                                                    useWideViewPort = newMode
                                                    loadWithOverviewMode = newMode
                                                }
                                                webViewInstance?.reload()
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MaterialTheme.colorScheme.primary,
                                                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Versão para Computador",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                },
                                onClick = {
                                    val newMode = viewModel.toggleDesktopMode()
                                    webViewInstance?.settings?.apply {
                                        userAgentString = if (newMode) BrowserViewModel.DESKTOP_USER_AGENT else VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
                                        useWideViewPort = newMode
                                        loadWithOverviewMode = newMode
                                    }
                                    webViewInstance?.reload()
                                },
                                modifier = Modifier.testTag("toggle_desktop_menu_item")
                            )

                            // "Começar Captura" (Checkbox toggle com persistência em DataStore)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = isCaptureEnabled,
                                            onCheckedChange = { viewModel.setCaptureEnabled(it) },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MaterialTheme.colorScheme.primary,
                                                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Começar Captura",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setCaptureEnabled(!isCaptureEnabled)
                                },
                                modifier = Modifier.testTag("toggle_sniffer_menu_item")
                            )

                            // "Voltar para Home" (sem destruir a WebView)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Home,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Voltar para Home",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp
                                        )
                                    }
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    onNavigateToHome()
                                },
                                modifier = Modifier.testTag("navigate_home_menu_item")
                            )

                            // "Histórico" (últimos 30 sites visitados)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Histórico",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp
                                        )
                                    }
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    viewModel.openHistoryDialog()
                                },
                                modifier = Modifier.testTag("view_history_menu_item")
                            )
                        }
                    }
                }

                // Loading Progress Bar
                AnimatedVisibility(visible = isLoading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                    )
                }
            }
        },
        floatingActionButton = {
            // Sniffer FAB with dynamic badge & pulse
            val infiniteTransition = rememberInfiniteTransition(label = "fab_pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = if (detectedVideos.isNotEmpty()) 1.08f else 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_scale"
            )

            BadgedBox(
                badge = {
                    if (detectedVideos.isNotEmpty()) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = Color.White,
                            modifier = Modifier
                                .padding(end = 4.dp, top = 4.dp)
                                .testTag("sniffer_badge_count")
                        ) {
                            Text(
                                text = "${detectedVideos.size}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            ) {
                FloatingActionButton(
                    onClick = { viewModel.openBatchSheet() },
                    containerColor = if (detectedVideos.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (detectedVideos.isNotEmpty()) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                    shape = CircleShape,
                    modifier = Modifier
                        .scale(pulseScale)
                        .testTag("sniffer_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Vídeos Detectados",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Start Page View (se acionada)
            if (isShowingStartPage) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "1DM Video Hunter",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "Navegue e capture vídeos MP4, WebM e MKV instantaneamente",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Atalhos Rápidos de Teste",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(quickBookmarks) { bookmark ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navigateTo(bookmark.url) }
                                    .testTag("bookmark_${bookmark.title.replace(" ", "_")}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(bookmark.color.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = bookmark.icon,
                                            contentDescription = null,
                                            tint = bookmark.color,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = bookmark.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Actual Native WebView
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                setSupportMultipleWindows(true)
                                javaScriptCanOpenWindowsAutomatically = false
                                userAgentString = if (isDesktopMode) BrowserViewModel.DESKTOP_USER_AGENT else VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                useWideViewPort = isDesktopMode
                                loadWithOverviewMode = isDesktopMode
                                builtInZoomControls = true
                                displayZoomControls = false
                            }

                            try {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                            } catch (_: Exception) {
                            }

                            viewModel.updateWebViewContext(
                                pageUrl = currentUrl,
                                userAgent = settings.userAgentString
                            )

                            val jsBridge = VideoSnifferJsBridge(
                                onVideoDetected = { url, fileName, ext ->
                                    if (viewModel.isCaptureEnabled.value) {
                                        viewModel.onVideoDetected(url, fileName, ext)
                                    }
                                },
                                onRichMediaDetected = { payload ->
                                    if (viewModel.isCaptureEnabled.value) {
                                        viewModel.onRichMediaDetected(payload)
                                    }
                                },
                                pageUrlProvider = { viewModel.currentUrl.value },
                                userAgentProvider = { settings.userAgentString }
                            )
                            addJavascriptInterface(jsBridge, VideoSnifferJsBridge.INTERFACE_NAME)
                            addJavascriptInterface(jsBridge, VideoSnifferJsBridge.BRIDGE_ALIAS_NAME)

                            webViewClient = VideoSnifferWebViewClient(
                                onVideoDetected = { url, fileName, ext ->
                                    if (viewModel.isCaptureEnabled.value) {
                                        viewModel.onVideoDetected(url, fileName, ext)
                                    }
                                },
                                onRichMediaDetected = { payload ->
                                    if (viewModel.isCaptureEnabled.value) {
                                        viewModel.onRichMediaDetected(payload)
                                    }
                                },
                                onPageStartedCallback = { startedUrl ->
                                    viewModel.setLoading(true)
                                    if (startedUrl != "about:blank" && startedUrl.isNotBlank()) {
                                        urlInputText = startedUrl
                                        viewModel.saveLastVisitedUrl(startedUrl)
                                        viewModel.updateWebViewContext(startedUrl, settings.userAgentString)
                                    }
                                },
                                onPageFinishedCallback = { finalUrl, title ->
                                    viewModel.setLoading(false)
                                    if (finalUrl != "about:blank" && finalUrl.isNotBlank()) {
                                        urlInputText = finalUrl
                                        viewModel.saveLastVisitedUrl(finalUrl)
                                        viewModel.updateWebViewContext(finalUrl, settings.userAgentString)
                                    }
                                    viewModel.setPageTitle(title)
                                },
                                isAdBlockerEnabledProvider = { isAdBlockerEnabled }
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    viewModel.setProgress(newProgress)
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    viewModel.setPageTitle(title)
                                }

                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    // Bloqueia aberturas não originadas por ação do usuário (popunders e ghost clicks)
                                    if (!isUserGesture) {
                                        return false
                                    }

                                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                                    if (transport != null) {
                                        val tempWebView = WebView(view?.context ?: return false)
                                        tempWebView.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                                val target = req?.url?.toString()
                                                if (!target.isNullOrBlank() && !AdBlocker.isAd(target)) {
                                                    val bundle = Bundle()
                                                    view.saveState(bundle)
                                                    viewModel.openNewTab(url = target, title = "Nova Aba", currentBundle = bundle)
                                                }
                                                return true
                                            }
                                        }
                                        transport.webView = tempWebView
                                        resultMsg.sendToTarget()
                                        return true
                                    }

                                    val href = view?.hitTestResult?.extra
                                    if (!href.isNullOrBlank() && !AdBlocker.isAd(href)) {
                                        val bundle = Bundle()
                                        view.saveState(bundle)
                                        viewModel.openNewTab(url = href, title = "Nova Aba", currentBundle = bundle)
                                        return true
                                    }

                                    return false
                                }

                                override fun onCloseWindow(window: WebView?) {
                                    super.onCloseWindow(window)
                                    val bundle = Bundle()
                                    webViewInstance?.saveState(bundle)
                                    viewModel.closeTab(activeTabId, bundle)
                                }
                            }

                            // Restaura o estado da sessão se disponível, ou carrega a última URL
                            if (!webViewStateBundle.isEmpty) {
                                restoreState(webViewStateBundle)
                            } else {
                                loadUrl(currentUrl)
                            }

                            webViewInstance = this
                        }
                    },
                    update = { view ->
                        webViewInstance = view
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Modal BottomSheet for Batch Downloads
    if (isBatchSheetVisible) {
        BatchDownloadSheet(
            detectedVideos = detectedVideos,
            selectedIds = selectedIds,
            downloadFolderUri = downloadFolderUri,
            onToggleSelect = { id -> viewModel.toggleVideoSelection(id) },
            onToggleSelectAll = { viewModel.toggleSelectAll() },
            onDownloadSingle = { video -> viewModel.startSingleDownload(video) },
            onPreviewVideo = { video -> viewModel.openPreviewPlayer(video) },
            onDownloadBatch = { viewModel.startBatchDownload() },
            onRemoveSingle = { video -> viewModel.removeDetectedVideo(video) },
            onRemoveSelected = { viewModel.removeSelectedDetectedVideos() },
            onClearAll = { viewModel.clearDetected() },
            onCancelActiveDownloads = { viewModel.cancelActiveDownloads() },
            onChangeDownloadFolder = {
                val initialUri = downloadFolderUri?.let {
                    try {
                        Uri.parse(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                openDocumentTreeLauncher.launch(initialUri)
            },
            onDismiss = { viewModel.closeBatchSheet() }
        )
    }

    // Seletor de Qualidade e Extração de Áudio (.m4a) / Alerta de DRM para Streams Adaptativas
    qualityDialogState?.let { dialogState ->
        StreamQualityDialog(
            video = dialogState.video,
            isLoadingManifest = dialogState.isLoading,
            isDrmProtected = dialogState.isDrmProtected,
            drmMessage = dialogState.drmMessage,
            options = dialogState.options,
            onConfirmOption = { option -> viewModel.confirmStreamQualityDownload(option) },
            onDismiss = { viewModel.dismissQualityDialog() }
        )
    }

    // Diálogo Modal de Histórico de Navegação (últimos 30 sites)
    if (isHistoryDialogVisible) {
        BrowsingHistoryDialog(
            historyList = browsingHistory,
            onSelectUrl = { selectedUrl ->
                viewModel.closeHistoryDialog()
                navigateTo(selectedUrl)
            },
            onClearHistory = {
                viewModel.clearBrowsingHistory()
            },
            onDismiss = {
                viewModel.closeHistoryDialog()
            }
        )
    }

    // Reprodutor Interno para Pré-visualização de Streams Online antes do Download
    previewPlayingVideo?.let { previewVideo ->
        InternalVideoPlayer(
            videoUriString = previewVideo.videoUrl,
            videoTitle = previewVideo.title.ifBlank { previewVideo.fileName },
            headers = previewVideo.headers,
            streamType = previewVideo.streamType,
            isOnlinePreview = true,
            onClose = { viewModel.closePreviewPlayer() }
        )
    }

    // Gerenciador Modal de Múltiplas Abas (Tabs Manager)
    if (isTabsSheetVisible) {
        TabsManagerSheet(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = { selectedId ->
                val bundle = Bundle()
                webViewInstance?.saveState(bundle)
                viewModel.selectTab(selectedId, bundle)
            },
            onCloseTab = { closedId ->
                val bundle = Bundle()
                webViewInstance?.saveState(bundle)
                viewModel.closeTab(closedId, bundle)
            },
            onNewTab = {
                val bundle = Bundle()
                webViewInstance?.saveState(bundle)
                viewModel.openNewTab(currentBundle = bundle)
            },
            onDismiss = { viewModel.hideTabsSheet() }
        )
    }
}
