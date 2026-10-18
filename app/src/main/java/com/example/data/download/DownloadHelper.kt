package com.example.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import com.example.browser.VideoSnifferJsBridge

class DownloadHelper(private val context: Context) {

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    @Volatile
    var currentPageUrl: String = ""
        private set

    @Volatile
    var currentUserAgent: String = VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
        private set

    /**
     * Atualiza o contexto atual da WebView (URL da página de origem e User-Agent configurado)
     * para ser repassado ao DownloadManager e evitar erros 403 Forbidden.
     */
    fun updateWebViewContext(pageUrl: String?, userAgent: String?) {
        if (!pageUrl.isNullOrBlank()) {
            currentPageUrl = pageUrl
        }
        if (!userAgent.isNullOrBlank()) {
            currentUserAgent = userAgent
        }
    }

    /**
     * Enqueues a video download to the public Downloads directory.
     * Returns the downloadId assigned by DownloadManager.
     */
    fun enqueueDownload(
        videoUrl: String,
        fileName: String,
        fileType: String,
        pageUrl: String? = null,
        userAgent: String? = null
    ): Long {
        return try {
            val sanitizedFileName = sanitizeFileName(fileName, fileType)
            val effectivePageUrl = pageUrl?.takeIf { it.isNotBlank() } ?: currentPageUrl
            val effectiveUserAgent = userAgent?.takeIf { it.isNotBlank() }
                ?: currentUserAgent.ifBlank { VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT }

            val request = DownloadManager.Request(Uri.parse(videoUrl)).apply {
                setTitle(sanitizedFileName)
                setDescription("Baixando vídeo com 1DM Video Hunter")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                // 1. Repassa explicitamente o User-Agent atual da WebView
                addRequestHeader("User-Agent", effectiveUserAgent)

                // 2. Obtém e repassa os cookies da URL de origem (pageUrl) e da URL da mídia
                try {
                    val cookieManager = CookieManager.getInstance()
                    val pageCookie = if (effectivePageUrl.isNotBlank()) {
                        cookieManager.getCookie(effectivePageUrl)
                    } else {
                        null
                    }
                    val mediaCookie = cookieManager.getCookie(videoUrl)

                    val resolvedCookie = when {
                        !pageCookie.isNullOrBlank() && !mediaCookie.isNullOrBlank() && pageCookie != mediaCookie ->
                            "$pageCookie; $mediaCookie"
                        !pageCookie.isNullOrBlank() -> pageCookie
                        !mediaCookie.isNullOrBlank() -> mediaCookie
                        else -> null
                    }

                    if (!resolvedCookie.isNullOrBlank()) {
                        addRequestHeader("Cookie", resolvedCookie)
                    }
                } catch (_: Exception) {
                }

                // 3. Repassa também o Referer da página de origem para servidores que validam hotlinking
                if (effectivePageUrl.isNotBlank()) {
                    addRequestHeader("Referer", effectivePageUrl)
                }

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

    /**
     * Cancela e remove um download ativo pelo ID no DownloadManager.
     */
    fun cancelDownload(downloadId: Long): Boolean {
        return try {
            downloadManager.remove(downloadId) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Cancela múltiplos downloads ativos.
     */
    fun cancelDownloads(downloadIds: List<Long>): Int {
        var count = 0
        for (id in downloadIds) {
            if (id > 0 && cancelDownload(id)) {
                count++
            }
        }
        return count
    }

    data class DownloadInfo(
        val status: Int,
        val localUri: String?,
        val totalBytes: Long
    )
}
