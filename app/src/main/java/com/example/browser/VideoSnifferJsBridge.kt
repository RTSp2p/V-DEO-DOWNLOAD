package com.example.browser

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Collections

/**
 * Ponte de comunicação nativa (@JavascriptInterface) estilo 1DM.
 * Recebe URLs de vídeo capturadas ativamente no DOM (hooks em HTMLMediaElement.prototype.src,
 * MutationObserver, eventos de reprodução, varredura inicial) e também em respostas JSON
 * de APIs assíncronas (fetch / XMLHttpRequest), encaminhando-as para a camada de negócios
 * (ViewModel -> Room Database com OnConflictStrategy.IGNORE).
 */
class VideoSnifferJsBridge(
    private val onVideoDetected: (url: String, fileName: String, extension: String) -> Unit
) {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedUrls = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Alias principal solicitado para recebimento de vídeos capturados via JSON APIs e DOM
     * (AndroidBridge.reportVideo(url, document.title)).
     */
    @JavascriptInterface
    fun reportVideo(mediaUrl: String?, pageTitle: String?) {
        onMediaDetected(mediaUrl, pageTitle)
    }

    /**
     * Chamado pelo script JavaScript injetado na WebView quando um elemento <video>, <source>,
     * atribuição em HTMLMediaElement.prototype.src ou resposta JSON de API é detectado.
     */
    @JavascriptInterface
    fun onMediaDetected(mediaUrl: String?, pageTitle: String?) {
        val rawUrl = normalizeRawUrl(mediaUrl) ?: return
        if (rawUrl.isEmpty()) return

        // 1. Filtrar protocolos incompatíveis com download direto (blob:, data:, etc.)
        if (isIncompatibleProtocol(rawUrl)) {
            return
        }

        // 2. Descartar manifestos e segmentos de streaming (HLS / DASH)
        if (isStreamingFragmentOrManifest(rawUrl)) {
            return
        }

        // Evita reprocessar repetidamente a mesma URL na sessão da ponte antes de chegar ao Room
        if (!processedUrls.add(rawUrl)) {
            return
        }

        // 3. Verificar se a URL contém extensão de vídeo (.mp4, .webm, .mkv) no caminho
        // ou antes de tokens de consulta / URLs assinadas (?expires=...&signature=...&token=...)
        val directExt = extractDirectExtension(rawUrl)
        if (directExt != null) {
            val fileName = buildFileName(rawUrl, pageTitle, directExt)
            onVideoDetected(rawUrl, fileName, directExt)
            return
        }

        // 4. Caso seja uma URL de mídia dinâmica vinda de uma tag <video>/<source> ou JSON sem extensão aparente,
        // inspeciona os cabeçalhos HTTP em background para confirmar se é MP4, WebM ou MKV completo.
        inspectDynamicMediaUrl(rawUrl, pageTitle)
    }

    private fun normalizeRawUrl(mediaUrl: String?): String? {
        val trimmed = mediaUrl?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        return trimmed
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    private fun isIncompatibleProtocol(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("blob:") ||
                lower.startsWith("data:") ||
                lower.startsWith("javascript:") ||
                lower.startsWith("about:") ||
                lower.startsWith("mailto:") ||
                lower.startsWith("intent:") ||
                (!lower.startsWith("http://") && !lower.startsWith("https://"))
    }

    private fun isStreamingFragmentOrManifest(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        val lower = url.lowercase()
        return clean.endsWith(".m3u8") ||
                clean.endsWith(".ts") ||
                clean.endsWith(".mpd") ||
                clean.endsWith(".m4s") ||
                clean.endsWith(".fmp4") ||
                lower.contains(".m3u8?") ||
                lower.contains(".mpd?") ||
                lower.contains(".m4s?")
    }

    /**
     * Suporta tanto URLs que terminam em .mp4/.webm/.mkv quanto URLs assinadas com
     * parâmetros complexos (ex: ?expires=...&signature=...&token=...) ou que contenham
     * a extensão no caminho antes dos tokens.
     */
    private fun extractDirectExtension(url: String): String? {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val lowerUrl = url.lowercase()

        return when {
            cleanPath.endsWith(".mp4") ||
                    cleanPath.contains(".mp4/") ||
                    lowerUrl.contains(".mp4?") ||
                    lowerUrl.contains(".mp4&") ||
                    lowerUrl.contains("mime=video/mp4") ||
                    lowerUrl.contains("mime=video%2fmp4") ||
                    Regex("""\bmp4\b""").containsMatchIn(cleanPath.substringAfterLast('/')) -> "mp4"

            cleanPath.endsWith(".webm") ||
                    cleanPath.contains(".webm/") ||
                    lowerUrl.contains(".webm?") ||
                    lowerUrl.contains(".webm&") ||
                    lowerUrl.contains("mime=video/webm") ||
                    lowerUrl.contains("mime=video%2fwebm") ||
                    Regex("""\bwebm\b""").containsMatchIn(cleanPath.substringAfterLast('/')) -> "webm"

            cleanPath.endsWith(".mkv") ||
                    cleanPath.contains(".mkv/") ||
                    lowerUrl.contains(".mkv?") ||
                    lowerUrl.contains(".mkv&") ||
                    lowerUrl.contains("mime=video/x-matroska") ||
                    Regex("""\bmkv\b""").containsMatchIn(cleanPath.substringAfterLast('/')) -> "mkv"

            else -> null
        }
    }

    private fun inspectDynamicMediaUrl(urlString: String, pageTitle: String?) {
        bridgeScope.launch {
            try {
                val cookie = try {
                    CookieManager.getInstance().getCookie(urlString)
                } catch (_: Exception) {
                    null
                }

                val connection = (URL(urlString).openConnection() as? HttpURLConnection)?.apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3500
                    readTimeout = 3500
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", MODERN_MOBILE_USER_AGENT)
                    if (!cookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookie)
                    }
                } ?: return@launch

                val contentType = connection.contentType?.lowercase() ?: ""
                connection.disconnect()

                val detectedExt = when {
                    contentType.contains("video/mp4") -> "mp4"
                    contentType.contains("video/webm") -> "webm"
                    contentType.contains("video/x-matroska") -> "mkv"
                    else -> null
                }

                if (detectedExt != null) {
                    val fileName = buildFileName(urlString, pageTitle, detectedExt)
                    onVideoDetected(urlString, fileName, detectedExt)
                }
            } catch (_: Exception) {
                // Ignora falhas de rede silenciosamente em background
            }
        }
    }

    private fun buildFileName(url: String, pageTitle: String?, extension: String): String {
        val extDot = ".$extension"

        // Tenta extrair o nome do segmento da URL primeiro se for descritivo
        try {
            val cleanUrlBeforeQuery = url.substringBefore('?').substringBefore('#')
            val uri = Uri.parse(cleanUrlBeforeQuery)
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank() && !segment.contains("=")) {
                val decoded = URLDecoder.decode(segment, "UTF-8")
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .trim('_')
                if (decoded.length > 3) {
                    return if (decoded.endsWith(extDot, ignoreCase = true)) {
                        decoded
                    } else {
                        "${decoded.substringBeforeLast('.')}$extDot"
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Usa o título da página se disponível
        val cleanTitle = pageTitle
            ?.trim()
            ?.replace(Regex("[^a-zA-Z0-9._\\-\\s]"), "")
            ?.trim()
            ?.replace(Regex("\\s+"), "_")
            ?.take(48)

        return if (!cleanTitle.isNullOrBlank() && cleanTitle.length > 2) {
            "${cleanTitle}_${System.currentTimeMillis() % 10000}$extDot"
        } else {
            "video_${System.currentTimeMillis()}$extDot"
        }
    }

    companion object {
        const val INTERFACE_NAME = "AndroidVideoSniffer"
        const val BRIDGE_ALIAS_NAME = "AndroidBridge"
        const val MODERN_MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
