package com.example.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import java.io.File

class DownloadHelper(private val context: Context) {

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /**
     * Enqueues a video download to the public Downloads directory.
     * Returns the downloadId assigned by DownloadManager.
     */
    fun enqueueDownload(videoUrl: String, fileName: String, fileType: String): Long {
        return try {
            val sanitizedFileName = sanitizeFileName(fileName, fileType)
            val request = DownloadManager.Request(Uri.parse(videoUrl)).apply {
                setTitle(sanitizedFileName)
                setDescription("Baixando vídeo com 1DM Video Hunter")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                
                // Direct file to public Downloads directory
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sanitizedFileName)
                
                val mimeType = when (fileType.lowercase()) {
                    "mp4" -> "video/mp4"
                    "webm" -> "video/webm"
                    "mkv" -> "video/x-matroska"
                    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileType) ?: "video/*"
                }
                setMimeType(mimeType)
            }
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }

    private fun sanitizeFileName(name: String, extension: String): String {
        var clean = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (clean.isBlank()) {
            clean = "video_${System.currentTimeMillis()}"
        }
        val extWithDot = if (extension.startsWith(".")) extension else ".$extension"
        if (!clean.endsWith(extWithDot, ignoreCase = true)) {
            clean += extWithDot
        }
        return clean
    }

    fun queryDownloadInfo(downloadId: Long): DownloadInfo? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query) ?: return null
        cursor.use {
            if (it.moveToFirst()) {
                val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val sizeIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                
                val status = if (statusIndex >= 0) it.getInt(statusIndex) else -1
                val uriString = if (uriIndex >= 0) it.getString(uriIndex) else null
                val totalBytes = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L

                return DownloadInfo(
                    status = status,
                    localUri = uriString,
                    totalBytes = if (totalBytes > 0) totalBytes else 0L
                )
            }
        }
        return null
    }

    data class DownloadInfo(
        val status: Int,
        val localUri: String?,
        val totalBytes: Long
    )
}
