package com.example.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.example.browser.VideoSnifferJsBridge
import com.example.data.download.DownloadHelper
import com.example.data.download.ManifestParseResult
import com.example.data.download.StreamManifestParser
import com.example.data.download.StreamQualityOption
import com.example.data.local.dao.BrowsingHistoryDao
import com.example.data.local.dao.VideoDao
import com.example.data.local.entity.BrowsingHistoryEntity
import com.example.data.local.entity.CapturedVideoEntity
import com.example.service.DownloadService
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

    private val manifestParser = StreamManifestParser()

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
     * Inserts a captured video into Room after normalizing and sanitizing its fileName and title
     * (never concatenating page title or domain, and ensuring a single ".mp4" extension).
     * Uses OnConflictStrategy.IGNORE. Returns true if saved, false if duplicate (-1L).
     */
    suspend fun insertCapturedVideo(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        val normalized = video.withNormalizedFileName()
        val rowId = videoDao.insertVideo(normalized)
        rowId != -1L
    }

    /**
     * Faz o parse do manifesto (.m3u8 ou .mpd) para apresentar o seletor de resoluções
     * (1080p, 720p, 480p, Apenas Áudio) ou detectar DRM preventivamente.
     */
    suspend fun parseStreamManifest(video: CapturedVideoEntity): ManifestParseResult = withContext(Dispatchers.IO) {
        val result = manifestParser.parseManifest(
            manifestUrl = video.videoUrl,
            streamType = video.streamType,
            headers = video.headers
        )
        if (result.isDrmProtected) {
            videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DRM_BLOCKED)
        }
        result
    }

    /**
     * Inicia o download de uma stream adaptativa com a opção de qualidade / áudio escolhida pelo usuário.
     * Garante que o ficheiro final termine sempre com uma única extensão ".mp4".
     */
    suspend fun startDownloadWithQuality(
        video: CapturedVideoEntity,
        option: StreamQualityOption
    ): Boolean = withContext(Dispatchers.IO) {
        if (video.isDrmProtected) {
            videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DRM_BLOCKED)
            return@withContext false
        }

        val updatedFileName = downloadHelper.resolveAndSanitizeFileName(
            videoUrl = video.videoUrl,
            candidateFileName = video.fileName
        )

        videoDao.updateStreamSelection(
            id = video.id,
            selectedQuality = option.label,
            variantVideoUrl = option.videoStreamUrl,
            variantAudioUrl = option.audioStreamUrl,
            isAudioOnly = option.isAudioOnly,
            fileType = "mp4",
            fileName = updatedFileName
        )

        videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DOWNLOADING)
        DownloadService.startDownload(context, video.id)
        true
    }

    /**
     * Inicia o download via motor híbrido DownloadService (suporta Range Requests, Pausa/Retomada,
     * HLS/DASH, descriptografia AES-128 e multiplexação MP4).
     */
    suspend fun startDownload(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        if (video.isDrmProtected) {
            videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DRM_BLOCKED)
            return@withContext false
        }

        val targetFolderUri = settingsRepository?.getDownloadFolderUriSnapshot()
            ?: downloadHelper.customDownloadFolderUri
        downloadHelper.updateDownloadFolderUri(targetFolderUri)

        val sanitizedName = downloadHelper.resolveAndSanitizeFileName(
            videoUrl = video.videoUrl,
            candidateFileName = video.fileName
        )
        if (sanitizedName != video.fileName) {
            videoDao.updateFileName(video.id, sanitizedName)
        }

        videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DOWNLOADING)
        DownloadService.startDownload(context, video.id)
        true
    }

    /**
     * Inicia download em lote para múltiplos vídeos capturados.
     * Para streams adaptativas em lote, o DownloadService seleciona automaticamente uma única variante (a maior resolução).
     */
    suspend fun startBatchDownload(videos: List<CapturedVideoEntity>): Int = withContext(Dispatchers.IO) {
        val targetFolderUri = settingsRepository?.getDownloadFolderUriSnapshot()
            ?: downloadHelper.customDownloadFolderUri
        downloadHelper.updateDownloadFolderUri(targetFolderUri)

        var count = 0
        for (video in videos) {
            if (video.isDrmProtected) {
                videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DRM_BLOCKED)
                continue
            }
            val sanitizedName = downloadHelper.resolveAndSanitizeFileName(
                videoUrl = video.videoUrl,
                candidateFileName = video.fileName
            )
            if (sanitizedName != video.fileName) {
                videoDao.updateFileName(video.id, sanitizedName)
            }
            videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DOWNLOADING)
            DownloadService.startDownload(context, video.id)
            count++
        }
        count
    }

    suspend fun pauseDownload(video: CapturedVideoEntity) = withContext(Dispatchers.IO) {
        DownloadService.pauseDownload(context, video.id)
        videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_PAUSED)
    }

    suspend fun resumeDownload(video: CapturedVideoEntity) = withContext(Dispatchers.IO) {
        videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_DOWNLOADING)
        DownloadService.resumeDownload(context, video.id)
    }

    /**
     * Cancela todos os downloads ativos no DownloadService e DownloadManager.
     */
    suspend fun cancelAllActiveDownloads(): Int = withContext(Dispatchers.IO) {
        val downloading = videoDao.getDownloadingVideos()
        val ids = downloading.map { it.downloadId }.filter { it > 0 }
        if (ids.isNotEmpty()) {
            downloadHelper.cancelDownloads(ids)
        }
        DownloadService.cancelAllDownloads(context)
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
        DownloadService.cancelDownload(context, video.id)
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
     * Compatibilidade caso algum download legado pelo DownloadManager complete.
     */
    suspend fun handleDownloadComplete(downloadId: Long) = withContext(Dispatchers.IO) {
        val downloadInfo = downloadHelper.queryDownloadInfo(downloadId)
        val video = videoDao.getVideoByDownloadId(downloadId)

        if (video != null) {
            if (downloadInfo?.status == DownloadManager.STATUS_FAILED) {
                videoDao.updateStatus(video.id, CapturedVideoEntity.STATUS_FAILED)
                return@withContext
            }

            val cleanFileName = downloadHelper.sanitizeFileName(video.fileName, "mp4")

            val rawLocalUri = downloadInfo?.localUri ?: run {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, cleanFileName)
                if (targetFile.exists()) Uri.fromFile(targetFile).toString() else null
            }

            val customFolderUri = settingsRepository?.getDownloadFolderUriSnapshot()
                ?: downloadHelper.customDownloadFolderUri

            val finalLocalUri = if (!customFolderUri.isNullOrBlank()) {
                downloadHelper.transferToSafFolderIfNeeded(
                    downloadedLocalUri = rawLocalUri,
                    fileName = cleanFileName,
                    fileType = "mp4",
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

            videoDao.updateDownloadCompletedById(
                id = video.id,
                localUri = finalLocalUri ?: "",
                fileSizeBytes = fileSize,
                finalFileName = cleanFileName,
                finalFileType = "mp4"
            )
        }
    }

    /**
     * Renames a video file on disk (File or SAF Document) and in Room,
     * sanitizing invalid Android characters and ensuring a single ".mp4" extension.
     */
    suspend fun renameVideo(id: Long, newNameWithoutExt: String): Boolean = withContext(Dispatchers.IO) {
        val video = videoDao.getVideoById(id) ?: return@withContext false
        val finalNewName = VideoSnifferJsBridge.sanitizeAndroidFileName(newNameWithoutExt, "mp4")

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
     * Limpa registros selecionados do histórico nas abas da tela Home.
     */
    suspend fun removeVideosFromHistory(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val videos = videoDao.getVideosByIds(ids)
        for (video in videos) {
            if (video.downloadStatus == CapturedVideoEntity.STATUS_DOWNLOADING ||
                video.downloadStatus == CapturedVideoEntity.STATUS_PAUSED ||
                video.downloadStatus == CapturedVideoEntity.STATUS_MUXING
            ) {
                if (video.downloadId > 0) {
                    downloadHelper.cancelDownload(video.downloadId)
                }
                DownloadService.cancelDownload(context, video.id)
            }
        }
        videoDao.deleteVideosByIds(ids)
    }
}
