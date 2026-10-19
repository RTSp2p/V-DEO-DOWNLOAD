package com.example.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.CapturedVideoEntity
import com.example.ui.player.InternalVideoPlayer
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentRed
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val allVideos by viewModel.allVideos.collectAsStateWithLifecycle()
    val downloadingVideos by viewModel.downloadingVideos.collectAsStateWithLifecycle()
    val completedVideos by viewModel.completedVideos.collectAsStateWithLifecycle()
    val failedVideos by viewModel.failedVideos.collectAsStateWithLifecycle()
    val selectedVideoIds by viewModel.selectedVideoIds.collectAsStateWithLifecycle()
    val isSettingsDialogVisible by viewModel.isSettingsDialogVisible.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val downloadFolderUri by viewModel.downloadFolderUri.collectAsStateWithLifecycle()
    val activePlayingVideo by viewModel.activePlayingVideo.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF OpenDocumentTree Launcher para seleção de pasta de download personalizada
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

    LaunchedEffect(Unit) {
        viewModel.userMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val currentList = when (selectedTab) {
        HomeTab.ALL -> allVideos
        HomeTab.DOWNLOADING -> downloadingVideos
        HomeTab.COMPLETED -> completedVideos
        HomeTab.FAILED -> failedVideos
    }

    val allCurrentTabSelected = currentList.isNotEmpty() &&
            currentList.all { selectedVideoIds.contains(it.id) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                // Header Title + Settings Gear IconButton
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Central de Downloads",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text(
                                text = "${allVideos.size} tarefas registradas",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Ícone de engrenagem (Settings) na barra superior da Home
                    IconButton(
                        onClick = { viewModel.openSettingsDialog() },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("home_settings_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configurações",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // PrimaryTabRow com 4 abas: TODOS, BAIXANDO, COMPLETOS, ERROS
                PrimaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = {
                        TabRowDefaults.PrimaryIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.tabIndicatorOffset(selectedTab.ordinal)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HomeTab.entries.forEach { tab ->
                        val count = when (tab) {
                            HomeTab.ALL -> allVideos.size
                            HomeTab.DOWNLOADING -> downloadingVideos.size
                            HomeTab.COMPLETED -> completedVideos.size
                            HomeTab.FAILED -> failedVideos.size
                        }

                        Tab(
                            selected = selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = tab.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTab == tab) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (count > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge(
                                            containerColor = when (tab) {
                                                HomeTab.DOWNLOADING -> MaterialTheme.colorScheme.primary
                                                HomeTab.COMPLETED -> MaterialTheme.colorScheme.secondary
                                                HomeTab.FAILED -> AccentRed
                                                HomeTab.ALL -> MaterialTheme.colorScheme.outline
                                            },
                                            contentColor = when (tab) {
                                                HomeTab.DOWNLOADING -> MaterialTheme.colorScheme.onPrimary
                                                else -> Color.White
                                            }
                                        ) {
                                            Text(text = "$count", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.testTag("home_tab_${tab.name}")
                        )
                    }
                }

                // Barra de Seleção em Lote e Limpeza de Histórico (visível quando há itens na aba atual)
                AnimatedVisibility(visible = currentList.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Botão / Checkbox "Selecionar Todos"
                            TextButton(
                                onClick = { viewModel.toggleSelectAllCurrentTab() },
                                modifier = Modifier.testTag("home_select_all_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SelectAll,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (allCurrentTabSelected) "Desmarcar Todos" else "Selecionar Todos",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Botão "Limpar do Histórico" para os cards selecionados
                            Button(
                                onClick = { viewModel.clearSelectedFromHistory() },
                                enabled = selectedVideoIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentRed,
                                    contentColor = Color.White,
                                    disabledContainerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("home_clear_history_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (selectedVideoIds.isNotEmpty()) {
                                        "Limpar do Histórico (${selectedVideoIds.size})"
                                    } else {
                                        "Limpar do Histórico"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
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
            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (selectedTab) {
                                    HomeTab.DOWNLOADING -> Icons.Default.Download
                                    HomeTab.COMPLETED -> Icons.Default.CheckCircle
                                    HomeTab.FAILED -> Icons.Default.Error
                                    HomeTab.ALL -> Icons.Default.Movie
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when (selectedTab) {
                                HomeTab.DOWNLOADING -> "Nenhum download em andamento"
                                HomeTab.COMPLETED -> "Nenhum download concluído ainda"
                                HomeTab.FAILED -> "Nenhum erro registrado"
                                HomeTab.ALL -> "Nenhum item na fila"
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Abra o Navegador para capturar e baixar novos vídeos.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentList, key = { it.id }) { video ->
                        val isSelected = selectedVideoIds.contains(video.id)
                        HomeDownloadCard(
                            video = video,
                            isSelected = isSelected,
                            onToggleSelect = { viewModel.toggleVideoSelection(video.id) },
                            onWatch = { viewModel.playVideo(video) },
                            onCancel = { viewModel.cancelDownload(video) },
                            onRetry = { viewModel.retryDownload(video) },
                            onDelete = { viewModel.deleteVideo(video) }
                        )
                    }
                }
            }
        }
    }

    // Diálogo de Configurações (Tema e Pasta de Download SAF)
    if (isSettingsDialogVisible) {
        SettingsDialog(
            currentThemeMode = themeMode,
            downloadFolderUri = downloadFolderUri,
            onSelectThemeMode = { mode -> viewModel.setThemeMode(mode) },
            onSelectDownloadFolder = {
                val initialUri = downloadFolderUri?.let {
                    try {
                        Uri.parse(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                openDocumentTreeLauncher.launch(initialUri)
            },
            onResetDownloadFolder = { viewModel.setDownloadFolderUri(null) },
            onDismiss = { viewModel.closeSettingsDialog() }
        )
    }

    // Video Player se o usuário clicar em "Assistir" a partir da Home
    activePlayingVideo?.let { playingVideo ->
        val uriToPlay = playingVideo.localUri ?: playingVideo.videoUrl
        InternalVideoPlayer(
            videoUriString = uriToPlay,
            videoTitle = playingVideo.fileName,
            onClose = { viewModel.closePlayer() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeDownloadCard(
    video: CapturedVideoEntity,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onWatch: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDownloading = video.downloadStatus == CapturedVideoEntity.STATUS_DOWNLOADING
    val isCompleted = video.downloadStatus == CapturedVideoEntity.STATUS_COMPLETED
    val isFailed = video.downloadStatus == CapturedVideoEntity.STATUS_FAILED

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { onToggleSelect() },
                onLongClick = { onToggleSelect() }
            )
            .testTag("home_card_${video.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox para seleção individual ou em lote
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("home_card_checkbox_${video.id}")
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Media Icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isDownloading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                isCompleted -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                isFailed -> AccentRed.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.outline
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isDownloading -> Icons.Default.Download
                            isCompleted -> Icons.Default.Movie
                            isFailed -> Icons.Default.Error
                            else -> Icons.Default.HourglassEmpty
                        },
                        contentDescription = null,
                        tint = when {
                            isDownloading -> MaterialTheme.colorScheme.primary
                            isCompleted -> MaterialTheme.colorScheme.secondary
                            isFailed -> AccentRed
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.fileName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Badge Formato
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = video.fileType.uppercase(),
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Status Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when {
                                        isDownloading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        isCompleted -> AccentGreen.copy(alpha = 0.2f)
                                        isFailed -> AccentRed.copy(alpha = 0.2f)
                                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when (video.downloadStatus) {
                                    CapturedVideoEntity.STATUS_DOWNLOADING -> "BAIXANDO"
                                    CapturedVideoEntity.STATUS_COMPLETED -> "COMPLETO"
                                    CapturedVideoEntity.STATUS_FAILED -> "FALHOU"
                                    else -> "DETECTADO"
                                },
                                color = when {
                                    isDownloading -> MaterialTheme.colorScheme.primary
                                    isCompleted -> AccentGreen
                                    isFailed -> AccentRed
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = formatFileSize(video.fileSizeBytes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Barra de Progresso quando estiver baixando
            if (isDownloading) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(video.capturedAt)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isDownloading) {
                        Button(
                            onClick = onCancel,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentRed.copy(alpha = 0.15f),
                                contentColor = AccentRed
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("cancel_btn_${video.id}")
                        ) {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancelar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isFailed) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("retry_btn_${video.id}")
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Tentar Novamente", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.outline)
                                .testTag("delete_failed_btn_${video.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remover",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (isCompleted) {
                        Button(
                            onClick = onWatch,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("watch_btn_${video.id}")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Assistir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Tamanho pendente"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        else -> String.format(Locale.getDefault(), "%.0f KB", kb)
    }
}
