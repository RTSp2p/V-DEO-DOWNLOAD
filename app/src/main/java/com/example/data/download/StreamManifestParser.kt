package com.example.data.download

import com.example.browser.VideoSnifferJsBridge
import com.example.data.local.entity.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StreamQualityOption(
    val id: String,
    val label: String,
    val resolutionHeight: Int,
    val bandwidthBps: Long,
    val videoStreamUrl: String?,
    val audioStreamUrl: String?,
    val isAudioOnly: Boolean = false,
    val estimatedSizeBytes: Long = 0L
)

data class HlsSegmentSpec(
    val url: String,
    val durationSec: Double,
    val keyMethod: String? = null,
    val keyUri: String? = null,
    val keyIvHex: String? = null,
    val initSegmentUrl: String? = null,
    val sequenceIndex: Int = 0,
    val byteRange: String? = null,
    val hasDiscontinuityBefore: Boolean = false
)

data class ManifestParseResult(
    val isDrmProtected: Boolean,
    val drmMessage: String? = null,
    val options: List<StreamQualityOption> = emptyList()
)

class StreamManifestParser(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    /**
     * Faz o download e parse de um manifesto .m3u8 (HLS) ou .mpd (DASH) usando os headers capturados,
     * detecta DRM preventivamente e retorna as opções de qualidade (1080p, 720p, 480p, etc.)
     * mais a opção de "Apenas Áudio".
     */
    suspend fun parseManifest(
        manifestUrl: String,
        streamType: StreamType,
        headers: Map<String, String>
    ): ManifestParseResult = withContext(Dispatchers.IO) {
        try {
            val content = fetchText(manifestUrl, headers)
                ?: return@withContext ManifestParseResult(
                    isDrmProtected = false,
                    options = buildFallbackOptions(manifestUrl)
                )

            if (VideoSnifferJsBridge.containsCommercialDrmMarkers(content)) {
                return@withContext ManifestParseResult(
                    isDrmProtected = true,
                    drmMessage = "Conteúdo protegido por direitos autorais (DRM Widevine / PlayReady / SAMPLE-AES).",
                    options = emptyList()
                )
            }

            return@withContext when (streamType) {
                StreamType.HLS -> parseHlsManifest(manifestUrl, content)
                StreamType.DASH -> parseDashManifest(manifestUrl, content)
                StreamType.DIRECT -> ManifestParseResult(
                    isDrmProtected = false,
                    options = buildFallbackOptions(manifestUrl)
                )
            }
        } catch (_: Exception) {
            ManifestParseResult(
                isDrmProtected = false,
                options = buildFallbackOptions(manifestUrl)
            )
        }
    }

    /**
     * Resolve uma Media Playlist HLS (ou a variante única selecionada de uma Master Playlist)
     * em uma lista ordenada e estritamente deduplicada de segmentos (.ts / .m4s).
     *
     * Regras aplicadas:
     * 1. Seleção estrita de uma única variante: se for Master Playlist (#EXT-X-STREAM-INF),
     *    seleciona apenas uma playlist de resolução/bitrate (ou áudio se isAudioTrack=true)
     *    e NUNCA concatena fragmentos de qualidades diferentes em série.
     * 2. Deduplicação de fragmentos (.ts / .m4s) antes do download para eliminar blocos repetidos.
     * 3. Deteção de fim de playlist / loop: caso a playlist não contenha #EXT-X-ENDLIST
     *    (típico de vídeos em loop no reprodutor web) ou repita segmentos após #EXT-X-DISCONTINUITY,
     *    captura estritamente uma única iteração completa dos fragmentos e encerra a recolha imediatamente.
     */
    suspend fun resolveHlsSegments(
        playlistUrl: String,
        headers: Map<String, String>,
        isAudioTrack: Boolean = false
    ): List<HlsSegmentSpec> = withContext(Dispatchers.IO) {
        resolveHlsSegmentsInternal(
            playlistUrl = playlistUrl,
            headers = headers,
            isAudioTrack = isAudioTrack,
            depth = 0
        )
    }

    private suspend fun resolveHlsSegmentsInternal(
        playlistUrl: String,
        headers: Map<String, String>,
        isAudioTrack: Boolean,
        depth: Int
    ): List<HlsSegmentSpec> {
        if (depth > 4) return emptyList()

        val text = fetchText(playlistUrl, headers) ?: return emptyList()
        if (VideoSnifferJsBridge.containsCommercialDrmMarkers(text)) {
            throw SecurityException("DRM_PROTECTED")
        }

        return parseHlsMediaPlaylistText(
            playlistUrl = playlistUrl,
            text = text,
            isAudioTrack = isAudioTrack,
            onResolveVariantPlaylist = { targetVariantUrl ->
                resolveHlsSegmentsInternal(
                    playlistUrl = targetVariantUrl,
                    headers = headers,
                    isAudioTrack = isAudioTrack,
                    depth = depth + 1
                )
            }
        )
    }

    /**
     * Analisa o conteúdo textual de uma playlist HLS (Master ou Media Playlist),
     * aplicando seleção estrita de variante única, deduplicação de fragmentos e
     * interrupção imediata ao fim da primeira iteração em playlists de loop (sem #EXT-X-ENDLIST).
     */
    internal suspend fun parseHlsMediaPlaylistText(
        playlistUrl: String,
        text: String,
        isAudioTrack: Boolean = false,
        onResolveVariantPlaylist: suspend (String) -> List<HlsSegmentSpec> = { emptyList() }
    ): List<HlsSegmentSpec> {
        // 1. Se ainda for uma Master Playlist (#EXT-X-STREAM-INF), seleciona estritamente UMA variante
        if (text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val parsed = parseHlsManifest(playlistUrl, text)
            val chosenVariantUrl = if (isAudioTrack) {
                parsed.options.firstOrNull { it.isAudioOnly }?.audioStreamUrl
                    ?: parsed.options.firstOrNull { !it.audioStreamUrl.isNullOrBlank() }?.audioStreamUrl
                    ?: parsed.options.firstOrNull()?.videoStreamUrl
            } else {
                parsed.options.firstOrNull { !it.isAudioOnly }?.videoStreamUrl
            }

            if (!chosenVariantUrl.isNullOrBlank() && chosenVariantUrl != playlistUrl) {
                return onResolveVariantPlaylist(chosenVariantUrl)
            }
        }

        val lines = text.lines().map { it.trim() }
        val hasEndList = lines.any { it.startsWith("#EXT-X-ENDLIST", ignoreCase = true) }

        val segments = mutableListOf<HlsSegmentSpec>()
        val seenExactKeys = LinkedHashSet<String>()
        val seenCanonicalKeys = LinkedHashSet<String>()

        var currentKeyMethod: String? = null
        var currentKeyUri: String? = null
        var currentKeyIv: String? = null
        var currentInitMapUrl: String? = null
        var currentByteRange: String? = null
        var currentDuration = 0.0
        var mediaSequence = 0
        var segmentCounter = 0
        var pendingDiscontinuity = false
        var sawDiscontinuityAfterSegments = false
        var firstNestedM3u8Url: String? = null

        for (line in lines) {
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    // Fim explícito da playlist: encerra imediatamente a leitura
                    break
                }

                line.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    // Se a playlist NÃO possui #EXT-X-ENDLIST e já coletamos os fragmentos da primeira iteração,
                    // uma descontinuidade indica o reinício do loop no reprodutor web -> encerra imediatamente!
                    if (!hasEndList && segments.isNotEmpty()) {
                        break
                    }
                    if (segments.isNotEmpty()) {
                        sawDiscontinuityAfterSegments = true
                    }
                    pendingDiscontinuity = true
                }

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }

                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val uriAttr = extractAttribute(line, "URI")
                    if (!uriAttr.isNullOrBlank()) {
                        currentInitMapUrl = resolveRelativeUrl(playlistUrl, uriAttr)
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    currentByteRange = line.substringAfter(":").trim()
                }

                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val method = extractAttribute(line, "METHOD")
                    if (method.equals("NONE", ignoreCase = true)) {
                        currentKeyMethod = null
                        currentKeyUri = null
                        currentKeyIv = null
                    } else {
                        currentKeyMethod = method
                        val uriAttr = extractAttribute(line, "URI")
                        currentKeyUri = if (!uriAttr.isNullOrBlank()) {
                            resolveRelativeUrl(playlistUrl, uriAttr)
                        } else {
                            null
                        }
                        currentKeyIv = extractAttribute(line, "IV")
                    }
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val durStr = line.substringAfter(":").substringBefore(",").trim()
                    currentDuration = durStr.toDoubleOrNull() ?: 0.0
                }

                !line.startsWith("#") -> {
                    val resolvedUrl = resolveRelativeUrl(playlistUrl, line)
                    val cleanPathLower = resolvedUrl.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)

                    // Nunca adiciona uma sub-playlist (.m3u8) como se fosse um segmento .ts/.m4s
                    if (cleanPathLower.endsWith(".m3u8")) {
                        if (firstNestedM3u8Url == null && resolvedUrl != playlistUrl) {
                            firstNestedM3u8Url = resolvedUrl
                        }
                        currentDuration = 0.0
                        currentByteRange = null
                        continue
                    }

                    val exactKey = "$resolvedUrl|${currentByteRange ?: ""}"
                    val canonicalKey = buildCanonicalSegmentKey(resolvedUrl, currentByteRange)

                    val isDuplicateSegment = !seenExactKeys.add(exactKey) || !seenCanonicalKeys.add(canonicalKey)
                    if (isDuplicateSegment) {
                        // Se não há #EXT-X-ENDLIST ou já houve #EXT-X-DISCONTINUITY, repetir um segmento
                        // significa que o vídeo em loop reiniciou a 2ª iteração: encerra a recolha imediatamente!
                        if (!hasEndList || sawDiscontinuityAfterSegments) {
                            break
                        }
                        // Mesmo em playlists com #EXT-X-ENDLIST, ignora blocos duplicados
                        currentDuration = 0.0
                        currentByteRange = null
                        pendingDiscontinuity = false
                        continue
                    }

                    segments.add(
                        HlsSegmentSpec(
                            url = resolvedUrl,
                            durationSec = currentDuration.coerceAtLeast(0.0),
                            keyMethod = currentKeyMethod,
                            keyUri = currentKeyUri,
                            keyIvHex = currentKeyIv,
                            initSegmentUrl = currentInitMapUrl,
                            sequenceIndex = mediaSequence + segmentCounter,
                            byteRange = currentByteRange,
                            hasDiscontinuityBefore = pendingDiscontinuity
                        )
                    )
                    segmentCounter++
                    currentDuration = 0.0
                    currentByteRange = null
                    pendingDiscontinuity = false
                }
            }
        }

        if (segments.isEmpty() && !firstNestedM3u8Url.isNullOrBlank()) {
            return onResolveVariantPlaylist(firstNestedM3u8Url)
        }

        return segments
    }

    /**
     * Constrói uma chave canônica para deduplicação de segmentos (.ts / .m4s),
     * ignorando parâmetros de query voláteis de loop/cache, mas preservando parâmetros
     * identificadores de segmento (seg, id, part, chunk, num, seq, index, byteRange).
     */
    private fun buildCanonicalSegmentKey(segmentUrl: String, byteRange: String?): String {
        val basePath = segmentUrl.substringBefore('?').substringBefore('#')
        val query = segmentUrl.substringAfter('?', "").substringBefore('#')
        val identifyingParams = if (query.isNotEmpty()) {
            query.split('&')
                .mapNotNull { param ->
                    val key = param.substringBefore('=').lowercase(Locale.ROOT)
                    if (key in SEGMENT_IDENTIFIER_QUERY_KEYS) param else null
                }
                .sorted()
                .joinToString("&")
        } else {
            ""
        }
        return "$basePath?$identifyingParams|${byteRange ?: ""}"
    }

    /**
     * Resolve segmentos de um manifesto DASH (.mpd) ou URL de representação.
     * Seleciona estritamente UMA única Representation (a escolhida ou a de maior resolução/bitrate)
     * e deduplica os segmentos, nunca concatenando múltiplas qualidades em série.
     */
    suspend fun resolveDashSegments(
        manifestOrTrackUrl: String,
        headers: Map<String, String>,
        targetHeight: Int = 0,
        isAudioTrack: Boolean = false
    ): List<HlsSegmentSpec> = withContext(Dispatchers.IO) {
        val clean = manifestOrTrackUrl.substringBefore('?').lowercase(Locale.ROOT)
        if (!clean.endsWith(".mpd") && !manifestOrTrackUrl.contains(".mpd", ignoreCase = true)) {
            return@withContext listOf(
                HlsSegmentSpec(
                    url = manifestOrTrackUrl,
                    durationSec = 0.0
                )
            )
        }

        val xml = fetchText(manifestOrTrackUrl, headers) ?: return@withContext emptyList()
        if (VideoSnifferJsBridge.containsCommercialDrmMarkers(xml)) {
            throw SecurityException("DRM_PROTECTED")
        }

        parseDashRepresentationSegments(
            manifestUrl = manifestOrTrackUrl,
            xml = xml,
            targetHeight = targetHeight,
            isAudioTrack = isAudioTrack
        )
    }

    private data class DashRepresentationCandidate(
        val id: String,
        val height: Int,
        val bandwidth: Long,
        val isAudio: Boolean,
        val segments: List<HlsSegmentSpec>
    )

    internal fun parseDashRepresentationSegments(
        manifestUrl: String,
        xml: String,
        targetHeight: Int = 0,
        isAudioTrack: Boolean = false
    ): List<HlsSegmentSpec> {
        val candidates = mutableListOf<DashRepresentationCandidate>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentAdaptationMime = ""
            var adaptationInitUrl: String? = null

            var inRepresentation = false
            var repId = ""
            var repHeight = 0
            var repBandwidth = 0L
            var repIsAudio = false
            var repInitUrl: String? = null
            var repSegments = mutableListOf<HlsSegmentSpec>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "AdaptationSet" -> {
                                currentAdaptationMime = (parser.getAttributeValue(null, "mimeType")
                                    ?: parser.getAttributeValue(null, "contentType") ?: "").lowercase(Locale.ROOT)
                                adaptationInitUrl = null
                            }
                            "Representation" -> {
                                inRepresentation = true
                                repId = parser.getAttributeValue(null, "id") ?: "${candidates.size}"
                                repHeight = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                                repBandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                                val repMime = (parser.getAttributeValue(null, "mimeType")
                                    ?: currentAdaptationMime).lowercase(Locale.ROOT)
                                val codecs = (parser.getAttributeValue(null, "codecs") ?: "").lowercase(Locale.ROOT)
                                repIsAudio = repMime.contains("audio") ||
                                        currentAdaptationMime.contains("audio") ||
                                        (repHeight == 0 && codecs.startsWith("mp4a"))
                                repInitUrl = adaptationInitUrl
                                repSegments = mutableListOf()
                            }
                            "Initialization" -> {
                                val sourceUrl = parser.getAttributeValue(null, "sourceURL")
                                if (!sourceUrl.isNullOrBlank()) {
                                    val resolved = resolveRelativeUrl(manifestUrl, sourceUrl)
                                    if (inRepresentation) {
                                        repInitUrl = resolved
                                    } else {
                                        adaptationInitUrl = resolved
                                    }
                                }
                            }
                            "BaseURL" -> {
                                if (inRepresentation) {
                                    val baseText = parser.nextText()?.trim() ?: ""
                                    if (baseText.isNotEmpty()) {
                                        val resolved = resolveRelativeUrl(manifestUrl, baseText)
                                        repSegments.add(
                                            HlsSegmentSpec(
                                                url = resolved,
                                                durationSec = 0.0,
                                                initSegmentUrl = repInitUrl,
                                                sequenceIndex = repSegments.size
                                            )
                                        )
                                    }
                                }
                            }
                            "SegmentURL" -> {
                                if (inRepresentation) {
                                    val media = parser.getAttributeValue(null, "media")
                                    if (!media.isNullOrBlank()) {
                                        repSegments.add(
                                            HlsSegmentSpec(
                                                url = resolveRelativeUrl(manifestUrl, media),
                                                durationSec = 0.0,
                                                initSegmentUrl = repInitUrl,
                                                sequenceIndex = repSegments.size
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "Representation" -> {
                                if (inRepresentation && repSegments.isNotEmpty()) {
                                    candidates.add(
                                        DashRepresentationCandidate(
                                            id = repId,
                                            height = repHeight,
                                            bandwidth = repBandwidth,
                                            isAudio = repIsAudio,
                                            segments = repSegments.distinctBy { it.url }
                                        )
                                    )
                                }
                                inRepresentation = false
                            }
                            "AdaptationSet" -> {
                                currentAdaptationMime = ""
                                adaptationInitUrl = null
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
        }

        // Seleciona estritamente UMA única Representation (nunca concatena múltiplas qualidades)
        val matchingTypeCandidates = candidates.filter { it.isAudio == isAudioTrack }
        val chosen = if (isAudioTrack) {
            matchingTypeCandidates.maxByOrNull { it.bandwidth }
        } else {
            if (targetHeight > 0) {
                matchingTypeCandidates.filter { it.height == targetHeight }.maxByOrNull { it.bandwidth }
                    ?: matchingTypeCandidates.maxWithOrNull(compareBy<DashRepresentationCandidate> { it.height }.thenBy { it.bandwidth })
            } else {
                matchingTypeCandidates.maxWithOrNull(compareBy<DashRepresentationCandidate> { it.height }.thenBy { it.bandwidth })
            }
        }

        return chosen?.segments?.distinctBy { it.url } ?: emptyList()
    }

    internal fun parseHlsManifest(manifestUrl: String, content: String): ManifestParseResult {
        val lines = content.lines().map { it.trim() }
        val audioGroupMap = mutableMapOf<String, String>()
        var standaloneAudioUrl: String? = null

        // 1. Coleta faixas de áudio separadas (#EXT-X-MEDIA:TYPE=AUDIO), priorizando DEFAULT=YES por GROUP-ID
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) && line.contains("TYPE=AUDIO", ignoreCase = true)) {
                val groupId = extractAttribute(line, "GROUP-ID")
                val uriAttr = extractAttribute(line, "URI")
                val isDefault = extractAttribute(line, "DEFAULT")?.equals("YES", ignoreCase = true) == true
                if (!uriAttr.isNullOrBlank()) {
                    val resolvedAudio = resolveRelativeUrl(manifestUrl, uriAttr)
                    if (!groupId.isNullOrBlank()) {
                        if (!audioGroupMap.containsKey(groupId) || isDefault) {
                            audioGroupMap[groupId] = resolvedAudio
                        }
                    }
                    if (standaloneAudioUrl == null || isDefault) {
                        standaloneAudioUrl = resolvedAudio
                    }
                }
            }
        }

        // 2. Coleta variantes de vídeo (#EXT-X-STREAM-INF)
        val rawOptions = mutableListOf<StreamQualityOption>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val bandwidth = extractAttribute(line, "BANDWIDTH")?.toLongOrNull()
                    ?: extractAttribute(line, "AVERAGE-BANDWIDTH")?.toLongOrNull()
                    ?: 0L
                val resolution = extractAttribute(line, "RESOLUTION") // ex: 1920x1080
                val codecs = extractAttribute(line, "CODECS") ?: ""
                val audioGroup = extractAttribute(line, "AUDIO")

                val height = resolution?.substringAfter('x', "")?.substringBefore(',')?.toIntOrNull() ?: 0

                // Procura a próxima linha não comentada para a URL da variante
                var j = i + 1
                var variantUriLine: String? = null
                while (j < lines.size) {
                    if (lines[j].isNotEmpty() && !lines[j].startsWith("#")) {
                        variantUriLine = lines[j]
                        break
                    }
                    j++
                }

                if (!variantUriLine.isNullOrBlank()) {
                    val variantUrl = resolveRelativeUrl(manifestUrl, variantUriLine)
                    val pairedAudio = if (!audioGroup.isNullOrBlank()) {
                        audioGroupMap[audioGroup] ?: standaloneAudioUrl
                    } else {
                        standaloneAudioUrl
                    }
                    val isAudioStreamOnly = height == 0 &&
                            codecs.contains("mp4a", ignoreCase = true) &&
                            !codecs.contains("avc1", ignoreCase = true) &&
                            !codecs.contains("hvc1", ignoreCase = true) &&
                            !codecs.contains("hev1", ignoreCase = true) &&
                            !codecs.contains("vp09", ignoreCase = true) &&
                            !codecs.contains("av01", ignoreCase = true)

                    if (isAudioStreamOnly) {
                        if (standaloneAudioUrl == null) standaloneAudioUrl = variantUrl
                    } else {
                        val effectiveAudio = pairedAudio?.takeIf { it != variantUrl }
                        val label = buildQualityLabel(height, bandwidth, effectiveAudio != null)
                        rawOptions.add(
                            StreamQualityOption(
                                id = "hls_${height}_${bandwidth}",
                                label = label,
                                resolutionHeight = if (height > 0) height else 720,
                                bandwidthBps = bandwidth,
                                videoStreamUrl = variantUrl,
                                audioStreamUrl = effectiveAudio,
                                isAudioOnly = false
                            )
                        )
                    }
                    i = j
                }
            }
            i++
        }

        // Ordena da melhor resolução/bitrate para a menor e garante apenas uma variante por resolução
        val sortedVideoOptions = rawOptions
            .sortedWith(compareByDescending<StreamQualityOption> { it.resolutionHeight }.thenByDescending { it.bandwidthBps })
            .distinctBy { "${it.resolutionHeight}_${it.videoStreamUrl}" }
            .toMutableList()

        // Se era uma Media Playlist direta (sem #EXT-X-STREAM-INF), oferece qualidade Original
        if (sortedVideoOptions.isEmpty()) {
            sortedVideoOptions.add(
                StreamQualityOption(
                    id = "hls_original",
                    label = "Qualidade Original (Full HD / Auto) - MP4",
                    resolutionHeight = 1080,
                    bandwidthBps = 5_000_000L,
                    videoStreamUrl = manifestUrl,
                    audioStreamUrl = standaloneAudioUrl?.takeIf { it != manifestUrl },
                    isAudioOnly = false
                )
            )
        }

        // Adiciona opção de áudio ao final
        val audioSourceUrl = standaloneAudioUrl ?: sortedVideoOptions.first().videoStreamUrl
        sortedVideoOptions.add(
            StreamQualityOption(
                id = "audio_only_mp4",
                label = "Apenas Áudio (.mp4)",
                resolutionHeight = 0,
                bandwidthBps = 128_000L,
                videoStreamUrl = null,
                audioStreamUrl = audioSourceUrl,
                isAudioOnly = true
            )
        )

        return ManifestParseResult(
            isDrmProtected = false,
            options = sortedVideoOptions
        )
    }

    private fun parseDashManifest(manifestUrl: String, xml: String): ManifestParseResult {
        val videoOptions = mutableListOf<StreamQualityOption>()
        var bestAudioUrl: String? = null
        var bestAudioBandwidth = -1L

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentAdaptationMime = ""
            var currentRepHeight = 0
            var currentRepBandwidth = 0L
            var currentRepId = ""
            var inRepresentation = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "AdaptationSet" -> {
                                currentAdaptationMime = (parser.getAttributeValue(null, "mimeType")
                                    ?: parser.getAttributeValue(null, "contentType") ?: "").lowercase(Locale.ROOT)
                            }
                            "Representation" -> {
                                inRepresentation = true
                                currentRepId = parser.getAttributeValue(null, "id") ?: "${System.nanoTime()}"
                                currentRepHeight = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                                currentRepBandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                                val repMime = (parser.getAttributeValue(null, "mimeType") ?: currentAdaptationMime).lowercase(Locale.ROOT)
                                if (repMime.contains("video") && currentRepHeight > 0) {
                                    val label = buildQualityLabel(currentRepHeight, currentRepBandwidth, true)
                                    videoOptions.add(
                                        StreamQualityOption(
                                            id = "dash_${currentRepId}_${currentRepHeight}",
                                            label = label,
                                            resolutionHeight = currentRepHeight,
                                            bandwidthBps = currentRepBandwidth,
                                            videoStreamUrl = manifestUrl,
                                            audioStreamUrl = manifestUrl,
                                            isAudioOnly = false
                                        )
                                    )
                                }
                            }
                            "BaseURL" -> {
                                if (inRepresentation) {
                                    val baseUrlText = parser.nextText()?.trim() ?: ""
                                    if (baseUrlText.isNotEmpty()) {
                                        val resolved = resolveRelativeUrl(manifestUrl, baseUrlText)
                                        if (currentAdaptationMime.contains("audio")) {
                                            if (currentRepBandwidth >= bestAudioBandwidth) {
                                                bestAudioBandwidth = currentRepBandwidth
                                                bestAudioUrl = resolved
                                            }
                                        } else if (videoOptions.isNotEmpty()) {
                                            val last = videoOptions.removeAt(videoOptions.lastIndex)
                                            videoOptions.add(last.copy(videoStreamUrl = resolved))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "Representation") {
                            inRepresentation = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
        }

        val sorted = videoOptions
            .sortedWith(compareByDescending<StreamQualityOption> { it.resolutionHeight }.thenByDescending { it.bandwidthBps })
            .distinctBy { it.resolutionHeight }
            .map { it.copy(audioStreamUrl = bestAudioUrl ?: it.audioStreamUrl) }
            .toMutableList()

        if (sorted.isEmpty()) {
            sorted.add(
                StreamQualityOption(
                    id = "dash_auto",
                    label = "Qualidade Original (DASH Multiplexado) - MP4",
                    resolutionHeight = 1080,
                    bandwidthBps = 4_500_000L,
                    videoStreamUrl = manifestUrl,
                    audioStreamUrl = bestAudioUrl,
                    isAudioOnly = false
                )
            )
        }

        sorted.add(
            StreamQualityOption(
                id = "dash_audio_only",
                label = "Apenas Áudio (.mp4)",
                resolutionHeight = 0,
                bandwidthBps = 128_000L,
                videoStreamUrl = null,
                audioStreamUrl = bestAudioUrl ?: manifestUrl,
                isAudioOnly = true
            )
        )

        return ManifestParseResult(
            isDrmProtected = false,
            options = sorted
        )
    }

    private fun buildFallbackOptions(manifestUrl: String): List<StreamQualityOption> {
        return listOf(
            StreamQualityOption(
                id = "stream_1080p",
                label = "1080p Full HD (MP4 Multiplexado)",
                resolutionHeight = 1080,
                bandwidthBps = 5_000_000L,
                videoStreamUrl = manifestUrl,
                audioStreamUrl = null,
                isAudioOnly = false
            ),
            StreamQualityOption(
                id = "stream_720p",
                label = "720p HD (MP4 Multiplexado)",
                resolutionHeight = 720,
                bandwidthBps = 2_800_000L,
                videoStreamUrl = manifestUrl,
                audioStreamUrl = null,
                isAudioOnly = false
            ),
            StreamQualityOption(
                id = "stream_480p",
                label = "480p SD (MP4 Multiplexado)",
                resolutionHeight = 480,
                bandwidthBps = 1_400_000L,
                videoStreamUrl = manifestUrl,
                audioStreamUrl = null,
                isAudioOnly = false
            ),
            StreamQualityOption(
                id = "stream_audio_only",
                label = "Apenas Áudio (.mp4)",
                resolutionHeight = 0,
                bandwidthBps = 128_000L,
                videoStreamUrl = null,
                audioStreamUrl = manifestUrl,
                isAudioOnly = true
            )
        )
    }

    private fun buildQualityLabel(height: Int, bandwidthBps: Long, hasSeparateAudio: Boolean): String {
        val resTag = when {
            height >= 2160 -> "${height}p (4K UHD)"
            height >= 1440 -> "${height}p (2K QHD)"
            height >= 1080 -> "${height}p (Full HD)"
            height >= 720 -> "${height}p (HD)"
            height > 0 -> "${height}p (SD)"
            else -> "Automático"
        }
        val mbps = if (bandwidthBps > 0) {
            String.format(Locale.US, " • %.1f Mbps", bandwidthBps / 1_000_000.0)
        } else {
            ""
        }
        val muxTag = if (hasSeparateAudio) " • Vídeo+Áudio" else " • MP4"
        return "$resTag$mbps$muxTag"
    }

    private fun extractAttribute(line: String, key: String): String? {
        val regex = Regex("""$key=(?:"([^"]*)"|([^,\s]*))""", RegexOption.IGNORE_CASE)
        val match = regex.find(line) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeOrAbsolute: String): String {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/')
            if (trimmed.startsWith("/")) {
                val origin = Regex("""^(https?://[^/]+)""").find(baseUrl)?.value ?: baseDir
                "$origin$trimmed"
            } else {
                "$baseDir/$trimmed"
            }
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        val reqBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        if (!headers.containsKey("User-Agent")) {
            reqBuilder.header("User-Agent", VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT)
        }
        return okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }

    companion object {
        private val SEGMENT_IDENTIFIER_QUERY_KEYS = setOf(
            "seg", "segment", "id", "part", "chunk", "frag", "num", "seq",
            "sequence", "index", "idx", "range", "bytestart", "byteend", "offset"
        )
    }
}
