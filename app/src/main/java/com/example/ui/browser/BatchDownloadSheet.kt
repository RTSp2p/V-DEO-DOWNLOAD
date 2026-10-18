package com.example.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.entity.CapturedVideoEntity
import com.example.ui.theme.AccentRed
import com.example.ui.theme.AmoledOutline
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDownloadSheet(
    detectedVideos: List<CapturedVideoEntity>,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDownloadSingle: (CapturedVideoEntity) -> Unit,
    onDownloadBatch: () -> Unit,
    onClearAll: () -> Unit,
    onCancelActiveDownloads: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allSelected = detectedVideos.isNotEmpty() && selectedIds.size == detectedVideos.size
    var showClearConfirmationDialog by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AmoledSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(44.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(AmoledOutline)
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(NeonPurple.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = NeonPurple,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.detected_videos),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "${detectedVideos.size} itens capturados nesta sessão",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                // Botão "X": apenas fecha/minimiza a janela mantendo os itens intactos no banco
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_sheet_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar janela",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (detectedVideos.isEmpty()) {
                // Empty State
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.empty_detected_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.empty_detected_desc),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                // Batch Action Bar (Select All + Selected Count)
                Surface(
                    color = AmoledSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { onToggleSelectAll() }
                                .padding(4.dp)
                        ) {
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = { onToggleSelectAll() },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = NeonCyan,
                                    checkmarkColor = Color.Black,
                                    uncheckedColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("select_all_checkbox")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (allSelected) "Desmarcar Todos" else stringResource(R.string.select_all),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Text(
                            text = "${selectedIds.size} selecionados",
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // List of Detected Media Cards
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(detectedVideos, key = { it.id }) { video ->
                        val isSelected = selectedIds.contains(video.id)
                        val isDownloading = video.downloadStatus == CapturedVideoEntity.STATUS_DOWNLOADING

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    NeonCyan.copy(alpha = 0.08f)
                                } else {
                                    AmoledSurfaceVariant.copy(alpha = 0.65f)
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detected_video_item_${video.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggleSelect(video.id) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = NeonCyan,
                                        checkmarkColor = Color.Black,
                                        uncheckedColor = TextSecondary
                                    ),
                                    modifier = Modifier.testTag("checkbox_${video.id}")
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = video.fileName,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
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

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Text(
                                            text = if (isDownloading) "Baixando..." else "Pronto para download",
                                            color = if (isDownloading) NeonCyan else TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Individual Download Button
                                IconButton(
                                    onClick = { onDownloadSingle(video) },
                                    enabled = !isDownloading,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isDownloading) AmoledOutline else NeonCyan.copy(alpha = 0.15f)
                                        )
                                        .testTag("download_single_btn_${video.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Baixar",
                                        tint = if (isDownloading) TextSecondary else NeonCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action Row with Options Menu & Batch Download Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Botão de Ações no Canto Inferior Esquerdo (Limpar Todos / Cancelar Download)
                    Box {
                        FilledTonalButton(
                            onClick = { showActionsMenu = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AmoledSurfaceVariant,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("sheet_actions_menu_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Mais Ações",
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ações", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false },
                            modifier = Modifier.background(AmoledSurfaceVariant)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            tint = AccentRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Limpar Todos", color = AccentRed, fontSize = 14.sp)
                                    }
                                },
                                onClick = {
                                    showActionsMenu = false
                                    showClearConfirmationDialog = true
                                },
                                modifier = Modifier.testTag("clear_all_detected_menu_item")
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = null,
                                            tint = NeonPurple,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Cancelar Downloads Ativos", color = TextPrimary, fontSize = 14.sp)
                                    }
                                },
                                onClick = {
                                    showActionsMenu = false
                                    onCancelActiveDownloads()
                                },
                                modifier = Modifier.testTag("cancel_active_downloads_menu_item")
                            )
                        }
                    }

                    // Botão Principal "Baixar Selecionados"
                    Button(
                        onClick = onDownloadBatch,
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = Color.Black,
                            disabledContainerColor = AmoledOutline,
                            disabledContentColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("batch_download_confirm_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Baixar (${selectedIds.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    // Diálogo de Confirmação para "Limpar Todos"
    if (showClearConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmationDialog = false },
            containerColor = AmoledSurface,
            title = {
                Text(
                    text = "Limpar Vídeos Detectados?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Deseja remover todos os vídeos detectados da lista? Os arquivos que já estão em download ou concluídos não serão afetados.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmationDialog = false
                        onClearAll()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.testTag("confirm_clear_all_btn")
                ) {
                    Text("Limpar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmationDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }
}
