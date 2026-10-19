package com.example.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import com.example.browser.VideoSnifferJsBridge
import java.io.File

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

    @Volatile
    var customDownloadFolderUri: String? = null
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
     * Atualiza o URI da pasta personalizada escolhida via Storage Access Framework (SAF).
     */
    fun updateDownloadFolderUri(folderUri: String?) {
        customDownloadFolderUri = folderUri?.takeIf { it.isNotBlank() }
    }

    /**
     * Enqueues a video download to the configured directory (or public Downloads directory).
     * Returns the downloadId assigned by DownloadManager.
     */
    fun enqueueDownload(
        videoUrl: String,
        fileName: String,
        fileType: String,
        pageUrl: String? = null,
        userAgent: String? = null,
        targetFolderUri: String? = customDownloadFolderUri
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

                // 4. Configura o destino considerando a pasta personalizada (SAF) se aplicável
                configureDestination(this, sanitizedFileName, targetFolderUri)

                val mimeType = resolveMimeType(fileType)
                setMimeType(mimeType)
            }
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }

    private fun configureDestination(
        request: DownloadManager.Request,
        sanitizedFileName: String,
        targetFolderUri: String?
    ) {
        if (!targetFolderUri.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(targetFolderUri)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: ""
                if (treeDocId.startsWith("primary:", ignoreCase = true)) {
                    val relativePath = treeDocId.substringAfter("primary:").trim('/')
                    when {
                        relativePath.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true) -> {
                            request.setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS,
                                sanitizedFileName
                            )
                            return
                        }
                        relativePath.startsWith("${Environment.DIRECTORY_DOWNLOADS}/", ignoreCase = true) -> {
                            val subPath = relativePath.substringAfter("/")
                            request.setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS,
                                "$subPath/$sanitizedFileName"
                            )
                            return
                        }
                        relativePath.isNotEmpty() -> {
                            val topDir = relativePath.substringBefore("/")
                            val subDir = relativePath.substringAfter("/", "")
                            val targetSubPath = if (subDir.isNotEmpty()) {
                                "$subDir/$sanitizedFileName"
                            } else {
                                sanitizedFileName
                            }
                            request.setDestinationInExternalPublicDir(topDir, targetSubPath)
                            return
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback seguro para DIRECTORY_DOWNLOADS e cópia via SAF no término
            }
        }
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sanitizedFileName)
    }

    /**
     * Se uma pasta SAF personalizada foi configurada e o arquivo baixado ainda não está
     * dentro da árvore SAF, copia/transfere o arquivo para o DocumentTree escolhido pelo usuário.
     */
    fun transferToSafFolderIfNeeded(
        downloadedLocalUri: String?,
        fileName: String,
        fileType: String,
        safTreeUriString: String? = customDownloadFolderUri
    ): String? {
        if (safTreeUriString.isNullOrBlank() || downloadedLocalUri.isNullOrBlank()) {
            return downloadedLocalUri
        }
        return try {
            val treeUri = Uri.parse(safTreeUriString)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: return downloadedLocalUri
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

            val sanitizedName = sanitizeFileName(fileName, fileType)
            val mimeType = resolveMimeType(fileType)

            val createdDocUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeType,
                sanitizedName
            ) ?: return downloadedLocalUri

            val sourceUri = Uri.parse(downloadedLocalUri)
            var copiedBytes = 0L

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(createdDocUri)?.use { output ->
                    copiedBytes = input.copyTo(output)
                }
            }

            if (copiedBytes > 0L) {
                // Remove arquivo temporário de origem se for file:// duplicado
                if (sourceUri.scheme == "file") {
                    val oldFile = File(sourceUri.path ?: "")
                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }
                createdDocUri.toString()
            } else {
                downloadedLocalUri
            }
        } catch (_: Exception) {
            downloadedLocalUri
        }
    }

    fun resolveMimeType(fileType: String): String {
        return when (fileType.lowercase().removePrefix(".")) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileType) ?: "video/*"
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
