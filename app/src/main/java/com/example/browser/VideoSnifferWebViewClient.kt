package com.example.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLDecoder

class VideoSnifferWebViewClient(
    private val onVideoDetected: (url: String, fileName: String, extension: String) -> Unit,
    private val onPageStartedCallback: (url: String) -> Unit = {},
    private val onPageFinishedCallback: (url: String, title: String?) -> Unit = { _, _ -> }
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { onPageStartedCallback(it) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url?.let { onPageFinishedCallback(it, view?.title) }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        val uri = request.url ?: return null
        val urlString = uri.toString()

        // 1. Explicitly discard streaming manifests and fragments
        if (isStreamingFragmentOrManifest(urlString)) {
            return null
        }

        // 2. Strict Filter: Check for full video extensions (.mp4, .webm, .mkv) or explicit MIME types
        val extension = getValidVideoExtension(urlString, request)
        if (extension != null) {
            val fileName = extractFileName(urlString, extension)
            onVideoDetected(urlString, fileName, extension)
        }

        // Return null so the WebView continues loading normally
        return null
    }

    private fun isStreamingFragmentOrManifest(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
                lower.contains(".ts") ||
                lower.contains(".mpd") ||
                lower.contains(".m4s") ||
                lower.contains(".fmp4") ||
                lower.contains("chunk") ||
                lower.contains("segment")
    }

    private fun getValidVideoExtension(url: String, request: WebResourceRequest): String? {
        val cleanPath = try {
            val uri = Uri.parse(url)
            uri.path?.lowercase() ?: ""
        } catch (e: Exception) {
            url.lowercase()
        }

        when {
            cleanPath.endsWith(".mp4") -> return "mp4"
            cleanPath.endsWith(".webm") -> return "webm"
            cleanPath.endsWith(".mkv") -> return "mkv"
        }

        // Check if URL contains extension before query params (e.g. video.mp4?hash=xyz)
        val pathWithoutQuery = url.substringBefore('?').lowercase()
        when {
            pathWithoutQuery.endsWith(".mp4") -> return "mp4"
            pathWithoutQuery.endsWith(".webm") -> return "webm"
            pathWithoutQuery.endsWith(".mkv") -> return "mkv"
        }

        // Check request headers for explicit video MIME types
        val headers = request.requestHeaders ?: emptyMap()
        for ((key, value) in headers) {
            val valLower = value.lowercase()
            if (key.equals("Accept", ignoreCase = true) || key.equals("Content-Type", ignoreCase = true)) {
                when {
                    valLower.contains("video/mp4") -> return "mp4"
                    valLower.contains("video/webm") -> return "webm"
                    valLower.contains("video/x-matroska") -> return "mkv"
                }
            }
        }

        return null
    }

    private fun extractFileName(url: String, extension: String): String {
        return try {
            val uri = Uri.parse(url)
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank()) {
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
}
