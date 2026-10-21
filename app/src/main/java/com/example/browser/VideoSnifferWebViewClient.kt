package com.example.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class VideoSnifferWebViewClient(
    private val onVideoDetected: (url: String, fileName: String, extension: String) -> Unit,
    private val onPageStartedCallback: (url: String) -> Unit = {},
    private val onPageFinishedCallback: (url: String, title: String?) -> Unit = { _, _ -> }
) : WebViewClient() {

    private val snifferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { onPageStartedCallback(it) }
        injectMediaSnifferScript(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url?.let { onPageFinishedCallback(it, view?.title) }
        injectMediaSnifferScript(view)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        url?.let { onPageFinishedCallback(it, view?.title) }
        injectMediaSnifferScript(view)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        super.onLoadResource(view, url)
        injectMediaSnifferScript(view)
    }

    /**
     * Injeta o script de monitoramento ativo no DOM em paralelo:
     * 1. Hook no setter de HTMLMediaElement.prototype.src e Element.prototype.setAttribute (Intacto)
     * 2. Hook em HTMLMediaElement.prototype.play e load (Intacto)
     * 3. MutationObserver para elementos <video> e <source> inseridos dinamicamente (Intacto)
     * 4. Varredura inicial e contínua de elementos de mídia existentes na página (Intacto)
     * 5. Camada Adicional: Interceptação de respostas JSON via fetch e XMLHttpRequest
     *    procurando chaves ("url", "urls", "hd", "sd", "file", etc.) ou strings com .mp4/.webm/.mkv
     */
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

    /**
     * Mecanismo secundário de captura via interceptação de rede para links diretos,
     * URLs assinadas e requisições HTTP convencionais.
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

        // 2. Descartar manifestos e formatos fragmentados explícitos (HLS / DASH)
        if (isStreamingFragmentOrManifest(urlString)) {
            return null
        }

        // 3. Detecção de extensões (.mp4, .webm, .mkv) no caminho ou antes de query strings / tokens assinados
        val directExtension = getDirectVideoExtension(urlString)
        if (directExtension != null) {
            val fileName = extractFileName(urlString, directExtension)
            onVideoDetected(urlString, fileName, directExtension)
            return null
        }

        // 4. Inspeção assíncrona em background para requisições de mídia sem extensão evidente
        if (shouldInspectUrl(urlString, request)) {
            inspectUrlHeadersInBackground(urlString, request.requestHeaders)
        }

        return null
    }

    private fun isStreamingFragmentOrManifest(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
                lower.contains(".ts") ||
                lower.contains(".mpd") ||
                lower.contains(".m4s") ||
                lower.contains(".fmp4")
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
        // Ignora assets estáticos comuns para poupar conexões
        if (lower.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg|ico|css|js|json|html|xml|woff|woff2|ttf)(\\?.*)?$"))) {
            return false
        }

        val acceptHeader = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
        return acceptHeader.contains("video/") ||
                acceptHeader.contains("*/*") ||
                !request.isForMainFrame
    }

    private fun inspectUrlHeadersInBackground(urlString: String, clientHeaders: Map<String, String>?) {
        snifferScope.launch {
            try {
                val cookie = clientHeaders?.get("Cookie") ?: try {
                    CookieManager.getInstance().getCookie(urlString)
                } catch (_: Exception) {
                    null
                }

                val connection = (URL(urlString).openConnection() as? HttpURLConnection)?.apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3000
                    readTimeout = 3000
                    instanceFollowRedirects = true
                    if (!cookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookie)
                    }
                    val ua = clientHeaders?.get("User-Agent") ?: VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
                    setRequestProperty("User-Agent", ua)
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
                    val fileName = extractFileName(urlString, detectedExt)
                    onVideoDetected(urlString, fileName, detectedExt)
                }
            } catch (_: Exception) {
                // Silencia erros de requisição rápida em segundo plano
            }
        }
    }

    private fun extractFileName(url: String, extension: String): String {
        return try {
            val cleanUrlBeforeQuery = url.substringBefore('?').substringBefore('#')
            val uri = Uri.parse(cleanUrlBeforeQuery)
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank() && !segment.contains("=")) {
                val decoded = URLDecoder.decode(segment, "UTF-8")
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val extDot = ".$extension"
                if (decoded.endsWith(extDot, ignoreCase = true)) {
                    decoded
                } else {
                    "${decoded.substringBeforeLast('.')}$extDot"
                }
            } else {
                "video_${System.currentTimeMillis()}.$extension"
            }
        } catch (e: Exception) {
            "video_${System.currentTimeMillis()}.$extension"
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

                function isDirectVideoCandidate(url, isFromMediaElement) {
                    if (!url) return false;
                    var lower = url.toLowerCase();
                    if (lower.indexOf('blob:') === 0 || lower.indexOf('data:') === 0) return false;
                    if (lower.indexOf('http://') !== 0 && lower.indexOf('https://') !== 0) return false;

                    // Descarta manifestos e fragmentos de streaming
                    var cleanPath = lower.split('?')[0].split('#')[0];
                    if (cleanPath.endsWith('.m3u8') || cleanPath.endsWith('.ts') ||
                        cleanPath.endsWith('.mpd') || cleanPath.endsWith('.m4s') ||
                        cleanPath.endsWith('.fmp4') ||
                        lower.indexOf('.m3u8?') !== -1 || lower.indexOf('.mpd?') !== -1) {
                        return false;
                    }

                    // Suporte a URLs assinadas e Query Parameters complexos:
                    // Considera válida qualquer URL que contenha .mp4, .webm ou .mkv no caminho
                    // ou antes de tokens de consulta (?expires=...&signature=...&token=...)
                    var hasSupportedExtension =
                        cleanPath.indexOf('.mp4') !== -1 ||
                        cleanPath.indexOf('.webm') !== -1 ||
                        cleanPath.indexOf('.mkv') !== -1 ||
                        lower.indexOf('.mp4?') !== -1 ||
                        lower.indexOf('.webm?') !== -1 ||
                        lower.indexOf('.mkv?') !== -1 ||
                        lower.indexOf('.mp4&') !== -1 ||
                        lower.indexOf('.webm&') !== -1 ||
                        lower.indexOf('.mkv&') !== -1 ||
                        lower.indexOf('mime=video/mp4') !== -1 ||
                        lower.indexOf('mime=video%2fmp4') !== -1 ||
                        lower.indexOf('mime=video/webm') !== -1 ||
                        lower.indexOf('mime=video%2fwebm') !== -1;

                    if (hasSupportedExtension) return true;

                    // Se vier diretamente de <video>, <source>, HTMLMediaElement.prototype.src
                    // ou de uma propriedade dedicada de vídeo no JSON, encaminha para a ponte
                    return !!isFromMediaElement;
                }

                function reportMediaUrl(rawUrl, isFromMediaElement) {
                    var resolved = resolveAbsoluteUrl(rawUrl);
                    if (!isDirectVideoCandidate(resolved, isFromMediaElement)) return;
                    try {
                        var pageTitle = document.title || '';
                        if (window[aliasBridgeName] && typeof window[aliasBridgeName].reportVideo === 'function') {
                            window[aliasBridgeName].reportVideo(resolved, pageTitle);
                        } else if (window[bridgeName] && typeof window[bridgeName].onMediaDetected === 'function') {
                            window[bridgeName].onMediaDetected(resolved, pageTitle);
                        }
                    } catch (e) {}
                }

                // --- Camada Adicional: Extração recursiva de vídeos em respostas JSON de APIs ---
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
                           s.indexOf('mime=video/mp4') !== -1 ||
                           s.indexOf('mime=video/webm') !== -1;
                }

                function inspectJsonPayload(node, isTargetVideoKey, depth) {
                    if (!node || depth > 9) return;

                    if (typeof node === 'string') {
                        var unescaped = node.replace(/\\\//g, '/').trim();
                        if (containsVideoMarker(unescaped)) {
                            reportMediaUrl(unescaped, true);
                        } else if (isTargetVideoKey && (unescaped.indexOf('http://') === 0 || unescaped.indexOf('https://') === 0 || unescaped.indexOf('//') === 0)) {
                            // Se a chave no JSON é explicitamente "hd", "sd", "file", "video_url", etc.
                            reportMediaUrl(unescaped, false);
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
                        // Fallback seguro: busca regex caso o payload tenha prefixo ou JSON parcial
                        try {
                            var cleaned = rawText.replace(/\\\//g, '/');
                            var regex = /https?:\/\/[^\s"'<>\\]+?\.(?:mp4|webm|mkv)(?:[?#][^\s"'<>\\]*)?/gi;
                            var matches = cleaned.match(regex);
                            if (matches) {
                                for (var m = 0; m < matches.length; m++) {
                                    reportMediaUrl(matches[m], true);
                                }
                            }
                        } catch (ignore) {}
                    }
                }

                function inspectMediaNode(node) {
                    if (!node || node.nodeType !== 1) return;
                    var tag = (node.tagName || '').toUpperCase();
                    if (tag === 'VIDEO' || tag === 'SOURCE') {
                        if (node.src) reportMediaUrl(node.src, true);
                        if (node.currentSrc) reportMediaUrl(node.currentSrc, true);
                        var attrSrc = node.getAttribute && (node.getAttribute('src') || node.getAttribute('data-src'));
                        if (attrSrc) reportMediaUrl(attrSrc, true);
                    } else if (tag === 'A') {
                        var href = node.href || (node.getAttribute && node.getAttribute('href'));
                        if (href) reportMediaUrl(href, false);
                    }

                    if (node.querySelectorAll) {
                        var mediaElements = node.querySelectorAll('video, source, a[href]');
                        for (var i = 0; i < mediaElements.length; i++) {
                            var el = mediaElements[i];
                            var elTag = (el.tagName || '').toUpperCase();
                            if (elTag === 'VIDEO' || elTag === 'SOURCE') {
                                if (el.src) reportMediaUrl(el.src, true);
                                if (el.currentSrc) reportMediaUrl(el.currentSrc, true);
                                var sAttr = el.getAttribute('src') || el.getAttribute('data-src');
                                if (sAttr) reportMediaUrl(sAttr, true);
                            } else if (elTag === 'A') {
                                if (el.href) reportMediaUrl(el.href, false);
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

                    // 1. Interceptar o setter de HTMLMediaElement.prototype.src (Mantido 100% Intacto)
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
                                        reportMediaUrl(val, true);
                                        return srcDescriptor.set.call(this, val);
                                    }
                                });
                            }

                            // Hook em play() e load() para capturar currentSrc resolvido pelo player
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

                    // Interceptar também Element.prototype.setAttribute para <video> e <source> (Mantido 100% Intacto)
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
                                            reportMediaUrl(value, true);
                                        }
                                    }
                                }
                                return result;
                            };
                        }
                    } catch (e) {}

                    // 2. Listeners de eventos de mídia em fase de captura (Mantido 100% Intacto)
                    var mediaEvents = ['loadstart', 'loadedmetadata', 'canplay', 'play', 'playing'];
                    for (var eIdx = 0; eIdx < mediaEvents.length; eIdx++) {
                        document.addEventListener(mediaEvents[eIdx], function(ev) {
                            if (ev && ev.target) {
                                inspectMediaNode(ev.target);
                            }
                        }, true);
                    }

                    // 3. MutationObserver para monitorar nós adicionados dinamicamente na árvore HTML (Mantido 100% Intacto)
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
                                    attributeFilter: ['src', 'data-src', 'href']
                                });
                            } else {
                                setTimeout(attachObserver, 100);
                            }
                        };
                        attachObserver();
                    } catch (e) {}

                    // 4. Camada Adicional: Hook em window.fetch para inspecionar respostas application/json
                    try {
                        if (typeof window.fetch === 'function') {
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                return origFetch.apply(this, arguments).then(function(response) {
                                    try {
                                        if (response && typeof response.clone === 'function') {
                                            var contentType = (response.headers && response.headers.get('content-type'))
                                                ? response.headers.get('content-type').toLowerCase()
                                                : '';
                                            if (contentType.indexOf('application/json') !== -1 ||
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

                    // 5. Camada Adicional: Hook em XMLHttpRequest para inspecionar respostas application/json
                    try {
                        if (typeof XMLHttpRequest !== 'undefined' && XMLHttpRequest.prototype) {
                            var origXhrSend = XMLHttpRequest.prototype.send;
                            if (origXhrSend) {
                                XMLHttpRequest.prototype.send = function() {
                                    try {
                                        this.addEventListener('load', function() {
                                            try {
                                                var ct = (this.getResponseHeader && this.getResponseHeader('content-type'))
                                                    ? this.getResponseHeader('content-type').toLowerCase()
                                                    : '';
                                                if (this.responseType === 'json' && this.response) {
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
