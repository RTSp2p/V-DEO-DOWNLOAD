package com.example.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.AmoledOutline
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
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
    val activePlayingVideo by viewModel.activePlayingVideo.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmoledSurface)
                    .statusBarsPadding()
            ) {
                // Header Title
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(NeonCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Central de Downloads",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "${allVideos.size} tarefas registradas",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                // PrimaryTabRow com 4 abas: TODOS, BAIXANDO, COMPLETOS, ERROS
                PrimaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = AmoledSurface,
                    contentColor = NeonCyan,
                    indicator = {
                        TabRowDefaults.PrimaryIndicator(
                            color = NeonCyan,
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
                                        color = if (selectedTab == tab) NeonCyan else TextSecondary
                                    )
                                    if (count > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge(
                                            containerColor = when (tab) {
                                                HomeTab.DOWNLOADING -> NeonCyan
                                                HomeTab.COMPLETED -> NeonPurple
                                                HomeTab.FAILED -> AccentRed
                                                HomeTab.ALL -> AmoledOutline
                                            },
                                            contentColor = when (tab) {
                                                HomeTab.DOWNLOADING -> Color.Black
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
                                .background(AmoledSurfaceVariant),
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
                                tint = TextSecondary,
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
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Abra o Navegador para capturar e baixar novos vídeos.",
                            color = TextSecondary,
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
                        HomeDownloadCard(
                            video = video,
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

@Composable
private fun HomeDownloadCard(
    video: CapturedVideoEntity,
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
        colors = CardDefaults.cardColors(containerColor = AmoledSurfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
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
                // Media Icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isDownloading -> NeonCyan.copy(alpha = 0.15f)
                                isCompleted -> NeonPurple.copy(alpha = 0.15f)
                                isFailed -> AccentRed.copy(alpha = 0.15f)
                                else -> AmoledOutline
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
                            isDownloading -> NeonCyan
                            isCompleted -> NeonPurple
                            isFailed -> AccentRed
                            else -> TextSecondary
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.fileName,
                        color = TextPrimary,
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
                                .background(NeonPurple.copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = video.fileType.uppercase(),
                                color = NeonPurple,
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
                                        isDownloading -> NeonCyan.copy(alpha = 0.2f)
                                        isCompleted -> AccentGreen.copy(alpha = 0.2f)
                                        isFailed -> AccentRed.copy(alpha = 0.2f)
                                        else -> AmoledOutline.copy(alpha = 0.5f)
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
                                    isDownloading -> NeonCyan
                                    isCompleted -> AccentGreen
                                    isFailed -> AccentRed
                                    else -> TextSecondary
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = formatFileSize(video.fileSizeBytes),
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Barra de Progresso quando estiver baixando
            if (isDownloading) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    color = NeonCyan,
                    trackColor = AmoledOutline,
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
                    color = TextSecondary.copy(alpha = 0.6f),
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
                                containerColor = NeonCyan,
                                contentColor = Color.Black
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
                                .background(AmoledOutline)
                                .testTag("delete_failed_btn_${video.id}")
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Remover", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }

                    if (isCompleted) {
                        Button(
                            onClick = onWatch,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = Color.Black
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
