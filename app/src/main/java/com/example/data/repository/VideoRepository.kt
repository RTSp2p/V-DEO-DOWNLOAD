package com.example.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.example.data.download.DownloadHelper
import com.example.data.local.dao.BrowsingHistoryDao
import com.example.data.local.dao.VideoDao
import com.example.data.local.entity.BrowsingHistoryEntity
import com.example.data.local.entity.CapturedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class VideoRepository(
    private val videoDao: VideoDao,
    private val browsingHistoryDao: BrowsingHistoryDao,
    private val downloadHelper: DownloadHelper,
    private val context: Context,
    private val settingsRepository: SettingsRepository? = null
) {

    fun observeDetectedVideos(): Flow<List<CapturedVideoEntity>> =
        videoDao.observeDetectedVideos()

    fun observeCompletedVideos(): Flow<List<CapturedVideoEntity>> =
        videoDao.observeCompletedVideos()

    fun observeAllVideos(): Flow<List<CapturedVideoEntity>> =
        videoDao.observeAllVideos()

    fun observeDownloadingVideos(): Flow<List<CapturedVideoEntity>> =
        videoDao.observeDownloadingVideos()

    fun observeFailedVideos(): Flow<List<CapturedVideoEntity>> =
        videoDao.observeFailedVideos()

    fun observeBrowsingHistory(): Flow<List<BrowsingHistoryEntity>> =
        browsingHistoryDao.observeHistory()

    suspend fun recordBrowsingVisit(url: String, title: String) = withContext(Dispatchers.IO) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("chrome:")) return@withContext
        val cleanTitle = title.ifBlank { url }
        browsingHistoryDao.insertHistory(
            BrowsingHistoryEntity(
                url = url,
                title = cleanTitle,
                visitedAt = System.currentTimeMillis()
            )
        )
        browsingHistoryDao.trimHistory()
    }

    suspend fun clearBrowsingHistory() = withContext(Dispatchers.IO) {
        browsingHistoryDao.clearHistory()
    }

    fun updateWebViewContext(pageUrl: String?, userAgent: String?) {
        downloadHelper.updateWebViewContext(pageUrl, userAgent)
    }

    fun updateDownloadFolderUri(folderUri: String?) {
        downloadHelper.updateDownloadFolderUri(folderUri)
    }

    /**
     * Inserts a captured video into Room.
     * Uses OnConflictStrategy.IGNORE. Returns true if saved, false if duplicate (-1L).
     */
    suspend fun insertCapturedVideo(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        val rowId = videoDao.insertVideo(video)
        rowId != -1L
    }

    /**
     * Enqueues download for a single video using the configured SAF download folder if present.
     */
    suspend fun startDownload(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        val targetFolderUri = settingsRepository?.getDownloadFolderUriSnapshot()
            ?: downloadHelper.customDownloadFolderUri
        downloadHelper.updateDownloadFolderUri(targetFolderUri)

        val downloadId = downloadHelper.enqueueDownload(
            videoUrl = video.videoUrl,
            fileName = video.fileName,
            fileType = video.fileType,
            targetFolderUri = targetFolderUri
        )
        if (downloadId != -1L) {
            videoDao.updateStatusAndDownloadId(
                id = video.id,
                status = CapturedVideoEntity.STATUS_DOWNLOADING,
                downloadId = downloadId
            )
            true
        } else {
            videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_FAILED)
            false
        }
    }

    /**
     * Starts batch download for multiple captured videos.
     */
    suspend fun startBatchDownload(videos: List<CapturedVideoEntity>): Int = withContext(Dispatchers.IO) {
        val targetFolderUri = settingsRepository?.getDownloadFolderUriSnapshot()
            ?: downloadHelper.customDownloadFolderUri
        downloadHelper.updateDownloadFolderUri(targetFolderUri)

        var count = 0
        for (video in videos) {
            val downloadId = downloadHelper.enqueueDownload(
                videoUrl = video.videoUrl,
                fileName = video.fileName,
                fileType = video.fileType,
                targetFolderUri = targetFolderUri
            )
            if (downloadId != -1L) {
                videoDao.updateStatusAndDownloadId(
                    id = video.id,
                    status = CapturedVideoEntity.STATUS_DOWNLOADING,
                    downloadId = downloadId
                )
                count++
            } else {
                videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_FAILED)
            }
        }
        count
    }

    /**
     * Cancela todos os downloads ativos chamando downloadManager.remove(id)
     * e atualizando o status para FAILED no Room.
     */
    suspend fun cancelAllActiveDownloads(): Int = withContext(Dispatchers.IO) {
        val downloading = videoDao.getDownloadingVideos()
        val ids = downloading.map { it.downloadId }
        downloadHelper.cancelDownloads(ids)
        videoDao.cancelAllDownloading()
        downloading.size
    }

    /**
     * Cancela um download específico ativo.
     */
    suspend fun cancelSingleDownload(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        if (video.downloadId > 0) {
            downloadHelper.cancelDownload(video.downloadId)
        }
        videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_FAILED)
        true
    }

    /**
     * Tenta novamente iniciar o download de um item que falhou ou foi cancelado.
     */
    suspend fun retryDownload(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        startDownload(video)
    }

    /**
     * Called when DownloadManager completes a download.
     * Fetches file details, transfers to SAF directory if needed, and updates Room.
     */
    suspend fun handleDownloadComplete(downloadId: Long) = withContext(Dispatchers.IO) {
        val downloadInfo = downloadHelper.queryDownloadInfo(downloadId)
        val video = videoDao.getVideoByDownloadId(downloadId)

        if (video != null) {
            if (downloadInfo?.status == DownloadManager.STATUS_FAILED) {
                videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_FAILED)
                return@withContext
            }

            val rawLocalUri = downloadInfo?.localUri ?: run {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, video.fileName)
                if (targetFile.exists()) Uri.fromFile(targetFile).toString() else null
            }

            val customFolderUri = settingsRepository?.getDownloadFolderUriSnapshot()
                ?: downloadHelper.customDownloadFolderUri

            val finalLocalUri = if (!customFolderUri.isNullOrBlank()) {
                downloadHelper.transferToSafFolderIfNeeded(
                    downloadedLocalUri = rawLocalUri,
                    fileName = video.fileName,
                    fileType = video.fileType,
                    safTreeUriString = customFolderUri
                )
            } else {
                rawLocalUri
            }

            var fileSize = downloadInfo?.totalBytes ?: 0L
            if (fileSize <= 0L && finalLocalUri != null) {
                try {
                    val uri = Uri.parse(finalLocalUri)
                    if (uri.scheme == "file") {
                        val file = File(uri.path ?: "")
                        if (file.exists()) fileSize = file.length()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            videoDao.updateDownloadCompleted(
                downloadId = downloadId,
                localUri = finalLocalUri ?: "",
                fileSizeBytes = fileSize
            )
        }
    }

    /**
     * Renames a video file on disk (File or SAF Document) and in Room.
     */
    suspend fun renameVideo(id: Long, newNameWithoutExt: String): Boolean = withContext(Dispatchers.IO) {
        val video = videoDao.getVideoById(id) ?: return@withContext false
        val ext = video.fileType.removePrefix(".")
        val finalNewName = if (newNameWithoutExt.endsWith(".$ext", ignoreCase = true)) {
            newNameWithoutExt
        } else {
            "$newNameWithoutExt.$ext"
        }

        var updatedUri = video.localUri
        if (video.localUri != null) {
            try {
                val uri = Uri.parse(video.localUri)
                if (uri.scheme == "file") {
                    val oldFile = File(uri.path ?: "")
                    if (oldFile.exists()) {
                        val newFile = File(oldFile.parentFile, finalNewName)
                        if (oldFile.renameTo(newFile)) {
                            updatedUri = Uri.fromFile(newFile).toString()
                        }
                    }
                } else if (uri.scheme == "content") {
                    val renamedUri = DocumentsContract.renameDocument(
                        context.contentResolver,
                        uri,
                        finalNewName
                    )
                    if (renamedUri != null) {
                        updatedUri = renamedUri.toString()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (updatedUri != null && updatedUri != video.localUri) {
            videoDao.updateFileNameAndUri(id, finalNewName, updatedUri)
        } else {
            videoDao.updateFileName(id, finalNewName)
        }
        true
    }

    /**
     * Deletes the video record from Room and removes the physical file from disk (usado na Biblioteca).
     */
    suspend fun deleteVideo(id: Long): Boolean = withContext(Dispatchers.IO) {
        val video = videoDao.getVideoById(id)
        if (video != null) {
            video.localUri?.let { uriStr ->
                try {
                    val uri = Uri.parse(uriStr)
                    if (uri.scheme == "file") {
                        val file = File(uri.path ?: "")
                        if (file.exists()) {
                            file.delete()
                        }
                    } else if (uri.scheme == "content") {
                        context.contentResolver.delete(uri, null, null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            videoDao.deleteVideo(id)
            true
        } else {
            false
        }
    }

    /**
     * Exclusão física real de um item DETECTED do banco Room:
     * DELETE FROM captured_videos WHERE id = :id AND downloadStatus = 'DETECTED'
     * Libera a restrição @Index(unique = true) na coluna videoUrl para re-detecção futura.
     */
    suspend fun deleteDetectedVideo(id: Long): Boolean = withContext(Dispatchers.IO) {
        videoDao.deleteDetectedVideoById(id) > 0
    }

    /**
     * Exclusão física em lote de itens DETECTED do banco Room, liberando suas URLs para re-detecção.
     */
    suspend fun deleteDetectedVideos(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        for (id in ids) {
            if (videoDao.deleteDetectedVideoById(id) > 0) {
                deletedCount++
            }
        }
        deletedCount
    }

    /**
     * Limpa todos os itens com status DETECTED executando exclusão física no Room.
     */
    suspend fun clearDetected() = withContext(Dispatchers.IO) {
        val detected = videoDao.getDetectedVideos()
        for (item in detected) {
            videoDao.deleteDetectedVideoById(item.id)
        }
        videoDao.clearDetectedVideos()
    }

    /**
     * Limpa registros selecionados do histórico nas abas da tela Home ("TODOS", "BAIXANDO", "COMPLETOS", "ERROS").
     * Para itens finalizados (COMPLETED), NÃO apaga o arquivo físico baixado no armazenamento,
     * mas remove o registro da tabela captured_videos para liberar o @Index(unique = true) de videoUrl
     * e permitir novas detecções da mesma URL no futuro.
     */
    suspend fun removeVideosFromHistory(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val videos = videoDao.getVideosByIds(ids)
        for (video in videos) {
            if (video.downloadStatus == CapturedVideoEntity.STATUS_DOWNLOADING && video.downloadId > 0) {
                downloadHelper.cancelDownload(video.downloadId)
            }
        }
        videoDao.deleteVideosByIds(ids)
    }
}
