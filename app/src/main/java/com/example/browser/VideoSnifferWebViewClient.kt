package com.example.browser

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.local.entity.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

class VideoSnifferWebViewClient(
    private val onVideoDetected: (url: String, fileName: String, extension: String) -> Unit,
    private val onRichMediaDetected: ((DetectedMediaPayload) -> Unit)? = null,
    private val onPageStartedCallback: (url: String) -> Unit = {},
    private val onPageFinishedCallback: (url: String, title: String?) -> Unit = { _, _ -> }
) : WebViewClient() {

    private val snifferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightInspections = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var currentMainPageUrl: String = ""

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank() && url != "about:blank") {
            currentMainPageUrl = url
        }
        url?.let { onPageStartedCallback(it) }
        injectMediaSnifferScript(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!url.isNullOrBlank() && url != "about:blank") {
            currentMainPageUrl = url
        }
        url?.let { onPageFinishedCallback(it, view?.title) }
        injectMediaSnifferScript(view)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!url.isNullOrBlank() && url != "about:blank") {
            currentMainPageUrl = url
        }
        url?.let { onPageFinishedCallback(it, view?.title) }
        injectMediaSnifferScript(view)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        super.onLoadResource(view, url)
        injectMediaSnifferScript(view)
    }

    private fun injectMediaSnifferScript(view: WebView?) {
        if (view == null) return
        view.post {
            try {
                view.evaluateJavascript(ACTIVE_MEDIA_SNIFFER_JS, null)
            } catch (_: Exception) {
                // Ignora se a WebView já tiver sido destruída
            }
        }
    }

    private fun emitDetectedMedia(payload: DetectedMediaPayload) {
        if (onRichMediaDetected != null) {
            onRichMediaDetected.invoke(payload)
        } else {
            onVideoDetected(payload.url, payload.fileName, payload.extension)
        }
    }

    /**
     * Captura via interceptação de rede em shouldInterceptRequest:
     * - Arquivos diretos (.mp4, .webm, .mkv)
     * - Manifestos de stream adaptativa (.m3u8 HLS e .mpd DASH)
     * - MIME types de manifesto (application/x-mpegurl, application/vnd.apple.mpegurl, application/dash+xml)
     * - Ignora segmentos individuais (.ts, .m4s, .mp4-init, .fmp4)
     * - NUNCA utiliza document.title ou domínio do site na nomenclatura do ficheiro.
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        val uri = request.url ?: return null
        val urlString = uri.toString()

        // 1. Ignorar protocolos incompatíveis (blob:, data:, etc.)
        val lowerProtocol = urlString.lowercase()
        if (lowerProtocol.startsWith("blob:") || lowerProtocol.startsWith("data:")) {
            return null
        }

        // 2. Ignorar segmentos individuais (.ts, .m4s, .mp4-init, .fmp4) para capturar apenas o manifesto
        if (isIndividualStreamSegment(urlString)) {
            return null
        }

        val reqHeaders = request.requestHeaders ?: emptyMap()
        val referer = reqHeaders["Referer"] ?: currentMainPageUrl
        val userAgent = reqHeaders["User-Agent"] ?: VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
        val contextHeaders = VideoSnifferJsBridge.buildContextHeaders(
            mediaUrl = urlString,
            pageReferer = referer,
            userAgent = userAgent
        )

        // 3. Detectar manifestos de Stream Adaptativa (.m3u8 HLS ou .mpd DASH)
        val streamType = extractAdaptiveStreamType(urlString)
        if (streamType != null) {
            inspectStreamManifestInBackground(urlString, streamType, contextHeaders)
            return null
        }

        // 4. Detecção de extensões diretas (.mp4, .webm, .mkv) no caminho ou antes de query strings / tokens assinados
        val directExtension = getDirectVideoExtension(urlString)
        if (directExtension != null) {
            val fileName = VideoSnifferJsBridge.buildFileName(
                url = urlString,
                videoElementTitle = null,
                extension = "mp4",
                contentDisposition = null
            )
            val resolvedTitle = fileName.substringBeforeLast('.')
            emitDetectedMedia(
                DetectedMediaPayload(
                    url = urlString,
                    fileName = fileName,
                    extension = "mp4",
                    isStream = false,
                    streamType = StreamType.DIRECT,
                    headers = contextHeaders,
                    title = resolvedTitle,
                    thumbnailUrl = null,
                    isDrmProtected = false
                )
            )
            return null
        }

        // 5. Inspeção assíncrona em background para requisições de mídia ou manifestos sem extensão evidente
        if (shouldInspectUrl(urlString, request)) {
            inspectUrlHeadersInBackground(urlString, contextHeaders)
        }

        return null
    }

    private fun isIndividualStreamSegment(url: String): Boolean {
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

    private fun extractAdaptiveStreamType(url: String): StreamType? {
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
     * Considera válida qualquer URL que contenha .mp4, .webm ou .mkv no caminho ou antes de tokens
     * de consulta (ex: ?expires=...&signature=...&token=...).
     */
    private fun getDirectVideoExtension(url: String): String? {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val lowerUrl = url.lowercase()

        return when {
            cleanPath.endsWith(".mp4") ||
                    cleanPath.contains(".mp4/") ||
                    lowerUrl.contains(".mp4?") ||
                    lowerUrl.contains(".mp4&") ||
                    lowerUrl.contains("mime=video/mp4") ||
                    lowerUrl.contains("mime=video%2fmp4") -> "mp4"

            cleanPath.endsWith(".webm") ||
                    cleanPath.contains(".webm/") ||
                    lowerUrl.contains(".webm?") ||
                    lowerUrl.contains(".webm&") ||
                    lowerUrl.contains("mime=video/webm") ||
                    lowerUrl.contains("mime=video%2fwebm") -> "webm"

            cleanPath.endsWith(".mkv") ||
                    cleanPath.contains(".mkv/") ||
                    lowerUrl.contains(".mkv?") ||
                    lowerUrl.contains(".mkv&") ||
                    lowerUrl.contains("mime=video/x-matroska") -> "mkv"

            else -> null
        }
    }

    private fun shouldInspectUrl(url: String, request: WebResourceRequest): Boolean {
        val lower = url.lowercase()
        if (lower.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg|ico|css|js|json|html|woff|woff2|ttf)(\\?.*)?$"))) {
            return false
        }

        val acceptHeader = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
        return acceptHeader.contains("video/") ||
                acceptHeader.contains("mpegurl") ||
                acceptHeader.contains("dash+xml") ||
                acceptHeader.contains("*/*") ||
                !request.isForMainFrame
    }

    private fun inspectStreamManifestInBackground(
        manifestUrl: String,
        streamType: StreamType,
        headers: Map<String, String>
    ) {
        if (!inFlightInspections.add(manifestUrl)) return
        snifferScope.launch {
            try {
                val inspection = VideoSnifferJsBridge.inspectManifestDrmAndHeaders(manifestUrl, headers)
                val ext = if (streamType == StreamType.HLS) "m3u8" else "mpd"
                val fileName = VideoSnifferJsBridge.buildFileName(
                    url = manifestUrl,
                    videoElementTitle = null,
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
                        thumbnailUrl = null,
                        isDrmProtected = inspection.isDrmProtected
                    )
                )
            } finally {
                inFlightInspections.remove(manifestUrl)
            }
        }
    }

    private fun inspectUrlHeadersInBackground(urlString: String, headers: Map<String, String>) {
        if (!inFlightInspections.add(urlString)) return
        snifferScope.launch {
            try {
                val connection = (URL(urlString).openConnection() as? HttpURLConnection)?.apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3000
                    readTimeout = 3000
                    instanceFollowRedirects = true
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                } ?: return@launch

                val contentType = connection.contentType?.lowercase() ?: ""
                val contentLength = connection.contentLengthLong.coerceAtLeast(0L)
                val contentDisposition = connection.getHeaderField("Content-Disposition")
                connection.disconnect()

                // Verifica MIME types de streams adaptativas (HLS / DASH)
                val streamType = when {
                    contentType.contains("application/x-mpegurl") ||
                            contentType.contains("application/vnd.apple.mpegurl") ||
                            contentType.contains("audio/mpegurl") -> StreamType.HLS
                    contentType.contains("application/dash+xml") -> StreamType.DASH
                    else -> null
                }

                if (streamType != null) {
                    val inspection = VideoSnifferJsBridge.inspectManifestDrmAndHeaders(urlString, headers)
                    val ext = if (streamType == StreamType.HLS) "m3u8" else "mpd"
                    val fileName = VideoSnifferJsBridge.buildFileName(
                        url = urlString,
                        videoElementTitle = null,
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
                            thumbnailUrl = null,
                            isDrmProtected = inspection.isDrmProtected
                        )
                    )
                    return@launch
                }

                val isSupportedVideo = contentType.contains("video/mp4") ||
                        contentType.contains("video/webm") ||
                        contentType.contains("video/x-matroska")

                if (isSupportedVideo) {
                    val fileName = VideoSnifferJsBridge.buildFileName(
                        url = urlString,
                        videoElementTitle = null,
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
                            thumbnailUrl = null,
                            isDrmProtected = false,
                            fileSizeBytes = contentLength
                        )
                    )
                }
            } catch (_: Exception) {
                // Silencia erros de requisição rápida em segundo plano
            } finally {
                inFlightInspections.remove(urlString)
            }
        }
    }

    companion object {
        private val ACTIVE_MEDIA_SNIFFER_JS = """
            (function() {
                var bridgeName = '${VideoSnifferJsBridge.INTERFACE_NAME}';
                var aliasBridgeName = '${VideoSnifferJsBridge.BRIDGE_ALIAS_NAME}';

                function resolveAbsoluteUrl(rawUrl) {
                    if (!rawUrl || typeof rawUrl !== 'string') return '';
                    var trimmed = rawUrl.replace(/\\\//g, '/').replace(/&amp;/g, '&').trim();
                    if (!trimmed) return '';
                    var lower = trimmed.toLowerCase();
                    if (lower.indexOf('blob:') === 0 || lower.indexOf('data:') === 0 || lower.indexOf('javascript:') === 0) {
                        return '';
                    }
                    try {
                        return new URL(trimmed, window.location.href).href;
                    } catch (e) {
                        return trimmed;
                    }
                }

                function getPagePosterFallback() {
                    try {
                        var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                        if (ogImg && ogImg.content) {
                            return resolveAbsoluteUrl(ogImg.content);
                        }
                    } catch (e) {}
                    return '';
                }

                // Extrai APENAS o atributo "title" ou "aria-label" presente diretamente no elemento <video>.
                // NUNCA extrai nem envia document.title ou domínio do site.
                function extractVideoElementTitle(node) {
                    try {
                        if (!node || !node.tagName) return '';
                        var tag = node.tagName.toUpperCase();
                        if (tag === 'VIDEO' && node.getAttribute) {
                            var videoAttr = node.getAttribute('title') || node.getAttribute('aria-label');
                            if (videoAttr && videoAttr.trim().length > 0) {
                                return videoAttr.trim();
                            }
                        } else if (tag === 'SOURCE') {
                            if (node.parentElement && node.parentElement.tagName &&
                                node.parentElement.tagName.toUpperCase() === 'VIDEO' &&
                                node.parentElement.getAttribute) {
                                var parentVideoAttr = node.parentElement.getAttribute('title') || node.parentElement.getAttribute('aria-label');
                                if (parentVideoAttr && parentVideoAttr.trim().length > 0) {
                                    return parentVideoAttr.trim();
                                }
                            }
                            if (node.getAttribute) {
                                var sourceAttr = node.getAttribute('title') || node.getAttribute('aria-label');
                                if (sourceAttr && sourceAttr.trim().length > 0) {
                                    return sourceAttr.trim();
                                }
                            }
                        }
                    } catch (e) {}
                    return '';
                }

                function extractNodePoster(node) {
                    try {
                        if (node) {
                            if (node.poster) return resolveAbsoluteUrl(node.poster);
                            var p = node.getAttribute && (node.getAttribute('poster') || node.getAttribute('data-poster') || node.getAttribute('data-thumb'));
                            if (p) return resolveAbsoluteUrl(p);
                            if (node.parentElement && node.parentElement.tagName && node.parentElement.tagName.toUpperCase() === 'VIDEO') {
                                var vp = node.parentElement.poster || node.parentElement.getAttribute('poster');
                                if (vp) return resolveAbsoluteUrl(vp);
                            }
                        }
                    } catch (e) {}
                    return getPagePosterFallback();
                }

                function isMediaOrStreamCandidate(url, isFromMediaElement) {
                    if (!url) return false;
                    var lower = url.toLowerCase();
                    if (lower.indexOf('blob:') === 0 || lower.indexOf('data:') === 0) return false;
                    if (lower.indexOf('http://') !== 0 && lower.indexOf('https://') !== 0) return false;

                    var cleanPath = lower.split('?')[0].split('#')[0];

                    // Ignora segmentos individuais (.ts, .m4s, .mp4-init, .fmp4)
                    if (cleanPath.endsWith('.ts') || cleanPath.endsWith('.m4s') ||
                        cleanPath.endsWith('.mp4-init') || cleanPath.endsWith('.fmp4') ||
                        lower.indexOf('.ts?') !== -1 || lower.indexOf('.m4s?') !== -1) {
                        return false;
                    }

                    // Suporta manifestos de stream adaptativa (.m3u8 HLS e .mpd DASH) e vídeos diretos (.mp4, .webm, .mkv)
                    var hasSupportedExtension =
                        cleanPath.indexOf('.m3u8') !== -1 ||
                        cleanPath.indexOf('.mpd') !== -1 ||
                        cleanPath.indexOf('.mp4') !== -1 ||
                        cleanPath.indexOf('.webm') !== -1 ||
                        cleanPath.indexOf('.mkv') !== -1 ||
                        lower.indexOf('.m3u8?') !== -1 ||
                        lower.indexOf('.mpd?') !== -1 ||
                        lower.indexOf('.mp4?') !== -1 ||
                        lower.indexOf('.webm?') !== -1 ||
                        lower.indexOf('.mkv?') !== -1 ||
                        lower.indexOf('.mp4&') !== -1 ||
                        lower.indexOf('.webm&') !== -1 ||
                        lower.indexOf('.mkv&') !== -1 ||
                        lower.indexOf('mime=video/mp4') !== -1 ||
                        lower.indexOf('mime=video%2fmp4') !== -1 ||
                        lower.indexOf('mime=video/webm') !== -1 ||
                        lower.indexOf('mime=video%2fwebm') !== -1 ||
                        lower.indexOf('mime=application/x-mpegurl') !== -1 ||
                        lower.indexOf('mime=application/vnd.apple.mpegurl') !== -1 ||
                        lower.indexOf('mime=application/dash+xml') !== -1;

                    if (hasSupportedExtension) return true;

                    return !!isFromMediaElement;
                }

                function reportMediaUrl(rawUrl, isFromMediaElement, videoElementTitle, customPoster) {
                    var resolved = resolveAbsoluteUrl(rawUrl);
                    if (!isMediaOrStreamCandidate(resolved, isFromMediaElement)) return;
                    try {
                        var videoAttrTitle = (typeof videoElementTitle === 'string') ? videoElementTitle.trim() : '';
                        var posterUrl = customPoster || getPagePosterFallback() || '';
                        var refererUrl = window.location.href || '';
                        if (window[aliasBridgeName] && typeof window[aliasBridgeName].reportVideoWithMeta === 'function') {
                            window[aliasBridgeName].reportVideoWithMeta(resolved, videoAttrTitle, posterUrl, refererUrl);
                        } else if (window[aliasBridgeName] && typeof window[aliasBridgeName].reportVideo === 'function') {
                            window[aliasBridgeName].reportVideo(resolved, videoAttrTitle);
                        } else if (window[bridgeName] && typeof window[bridgeName].onMediaDetected === 'function') {
                            window[bridgeName].onMediaDetected(resolved, videoAttrTitle);
                        }
                    } catch (e) {}
                }

                // --- Camada Adicional: Extração recursiva de vídeos e streams em respostas JSON de APIs ---
                var videoJsonKeys = {
                    'url': true,
                    'urls': true,
                    'hd': true,
                    'sd': true,
                    'file': true,
                    'files': true,
                    'src': true,
                    'video_url': true,
                    'videourl': true,
                    'mp4': true,
                    'webm': true,
                    'hls': true,
                    'm3u8': true,
                    'dash': true,
                    'mpd': true,
                    'manifest': true,
                    'play_url': true,
                    'download_url': true,
                    'stream_url': true
                };

                function containsVideoMarker(str) {
                    if (!str || typeof str !== 'string') return false;
                    var s = str.replace(/\\\//g, '/').toLowerCase();
                    if (s.indexOf('http://') === -1 && s.indexOf('https://') === -1 && s.indexOf('//') !== 0) {
                        return false;
                    }
                    return s.indexOf('.mp4') !== -1 ||
                           s.indexOf('.webm') !== -1 ||
                           s.indexOf('.mkv') !== -1 ||
                           s.indexOf('.m3u8') !== -1 ||
                           s.indexOf('.mpd') !== -1 ||
                           s.indexOf('mime=video/mp4') !== -1 ||
                           s.indexOf('mime=video/webm') !== -1 ||
                           s.indexOf('application/x-mpegurl') !== -1 ||
                           s.indexOf('application/dash+xml') !== -1;
                }

                function inspectJsonPayload(node, isTargetVideoKey, depth) {
                    if (!node || depth > 9) return;

                    if (typeof node === 'string') {
                        var unescaped = node.replace(/\\\//g, '/').trim();
                        if (containsVideoMarker(unescaped)) {
                            reportMediaUrl(unescaped, true, '', null);
                        } else if (isTargetVideoKey && (unescaped.indexOf('http://') === 0 || unescaped.indexOf('https://') === 0 || unescaped.indexOf('//') === 0)) {
                            reportMediaUrl(unescaped, false, '', null);
                        }
                        return;
                    }

                    if (Array.isArray(node)) {
                        for (var i = 0; i < node.length; i++) {
                            inspectJsonPayload(node[i], isTargetVideoKey, depth + 1);
                        }
                        return;
                    }

                    if (typeof node === 'object') {
                        var keys = Object.keys(node);
                        for (var k = 0; k < keys.length; k++) {
                            var key = keys[k];
                            var lowerKey = key.toLowerCase();
                            var matchedKey = !!videoJsonKeys[lowerKey];
                            inspectJsonPayload(node[key], matchedKey, depth + 1);
                        }
                    }
                }

                function processJsonText(rawText) {
                    if (!rawText || typeof rawText !== 'string') return;
                    try {
                        var parsed = JSON.parse(rawText);
                        inspectJsonPayload(parsed, false, 0);
                    } catch (e) {
                        try {
                            var cleaned = rawText.replace(/\\\//g, '/');
                            var regex = /https?:\/\/[^\s"'<>\\]+?\.(?:mp4|webm|mkv|m3u8|mpd)(?:[?#][^\s"'<>\\]*)?/gi;
                            var matches = cleaned.match(regex);
                            if (matches) {
                                for (var m = 0; m < matches.length; m++) {
                                    reportMediaUrl(matches[m], true, '', null);
                                }
                            }
                        } catch (ignore) {}
                    }
                }

                function inspectMediaNode(node) {
                    if (!node || node.nodeType !== 1) return;
                    var tag = (node.tagName || '').toUpperCase();
                    if (tag === 'VIDEO' || tag === 'SOURCE') {
                        var nTitle = extractVideoElementTitle(node);
                        var nPoster = extractNodePoster(node);
                        if (node.src) reportMediaUrl(node.src, true, nTitle, nPoster);
                        if (node.currentSrc) reportMediaUrl(node.currentSrc, true, nTitle, nPoster);
                        var attrSrc = node.getAttribute && (node.getAttribute('src') || node.getAttribute('data-src'));
                        if (attrSrc) reportMediaUrl(attrSrc, true, nTitle, nPoster);
                    } else if (tag === 'A') {
                        var href = node.href || (node.getAttribute && node.getAttribute('href'));
                        if (href) reportMediaUrl(href, false, '', null);
                    }

                    if (node.querySelectorAll) {
                        var mediaElements = node.querySelectorAll('video, source, a[href]');
                        for (var i = 0; i < mediaElements.length; i++) {
                            var el = mediaElements[i];
                            var elTag = (el.tagName || '').toUpperCase();
                            if (elTag === 'VIDEO' || elTag === 'SOURCE') {
                                var eTitle = extractVideoElementTitle(el);
                                var ePoster = extractNodePoster(el);
                                if (el.src) reportMediaUrl(el.src, true, eTitle, ePoster);
                                if (el.currentSrc) reportMediaUrl(el.currentSrc, true, eTitle, ePoster);
                                var sAttr = el.getAttribute('src') || el.getAttribute('data-src');
                                if (sAttr) reportMediaUrl(sAttr, true, eTitle, ePoster);
                            } else if (elTag === 'A') {
                                if (el.href) reportMediaUrl(el.href, false, '', null);
                            }
                        }
                    }
                }

                function scanAllMedia() {
                    if (document.documentElement) {
                        inspectMediaNode(document.documentElement);
                    }
                }

                window.__1dmScanMedia = scanAllMedia;

                if (!window.__1dmSnifferInstalled) {
                    window.__1dmSnifferInstalled = true;

                    // 1. Interceptar o setter de HTMLMediaElement.prototype.src
                    try {
                        if (typeof HTMLMediaElement !== 'undefined' && HTMLMediaElement.prototype) {
                            var srcDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                            if (srcDescriptor && srcDescriptor.set) {
                                Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                                    configurable: true,
                                    enumerable: srcDescriptor.enumerable,
                                    get: function() {
                                        return srcDescriptor.get ? srcDescriptor.get.call(this) : '';
                                    },
                                    set: function(val) {
                                        reportMediaUrl(val, true, extractVideoElementTitle(this), extractNodePoster(this));
                                        return srcDescriptor.set.call(this, val);
                                    }
                                });
                            }

                            var origPlay = HTMLMediaElement.prototype.play;
                            if (origPlay) {
                                HTMLMediaElement.prototype.play = function() {
                                    inspectMediaNode(this);
                                    return origPlay.apply(this, arguments);
                                };
                            }
                            var origLoad = HTMLMediaElement.prototype.load;
                            if (origLoad) {
                                HTMLMediaElement.prototype.load = function() {
                                    inspectMediaNode(this);
                                    return origLoad.apply(this, arguments);
                                };
                            }
                        }
                    } catch (e) {}

                    // Interceptar também Element.prototype.setAttribute para <video> e <source>
                    try {
                        var origSetAttribute = Element.prototype.setAttribute;
                        if (origSetAttribute) {
                            Element.prototype.setAttribute = function(name, value) {
                                var result = origSetAttribute.apply(this, arguments);
                                if (name && typeof name === 'string') {
                                    var lowerName = name.toLowerCase();
                                    if (lowerName === 'src' || lowerName === 'data-src') {
                                        var tag = (this.tagName || '').toUpperCase();
                                        if (tag === 'VIDEO' || tag === 'SOURCE') {
                                            reportMediaUrl(value, true, extractVideoElementTitle(this), extractNodePoster(this));
                                        }
                                    }
                                }
                                return result;
                            };
                        }
                    } catch (e) {}

                    // 2. Listeners de eventos de mídia em fase de captura
                    var mediaEvents = ['loadstart', 'loadedmetadata', 'canplay', 'play', 'playing'];
                    for (var eIdx = 0; eIdx < mediaEvents.length; eIdx++) {
                        document.addEventListener(mediaEvents[eIdx], function(ev) {
                            if (ev && ev.target) {
                                inspectMediaNode(ev.target);
                            }
                        }, true);
                    }

                    // 3. MutationObserver para monitorar nós adicionados dinamicamente na árvore HTML
                    try {
                        var observer = new MutationObserver(function(mutations) {
                            for (var m = 0; m < mutations.length; m++) {
                                var mutation = mutations[m];
                                if (mutation.type === 'attributes' && mutation.target) {
                                    inspectMediaNode(mutation.target);
                                } else if (mutation.addedNodes && mutation.addedNodes.length > 0) {
                                    for (var n = 0; n < mutation.addedNodes.length; n++) {
                                        inspectMediaNode(mutation.addedNodes[n]);
                                    }
                                }
                            }
                        });

                        var attachObserver = function() {
                            var rootNode = document.documentElement || document.body;
                            if (rootNode) {
                                observer.observe(rootNode, {
                                    childList: true,
                                    subtree: true,
                                    attributes: true,
                                    attributeFilter: ['src', 'data-src', 'href', 'poster', 'title', 'aria-label']
                                });
                            } else {
                                setTimeout(attachObserver, 100);
                            }
                        };
                        attachObserver();
                    } catch (e) {}

                    // 4. Hook em window.fetch para inspecionar respostas JSON e manifestos M3U8/MPD
                    try {
                        if (typeof window.fetch === 'function') {
                            var origFetch = window.fetch;
                            window.fetch = function(input) {
                                try {
                                    var reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                                    if (reqUrl && (reqUrl.indexOf('.m3u8') !== -1 || reqUrl.indexOf('.mpd') !== -1)) {
                                        reportMediaUrl(reqUrl, true, '', null);
                                    }
                                } catch (e) {}
                                return origFetch.apply(this, arguments).then(function(response) {
                                    try {
                                        if (response && typeof response.clone === 'function') {
                                            var contentType = (response.headers && response.headers.get('content-type'))
                                                ? response.headers.get('content-type').toLowerCase()
                                                : '';
                                            if (contentType.indexOf('mpegurl') !== -1 || contentType.indexOf('dash+xml') !== -1) {
                                                if (response.url) reportMediaUrl(response.url, true, '', null);
                                            } else if (contentType.indexOf('application/json') !== -1 ||
                                                contentType.indexOf('+json') !== -1 ||
                                                contentType.indexOf('text/json') !== -1 ||
                                                contentType.indexOf('text/plain') !== -1) {
                                                response.clone().text().then(function(bodyText) {
                                                    processJsonText(bodyText);
                                                }).catch(function() {});
                                            }
                                        }
                                    } catch (err) {}
                                    return response;
                                });
                            };
                        }
                    } catch (e) {}

                    // 5. Hook em XMLHttpRequest para inspecionar respostas JSON e manifestos M3U8/MPD
                    try {
                        if (typeof XMLHttpRequest !== 'undefined' && XMLHttpRequest.prototype) {
                            var origXhrOpen = XMLHttpRequest.prototype.open;
                            if (origXhrOpen) {
                                XMLHttpRequest.prototype.open = function(method, url) {
                                    this.__1dmUrl = url;
                                    if (url && typeof url === 'string' && (url.indexOf('.m3u8') !== -1 || url.indexOf('.mpd') !== -1)) {
                                        reportMediaUrl(url, true, '', null);
                                    }
                                    return origXhrOpen.apply(this, arguments);
                                };
                            }
                            var origXhrSend = XMLHttpRequest.prototype.send;
                            if (origXhrSend) {
                                XMLHttpRequest.prototype.send = function() {
                                    try {
                                        this.addEventListener('load', function() {
                                            try {
                                                var ct = (this.getResponseHeader && this.getResponseHeader('content-type'))
                                                    ? this.getResponseHeader('content-type').toLowerCase()
                                                    : '';
                                                if (ct.indexOf('mpegurl') !== -1 || ct.indexOf('dash+xml') !== -1) {
                                                    if (this.responseURL || this.__1dmUrl) {
                                                        reportMediaUrl(this.responseURL || this.__1dmUrl, true, '', null);
                                                    }
                                                } else if (this.responseType === 'json' && this.response) {
                                                    inspectJsonPayload(this.response, false, 0);
                                                } else if (!this.responseType || this.responseType === 'text') {
                                                    var txt = this.responseText;
                                                    if (txt && (ct.indexOf('application/json') !== -1 ||
                                                                ct.indexOf('json') !== -1 ||
                                                                txt.trim().indexOf('{') === 0 ||
                                                                txt.trim().indexOf('[') === 0)) {
                                                        processJsonText(txt);
                                                    }
                                                }
                                            } catch (xhrErr) {}
                                        });
                                    } catch (e) {}
                                    return origXhrSend.apply(this, arguments);
                                };
                            }
                        }
                    } catch (e) {}
                }

                // 6. Varredura inicial imediata
                scanAllMedia();
            })();
        """.trimIndent()
    }
}
