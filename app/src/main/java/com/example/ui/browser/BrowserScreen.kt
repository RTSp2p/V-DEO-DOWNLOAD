package com.example.ui.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.example.browser.VideoSnifferJsBridge
import com.example.browser.VideoSnifferWebViewClient
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.AmoledOutline
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collectLatest

private data class QuickBookmark(
    val title: String,
    val url: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detectedVideos by viewModel.detectedVideos.collectAsStateWithLifecycle()
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isBatchSheetVisible by viewModel.isBatchSheetVisible.collectAsStateWithLifecycle()
    val isSnifferEnabled by viewModel.isSnifferEnabled.collectAsStateWithLifecycle()
    val browsingHistory by viewModel.browsingHistory.collectAsStateWithLifecycle()
    val isHistoryDialogVisible by viewModel.isHistoryDialogVisible.collectAsStateWithLifecycle()

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var urlInputText by remember { mutableStateOf(currentUrl) }
    var isShowingStartPage by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    // Bundle para salvar e restaurar o estado da WebView
    val webViewStateBundle = rememberSaveable { Bundle() }

    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Listen to messages
    LaunchedEffect(Unit) {
        viewModel.userMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(currentUrl) {
        if (!isShowingStartPage) {
            urlInputText = currentUrl
        }
    }

    // Handle Hardware / Gesture Back for WebView
    BackHandler(enabled = webViewInstance?.canGoBack() == true) {
        webViewInstance?.goBack()
    }

    // Salvar estado ao sair
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.saveState(webViewStateBundle)
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
        webViewInstance?.loadUrl(finalUrl)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmoledSurface)
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
                            tint = if (webViewInstance?.canGoBack() == true) NeonCyan else TextSecondary.copy(alpha = 0.35f),
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
                            tint = if (webViewInstance?.canGoForward() == true) NeonCyan else TextSecondary.copy(alpha = 0.35f),
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
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("browser_reload_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Recarregar",
                            tint = NeonCyan,
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
                                color = TextSecondary,
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
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AmoledSurfaceVariant,
                            unfocusedContainerColor = AmoledSurfaceVariant.copy(alpha = 0.6f),
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = AmoledOutline,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Menu de Opções (Três Pontos no Canto Superior Direito)
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
                                tint = NeonCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            modifier = Modifier.background(AmoledSurfaceVariant)
                        ) {
                            // 1. "Começar Captura" (Checkbox toggle)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = isSnifferEnabled,
                                            onCheckedChange = { viewModel.setSnifferEnabled(it) },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = NeonCyan,
                                                checkmarkColor = Color.Black,
                                                uncheckedColor = TextSecondary
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Começar Captura",
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setSnifferEnabled(!isSnifferEnabled)
                                },
                                modifier = Modifier.testTag("toggle_sniffer_menu_item")
                            )

                            // 2. "Voltar para Home" (sem destruir a WebView)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Home,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Voltar para Home", color = TextPrimary, fontSize = 14.sp)
                                    }
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    onNavigateToHome()
                                },
                                modifier = Modifier.testTag("navigate_home_menu_item")
                            )

                            // 3. "Histórico" (últimos 30 sites visitados)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = NeonPurple,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Histórico", color = TextPrimary, fontSize = 14.sp)
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
                        color = NeonCyan,
                        trackColor = AmoledOutline,
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
                            containerColor = NeonPurple,
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
                    containerColor = if (detectedVideos.isNotEmpty()) NeonCyan else AmoledSurfaceVariant,
                    contentColor = if (detectedVideos.isNotEmpty()) Color.Black else TextSecondary,
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
                .background(AmoledBlack)
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
                                Brush.linearGradient(listOf(NeonCyan, NeonPurple))
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
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "Navegue e capture vídeos MP4, WebM e MKV instantaneamente",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Atalhos Rápidos de Teste",
                        color = NeonCyan,
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
                                colors = CardDefaults.cardColors(containerColor = AmoledSurfaceVariant),
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
                                        color = TextPrimary,
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
                                userAgentString = VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                useWideViewPort = true
                                loadWithOverviewMode = true
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
                                    viewModel.onVideoSniffed(url, fileName, ext)
                                }
                            )
                            addJavascriptInterface(jsBridge, VideoSnifferJsBridge.INTERFACE_NAME)
                            addJavascriptInterface(jsBridge, VideoSnifferJsBridge.BRIDGE_ALIAS_NAME)

                            webViewClient = VideoSnifferWebViewClient(
                                onVideoDetected = { url, fileName, ext ->
                                    viewModel.onVideoSniffed(url, fileName, ext)
                                },
                                onPageStartedCallback = { url ->
                                    viewModel.setLoading(true)
                                    urlInputText = url
                                    viewModel.updateUrl(url)
                                    viewModel.updateWebViewContext(url, settings.userAgentString)
                                },
                                onPageFinishedCallback = { url, title ->
                                    viewModel.setLoading(false)
                                    viewModel.setPageTitle(title)
                                    urlInputText = url
                                    viewModel.updateWebViewContext(url, settings.userAgentString)
                                }
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    viewModel.setProgress(newProgress)
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    viewModel.setPageTitle(title)
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
            onToggleSelect = { id -> viewModel.toggleVideoSelection(id) },
            onToggleSelectAll = { viewModel.toggleSelectAll() },
            onDownloadSingle = { video -> viewModel.startSingleDownload(video) },
            onDownloadBatch = { viewModel.startBatchDownload() },
            onClearAll = { viewModel.clearDetected() },
            onCancelActiveDownloads = { viewModel.cancelActiveDownloads() },
            onDismiss = { viewModel.closeBatchSheet() }
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
}
