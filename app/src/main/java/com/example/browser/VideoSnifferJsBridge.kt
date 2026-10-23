package com.example.browser

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import com.example.data.local.entity.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Ponte de comunicação nativa (@JavascriptInterface) estilo 1DM.
 * Captura vídeos diretos (.mp4, .webm, .mkv) e streams adaptativas (.m3u8 HLS e .mpd DASH),
 * aplicando nomenclatura limpa baseada exclusivamente no recurso de mídia:
 * - Prioridade 1: Cabeçalho HTTP Content-Disposition ou último segmento válido da URL (antes de "?").
 * - Prioridade 2: Atributo "title" ou "aria-label" diretamente da tag <video>.
 * - Fallback: "video_YYYYMMDD_HHmmss.mp4".
 * NUNCA utiliza nem concatena document.title ou domínio do site.
 */
class VideoSnifferJsBridge(
    private val onVideoDetected: (url: String, fileName: String, extension: String) -> Unit,
    private val onRichMediaDetected: ((DetectedMediaPayload) -> Unit)? = null,
    private val pageUrlProvider: () -> String? = { null },
    private val userAgentProvider: () -> String = { MODERN_MOBILE_USER_AGENT }
) {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightUrls = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Recebe vídeos capturados via DOM ou APIs.
     * O segundo parâmetro (videoElementTitle) contém exclusivamente o atributo title/aria-label
     * da tag <video> (ou vazio), nunca document.title.
     */
    @JavascriptInterface
    fun reportVideo(mediaUrl: String?, videoElementTitle: String?) {
        onMediaDetectedWithMeta(mediaUrl, videoElementTitle, null, null)
    }

    /**
     * Versão enriquecida chamada pelo script injetado com title/aria-label da tag <video>,
     * poster/thumbnail e referer da página.
     */
    @JavascriptInterface
    fun reportVideoWithMeta(
        mediaUrl: String?,
        videoElementTitle: String?,
        thumbnailUrl: String?,
        pageReferer: String?
    ) {
        onMediaDetectedWithMeta(mediaUrl, videoElementTitle, thumbnailUrl, pageReferer)
    }

    @JavascriptInterface
    fun onMediaDetected(mediaUrl: String?, videoElementTitle: String?) {
        onMediaDetectedWithMeta(mediaUrl, videoElementTitle, null, null)
    }

    @JavascriptInterface
    fun onMediaDetectedWithMeta(
        mediaUrl: String?,
        videoElementTitle: String?,
        thumbnailUrl: String?,
        pageReferer: String?
    ) {
        val rawUrl = normalizeRawUrl(mediaUrl) ?: return
        if (rawUrl.isEmpty()) return

        // 1. Filtrar protocolos incompatíveis (blob:, data:, javascript:, etc.)
        if (isIncompatibleProtocol(rawUrl)) {
            return
        }

        // 2. Ignorar segmentos individuais de stream (.ts, .m4s, .mp4-init, .fmp4)
        if (isIndividualStreamSegment(rawUrl)) {
            return
        }

        val effectiveReferer = pageReferer?.takeIf { it.isNotBlank() } ?: pageUrlProvider() ?: ""
        val effectiveUserAgent = userAgentProvider().ifBlank { MODERN_MOBILE_USER_AGENT }
        val headers = buildContextHeaders(rawUrl, effectiveReferer, effectiveUserAgent)
        val cleanThumb = normalizeRawUrl(thumbnailUrl)
        val cleanVideoTagTitle = videoElementTitle?.trim()?.takeIf { it.isNotEmpty() }

        // 3. Verificar se é um manifesto de Stream Adaptativa (.m3u8 HLS ou .mpd DASH)
        val adaptiveStreamType = extractAdaptiveStreamType(rawUrl)
        if (adaptiveStreamType != null) {
            inspectAndEmitStreamManifest(
                manifestUrl = rawUrl,
                streamType = adaptiveStreamType,
                videoElementTitle = cleanVideoTagTitle,
                thumbnailUrl = cleanThumb,
                headers = headers
            )
            return
        }

        // 4. Verificar se a URL contém extensão de vídeo direto (.mp4, .webm, .mkv)
        val directExt = extractDirectExtension(rawUrl)
        if (directExt != null) {
            val fileName = buildFileName(
                url = rawUrl,
                videoElementTitle = cleanVideoTagTitle,
                extension = "mp4",
                contentDisposition = null
            )
            val resolvedTitle = fileName.substringBeforeLast('.')
            emitDetectedMedia(
                DetectedMediaPayload(
                    url = rawUrl,
                    fileName = fileName,
                    extension = "mp4",
                    isStream = false,
                    streamType = StreamType.DIRECT,
                    headers = headers,
                    title = resolvedTitle,
                    thumbnailUrl = cleanThumb,
                    isDrmProtected = false
                )
            )
            return
        }

        // 5. Caso seja uma URL dinâmica sem extensão aparente, inspeciona cabeçalhos HTTP em background
        inspectDynamicMediaUrl(rawUrl, cleanVideoTagTitle, cleanThumb, headers)
    }

    private fun emitDetectedMedia(payload: DetectedMediaPayload) {
        if (onRichMediaDetected != null) {
            onRichMediaDetected.invoke(payload)
        } else {
            onVideoDetected(payload.url, payload.fileName, payload.extension)
        }
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

    /**
     * Descarta apenas os fragmentos/segmentos individuais (.ts, .m4s, .mp4-init, .fmp4)
     * para não poluir a lista com centenas de pedaços de 2 segundos.
     */
    fun isIndividualStreamSegment(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        val lower = url.lowercase()
        return clean.endsWith(".ts") ||
                clean.endsWith(".m4s") ||
                clean.endsWith(".mp4-init") ||
                clean.endsWith(".fmp4") ||
                clean.endsWith(".aac") ||
                clean.endsWith(".vtt") ||
                clean.endsWith(".srt") ||
                lower.contains(".ts?") ||
                lower.contains(".m4s?") ||
                lower.contains(".mp4-init") ||
                lower.contains(".fmp4?") ||
                Regex("""/(seg|segment|chunk|frag)[-_]?\d+\.(ts|m4s|mp4)""").containsMatchIn(clean)
    }

    /**
     * Identifica se a URL é um manifesto HLS (.m3u8) ou DASH (.mpd).
     */
    fun extractAdaptiveStreamType(url: String): StreamType? {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val lowerUrl = url.lowercase()
        return when {
            cleanPath.endsWith(".m3u8") ||
                    cleanPath.contains(".m3u8/") ||
                    lowerUrl.contains(".m3u8?") ||
                    lowerUrl.contains(".m3u8&") ||
                    lowerUrl.contains("mime=application/x-mpegurl") ||
                    lowerUrl.contains("mime=application/vnd.apple.mpegurl") ||
                    lowerUrl.contains("format=m3u8") -> StreamType.HLS

            cleanPath.endsWith(".mpd") ||
                    cleanPath.contains(".mpd/") ||
                    lowerUrl.contains(".mpd?") ||
                    lowerUrl.contains(".mpd&") ||
                    lowerUrl.contains("mime=application/dash+xml") ||
                    lowerUrl.contains("format=mpd") -> StreamType.DASH

            else -> null
        }
    }

    /**
     * Suporta tanto URLs que terminam em .mp4/.webm/.mkv quanto URLs assinadas com
     * parâmetros complexos (ex: ?expires=...&signature=...&token=...).
     */
    fun extractDirectExtension(url: String): String? {
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

    private fun inspectAndEmitStreamManifest(
        manifestUrl: String,
        streamType: StreamType,
        videoElementTitle: String?,
        thumbnailUrl: String?,
        headers: Map<String, String>
    ) {
        if (!inFlightUrls.add(manifestUrl)) return
        bridgeScope.launch {
            try {
                val inspection = inspectManifestDrmAndHeaders(manifestUrl, headers)
                val ext = if (streamType == StreamType.HLS) "m3u8" else "mpd"
                val fileName = buildFileName(
                    url = manifestUrl,
                    videoElementTitle = videoElementTitle,
                    extension = "mp4",
                    contentDisposition = inspection.contentDisposition
                )
                val resolvedTitle = fileName.substringBeforeLast('.')

                emitDetectedMedia(
                    DetectedMediaPayload(
                        url = manifestUrl,
                        fileName = fileName,
                        extension = ext,
                        isStream = true,
                        streamType = streamType,
                        headers = headers,
                        title = resolvedTitle,
                        thumbnailUrl = thumbnailUrl,
                        isDrmProtected = inspection.isDrmProtected
                    )
                )
            } finally {
                inFlightUrls.remove(manifestUrl)
            }
        }
    }

    private fun inspectDynamicMediaUrl(
        urlString: String,
        videoElementTitle: String?,
        thumbnailUrl: String?,
        headers: Map<String, String>
    ) {
        if (!inFlightUrls.add(urlString)) return
        bridgeScope.launch {
            try {
                val connection = (URL(urlString).openConnection() as? HttpURLConnection)?.apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3500
                    readTimeout = 3500
                    instanceFollowRedirects = true
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                } ?: return@launch

                val contentType = connection.contentType?.lowercase() ?: ""
                val contentLength = connection.contentLengthLong.coerceAtLeast(0L)
                val contentDisposition = connection.getHeaderField("Content-Disposition")
                connection.disconnect()

                val streamType = when {
                    contentType.contains("application/x-mpegurl") ||
                            contentType.contains("application/vnd.apple.mpegurl") ||
                            contentType.contains("audio/mpegurl") -> StreamType.HLS
                    contentType.contains("application/dash+xml") -> StreamType.DASH
                    else -> null
                }

                if (streamType != null) {
                    val inspection = inspectManifestDrmAndHeaders(urlString, headers)
                    val ext = if (streamType == StreamType.HLS) "m3u8" else "mpd"
                    val fileName = buildFileName(
                        url = urlString,
                        videoElementTitle = videoElementTitle,
                        extension = "mp4",
                        contentDisposition = contentDisposition ?: inspection.contentDisposition
                    )
                    val resolvedTitle = fileName.substringBeforeLast('.')
                    emitDetectedMedia(
                        DetectedMediaPayload(
                            url = urlString,
                            fileName = fileName,
                            extension = ext,
                            isStream = true,
                            streamType = streamType,
                            headers = headers,
                            title = resolvedTitle,
                            thumbnailUrl = thumbnailUrl,
                            isDrmProtected = inspection.isDrmProtected
                        )
                    )
                    return@launch
                }

                val isSupportedVideo = contentType.contains("video/mp4") ||
                        contentType.contains("video/webm") ||
                        contentType.contains("video/x-matroska")

                if (isSupportedVideo) {
                    val fileName = buildFileName(
                        url = urlString,
                        videoElementTitle = videoElementTitle,
                        extension = "mp4",
                        contentDisposition = contentDisposition
                    )
                    val resolvedTitle = fileName.substringBeforeLast('.')
                    emitDetectedMedia(
                        DetectedMediaPayload(
                            url = urlString,
                            fileName = fileName,
                            extension = "mp4",
                            isStream = false,
                            streamType = StreamType.DIRECT,
                            headers = headers,
                            title = resolvedTitle,
                            thumbnailUrl = thumbnailUrl,
                            isDrmProtected = false,
                            fileSizeBytes = contentLength
                        )
                    )
                }
            } catch (_: Exception) {
                // Ignora falhas de rede silenciosamente em background
            } finally {
                inFlightUrls.remove(urlString)
            }
        }
    }

    data class ManifestInspection(
        val isDrmProtected: Boolean,
        val contentDisposition: String?
    )

    companion object {
        const val INTERFACE_NAME = "AndroidVideoSniffer"
        const val BRIDGE_ALIAS_NAME = "AndroidBridge"
        const val MODERN_MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        // Caracteres proibidos no sistema de ficheiros do Android: \ / : * ? " < > | e caracteres de controle
        private val INVALID_ANDROID_FS_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
        private val MULTIPLE_WHITESPACE_REGEX = Regex("""\s+""")
        private val KNOWN_MEDIA_EXTENSIONS_REGEX =
            Regex("""\.(mp4|m4a|webm|mkv|m3u8|mpd|ts|m4s|mov|avi|flv|fmp4|aac|mp3)$""", RegexOption.IGNORE_CASE)

        // Nomes genéricos de endpoints/streams que devem ceder lugar à Prioridade 2 (atributo title/aria-label de <video>) ou timestamp
        private val GENERIC_STREAM_NAMES = setOf(
            "stream", "master", "index", "playlist", "manifest", "chunklist",
            "video", "media", "play", "playback", "watch", "hls", "dash",
            "live", "vod", "default", "output", "file", "download", "source",
            "player", "embed", "v", "m3u8", "mpd", "mp4", "webm", "mkv",
            "init", "segment", "chunk", "frag", "audio", "track", "main"
        )

        fun buildContextHeaders(
            mediaUrl: String,
            pageReferer: String?,
            userAgent: String?
        ): Map<String, String> {
            val map = mutableMapOf<String, String>()
            val effectiveUa = userAgent?.takeIf { it.isNotBlank() } ?: MODERN_MOBILE_USER_AGENT
            map["User-Agent"] = effectiveUa

            val referer = pageReferer?.takeIf { it.isNotBlank() && it != "about:blank" }
            if (referer != null) {
                map["Referer"] = referer
            }

            try {
                val cookieManager = CookieManager.getInstance()
                val pageCookie = if (!referer.isNullOrBlank()) cookieManager.getCookie(referer) else null
                val mediaCookie = cookieManager.getCookie(mediaUrl)
                val combinedCookie = when {
                    !pageCookie.isNullOrBlank() && !mediaCookie.isNullOrBlank() && pageCookie != mediaCookie ->
                        "$pageCookie; $mediaCookie"
                    !pageCookie.isNullOrBlank() -> pageCookie
                    !mediaCookie.isNullOrBlank() -> mediaCookie
                    else -> null
                }
                if (!combinedCookie.isNullOrBlank()) {
                    map["Cookie"] = combinedCookie
                }
            } catch (_: Exception) {
            }

            return map
        }

        fun checkManifestForDrm(manifestUrl: String, headers: Map<String, String>): Boolean {
            return inspectManifestDrmAndHeaders(manifestUrl, headers).isDrmProtected
        }

        fun inspectManifestDrmAndHeaders(
            manifestUrl: String,
            headers: Map<String, String>
        ): ManifestInspection {
            return try {
                val conn = (URL(manifestUrl).openConnection() as? HttpURLConnection)?.apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                } ?: return ManifestInspection(false, null)

                val contentDisposition = conn.getHeaderField("Content-Disposition")
                val text = BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    var linesRead = 0
                    while (reader.readLine().also { line = it } != null && linesRead < 150) {
                        sb.append(line).append('\n')
                        linesRead++
                    }
                    sb.toString()
                }
                conn.disconnect()
                ManifestInspection(
                    isDrmProtected = containsCommercialDrmMarkers(text),
                    contentDisposition = contentDisposition
                )
            } catch (_: Exception) {
                ManifestInspection(false, null)
            }
        }

        fun containsCommercialDrmMarkers(manifestContent: String): Boolean {
            val upper = manifestContent.uppercase()
            return upper.contains("METHOD=SAMPLE-AES") ||
                    upper.contains("METHOD=SAMPLE-AES-CTR") ||
                    upper.contains("METHOD=SAMPLE-AES-CENC") ||
                    upper.contains("COM.WIDEVINE.ALPHA") ||
                    upper.contains("EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED") || // Widevine UUID
                    upper.contains("9A04F079-9840-4286-AB92-E65BE0885F95") || // PlayReady UUID
                    upper.contains("94CE86FB-07FF-4F43-ADB8-93D2FA968CA2") || // FairPlay UUID
                    upper.contains("PLAYREADY") ||
                    upper.contains("CENC:PSSH") ||
                    upper.contains("CONTENTPROTECTION")
        }

        /**
         * Extrai o nome do ficheiro a partir do cabeçalho HTTP Content-Disposition, se presente.
         * Suporta tanto filename*=UTF-8''nome.mp4 quanto filename="nome.mp4" / filename=nome.mp4.
         */
        fun extractFileNameFromContentDisposition(contentDisposition: String?): String? {
            if (contentDisposition.isNullOrBlank()) return null

            // 1. Tenta RFC 5987: filename*=UTF-8''...
            val utf8Regex = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;\r\n]+)""", RegexOption.IGNORE_CASE)
            utf8Regex.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { encoded ->
                val raw = encoded.trim().trim('"', '\'')
                val decoded = try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (_: Exception) {
                    raw
                }
                val base = stripKnownExtensions(sanitizeBaseName(decoded.substringAfterLast('/')))
                if (base.isNotBlank() && !isGenericMediaIdentifier(base)) {
                    return base
                }
            }

            // 2. Tenta filename="..." ou filename=...
            val stdRegex = Regex("""filename\s*=\s*(?:"([^"]+)"|'([^']+)'|([^;\s\r\n]+))""", RegexOption.IGNORE_CASE)
            stdRegex.find(contentDisposition)?.let { match ->
                val raw = (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value ?: "").trim()
                if (raw.isNotEmpty()) {
                    val decoded = try {
                        URLDecoder.decode(raw, "UTF-8")
                    } catch (_: Exception) {
                        raw
                    }
                    val base = stripKnownExtensions(sanitizeBaseName(decoded.substringAfterLast('/')))
                    if (base.isNotBlank() && !isGenericMediaIdentifier(base)) {
                        return base
                    }
                }
            }

            return null
        }

        /**
         * Extrai o nome base do último segmento válido da URL antes de parâmetros de query ("?") e fragmento ("#").
         * Exemplo: "https://exemplo.com/media/aula_01.mp4?token=xyz" -> "aula_01".
         * Retorna null se o segmento for genérico (ex: "/stream.m3u8", hashes, tokens).
         */
        fun extractValidUrlBaseName(url: String): String? {
            if (url.isBlank()) return null
            return try {
                val cleanUrlBeforeQuery = url.substringBefore('?').substringBefore('#').trimEnd('/')
                val rawSegment = cleanUrlBeforeQuery.substringAfterLast('/', "")
                if (rawSegment.isBlank() || rawSegment.contains("=")) return null

                val decodedSegment = try {
                    URLDecoder.decode(rawSegment, "UTF-8")
                } catch (_: Exception) {
                    rawSegment
                }

                val baseName = stripKnownExtensions(sanitizeBaseName(decodedSegment))
                if (baseName.isBlank() || isGenericMediaIdentifier(baseName)) {
                    null
                } else {
                    baseName
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Verifica se um identificador extraído da URL é genérico (apenas hashes, tokens,
         * números puros de timestamp ou nomes genéricos como "stream", "master", "playlist", "index").
         */
        fun isGenericMediaIdentifier(baseName: String): Boolean {
            val trimmed = baseName.trim().trim('_', '-', '.')
            if (trimmed.length < 2) return true

            val lower = trimmed.lowercase(Locale.ROOT)
            if (lower in GENERIC_STREAM_NAMES) return true

            // Padrões genéricos como master_1080p, playlist-0, chunklist_b1200000, index_1, stream1
            if (Regex("""^(master|index|playlist|manifest|stream|chunklist|video|media|segment|seg|chunk|frag)[-_]?\d*$""").matches(lower)) {
                return true
            }

            // Apenas indicação de resolução (ex: "1080p", "720p", "480p", "4k", "hd", "sd")
            if (Regex("""^(\d{3,4}p|4k|2k|hd|sd|fhd|uhd|auto)$""").matches(lower)) {
                return true
            }

            // UUID (ex: 550e8400-e29b-41d4-a716-446655440000)
            if (Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""").matches(lower)) {
                return true
            }

            // Hash hexadecimal puro (16 ou mais caracteres hexadecimais, ex: MD5/SHA1/SHA256)
            val compactHex = lower.replace("-", "").replace("_", "")
            if (compactHex.length >= 16 && Regex("""^[0-9a-f]+$""").matches(compactHex)) {
                return true
            }

            // Token alfanumérico longo sem separadores legíveis (ex: tokens aleatórios >= 22 chars)
            if (trimmed.length >= 22 && !trimmed.contains('_') && !trimmed.contains('-') && !trimmed.contains(' ') &&
                Regex("""^[a-zA-Z0-9]+$""").matches(trimmed) &&
                trimmed.any { it.isDigit() } && trimmed.any { it.isLetter() }
            ) {
                return true
            }

            // Apenas dígitos numéricos (ex: IDs numéricos puros "123456789" ou timestamps)
            if (Regex("""^\d+$""").matches(trimmed)) {
                return true
            }

            return false
        }

        /**
         * Remove caracteres inválidos para o sistema de ficheiros do Android: \ / : * ? " < > |
         */
        fun sanitizeBaseName(raw: String): String {
            return raw
                .replace(INVALID_ANDROID_FS_CHARS, "")
                .replace(MULTIPLE_WHITESPACE_REGEX, " ")
                .trim()
                .trim('.')
                .trim()
        }

        /**
         * Remove repetidamente extensões de mídia conhecidas do final do nome base,
         * prevenindo extensões duplicadas como ".mp4.mp4" ou ".m3u8.mp4".
         */
        fun stripKnownExtensions(name: String): String {
            var current = name.trim().trim('.')
            while (true) {
                val updated = current.replace(KNOWN_MEDIA_EXTENSIONS_REGEX, "").trim().trim('.')
                if (updated == current || updated.isEmpty()) {
                    break
                }
                current = updated
            }
            return current
        }

        /**
         * Gera o nome padrão com timestamp quando nenhum identificador válido é encontrado:
         * "video_YYYYMMDD_HHmmss.mp4"
         */
        fun generateTimestampFallbackName(extension: String = "mp4", date: Date = Date()): String {
            val cleanExt = normalizeTargetExtension(extension)
            val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            return "video_${formatter.format(date)}.$cleanExt"
        }

        private fun normalizeTargetExtension(extension: String): String {
            val clean = extension.trim().removePrefix(".").lowercase(Locale.ROOT)
            return if (clean == "m4a") "m4a" else "mp4"
        }

        /**
         * Sanitiza um nome de ficheiro já existente garantindo:
         * - Remoção dos caracteres inválidos: \ / : * ? " < > |
         * - Extensão única ".mp4" (ou ".m4a" se explicitamente áudio), evitando ".mp4.mp4".
         * - Fallback para "video_YYYYMMDD_HHmmss.mp4" se vazio.
         */
        fun sanitizeAndroidFileName(rawFileName: String?, extension: String = "mp4"): String {
            val targetExt = normalizeTargetExtension(extension)
            val cleaned = sanitizeBaseName(rawFileName ?: "")
            val baseWithoutExt = stripKnownExtensions(cleaned)
            if (baseWithoutExt.isBlank()) {
                return generateTimestampFallbackName(targetExt)
            }
            return "$baseWithoutExt.$targetExt"
        }

        /**
         * Constrói o nome do ficheiro seguindo estritamente a ordem de prioridade:
         * - Prioridade 1 (Nome da URL / Servidor): Cabeçalho HTTP "Content-Disposition" (se presente)
         *   ou último segmento válido da URL (antes de "?"). Ex: ".../media/aula_01.mp4?token=xyz" -> "aula_01.mp4".
         * - Prioridade 2 (Atributo da tag <video>): Se a URL tiver nome genérico, utiliza apenas o atributo
         *   "title" ou "aria-label" presente diretamente no elemento <video> do HTML.
         * - Fallback: Padrão com timestamp "video_YYYYMMDD_HHmmss.mp4".
         * - NUNCA utiliza document.title ou domínio do site.
         */
        fun buildFileName(
            url: String,
            videoElementTitle: String? = null,
            extension: String = "mp4",
            contentDisposition: String? = null
        ): String {
            val targetExt = normalizeTargetExtension(extension)

            // Prioridade 1A: Cabeçalho HTTP Content-Disposition
            val fromContentDisposition = extractFileNameFromContentDisposition(contentDisposition)
            if (!fromContentDisposition.isNullOrBlank()) {
                return "$fromContentDisposition.$targetExt"
            }

            // Prioridade 1B: Último segmento válido da URL (antes de "?")
            val fromUrlSegment = extractValidUrlBaseName(url)
            if (!fromUrlSegment.isNullOrBlank()) {
                return "$fromUrlSegment.$targetExt"
            }

            // Prioridade 2: Atributo "title" ou "aria-label" diretamente do elemento <video>
            if (!videoElementTitle.isNullOrBlank()) {
                val cleanedVideoAttr = stripKnownExtensions(sanitizeBaseName(videoElementTitle)).take(120).trim()
                if (cleanedVideoAttr.isNotBlank() && !isGenericMediaIdentifier(cleanedVideoAttr)) {
                    return "$cleanedVideoAttr.$targetExt"
                }
            }

            // Fallback: "video_YYYYMMDD_HHmmss.mp4"
            return generateTimestampFallbackName(targetExt)
        }
    }
}
