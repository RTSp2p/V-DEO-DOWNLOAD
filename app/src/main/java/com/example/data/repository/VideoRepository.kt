package com.example.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.data.download.DownloadHelper
import com.example.data.local.dao.VideoDao
import com.example.data.local.entity.CapturedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class VideoRepository(
    private val videoDao: VideoDao,
    private val downloadHelper: DownloadHelper,
    private val context: Context
) {

    fun observeDetectedVideos(): Flow<List<CapturedVideoEntity>> =
        videoDao.observeDetectedVideos()

    fun observeCompletedVideos(): Flow<List<CapturedVideoEntity>> =
        videoDao.observeCompletedVideos()

    /**
     * Inserts a captured video into Room.
     * Uses OnConflictStrategy.IGNORE. Returns true if saved, false if duplicate (-1L).
     */
    suspend fun insertCapturedVideo(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        val rowId = videoDao.insertVideo(video)
        rowId != -1L
    }

    /**
     * Enqueues download for a single video.
     */
    suspend fun startDownload(video: CapturedVideoEntity): Boolean = withContext(Dispatchers.IO) {
        val downloadId = downloadHelper.enqueueDownload(
            videoUrl = video.videoUrl,
            fileName = video.fileName,
            fileType = video.fileType
        )
        if (downloadId != -1L) {
            videoDao.updateStatusAndDownloadId(
                id = video.id,
                status = CapturedVideoEntity.STATUS_DOWNLOADING,
                downloadId = downloadId
            )
            true
        } else {
            false
        }
    }

    /**
     * Starts batch download for multiple captured videos.
     */
    suspend fun startBatchDownload(videos: List<CapturedVideoEntity>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (video in videos) {
            val downloadId = downloadHelper.enqueueDownload(
                videoUrl = video.videoUrl,
                fileName = video.fileName,
                fileType = video.fileType
            )
            if (downloadId != -1L) {
                videoDao.updateStatusAndDownloadId(
                    id = video.id,
                    status = CapturedVideoEntity.STATUS_DOWNLOADING,
                    downloadId = downloadId
                )
                count++
            }
        }
        count
    }

    /**
     * Called when DownloadManager completes a download.
     * Fetches file details and updates Room.
     */
    suspend fun handleDownloadComplete(downloadId: Long) = withContext(Dispatchers.IO) {
        val downloadInfo = downloadHelper.queryDownloadInfo(downloadId)
        val video = videoDao.getVideoByDownloadId(downloadId)

        if (video != null) {
            val localUri = downloadInfo?.localUri ?: run {
                // Fallback to standard Downloads directory file path
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, video.fileName)
                if (targetFile.exists()) Uri.fromFile(targetFile).toString() else null
            }
            
            // Determine real size on disk if available
            var fileSize = downloadInfo?.totalBytes ?: 0L
            if (fileSize <= 0L && localUri != null) {
                try {
                    val uri = Uri.parse(localUri)
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
                localUri = localUri ?: "",
                fileSizeBytes = fileSize
            )
        }
    }

    /**
     * Renames a video file on disk and in Room.
     */
    suspend fun renameVideo(id: Long, newNameWithoutExt: String): Boolean = withContext(Dispatchers.IO) {
        val video = videoDao.getVideoById(id) ?: return@withContext false
        val ext = video.fileType.removePrefix(".")
        val finalNewName = if (newNameWithoutExt.endsWith(".$ext", ignoreCase = true)) {
            newNameWithoutExt
        } else {
            "$newNameWithoutExt.$ext"
        }

        // Try renaming physical file if localUri is a file:// Uri
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
     * Deletes the video record from Room and removes the physical file from disk.
     */
    suspend fun deleteVideo(id: Long): Boolean = withContext(Dispatchers.IO) {
        val video = videoDao.getVideoById(id)
        if (video != null) {
            // Remove physical file
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

    suspend fun clearDetected() = withContext(Dispatchers.IO) {
        videoDao.clearDetectedVideos()
    }
}
