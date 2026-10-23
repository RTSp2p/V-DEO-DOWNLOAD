package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.VideoHunterApplication
import com.example.browser.VideoSnifferJsBridge
import com.example.data.download.HlsSegmentSpec
import com.example.data.download.MediaMuxerEngine
import com.example.data.download.StreamManifestParser
import com.example.data.local.entity.CapturedVideoEntity
import com.example.data.local.entity.StreamType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * Motor de Download Híbrido em Foreground Service:
 * 1. Fluxo de Arquivo Direto (.mp4, .webm, .mkv): Download via OkHttp com suporte a
 *    Range Requests ("Range: bytes=downloaded-"), extração prioritária de Content-Disposition
 *    e sanitização estrita para extensão ".mp4" única.
 * 2. Fluxo de Stream Adaptativa (.m3u8 HLS e .mpd DASH):
 *    - Seleciona estritamente uma única variante de resolução/bitrate (nunca concatena variantes).
 *    - Deduplica a lista de URLs de fragmentos (.ts / .m4s) antes do download.
 *    - Baixa vídeo e áudio separados em paralelo e faz a multiplexação simultânea.
 *    - Normaliza o timestamp inicial para zero e ignora descontinuidades artificiais,
 *      garantindo que a duração final reflita a soma exata das durações declaradas em #EXTINF.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val pausedVideoIds = ConcurrentHashMap.newKeySet<Long>()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val manifestParser: StreamManifestParser by lazy {
        StreamManifestParser(okHttpClient)
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val videoId = intent.getLongExtra(EXTRA_VIDEO_ID, -1L)

        when (action) {
            ACTION_START_DOWNLOAD, ACTION_RESUME_DOWNLOAD -> {
                if (videoId != -1L) {
                    pausedVideoIds.remove(videoId)
                    ensureForegroundNotification("Iniciando download...", 0, 0L)
                    launchDownloadJob(videoId)
                }
            }
            ACTION_PAUSE_DOWNLOAD -> {
                if (videoId != -1L) {
                    pauseDownloadJob(videoId)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                if (videoId != -1L) {
                    cancelDownloadJob(videoId)
                }
            }
            ACTION_CANCEL_ALL -> {
                cancelAllJobs()
            }
        }

        return START_NOT_STICKY
    }

    private fun launchDownloadJob(videoId: Long) {
        if (activeJobs[videoId]?.isActive == true) return

        val app = applicationContext as? VideoHunterApplication ?: return
        val dao = app.database.videoDao()

        val job = serviceScope.launch {
            val video = dao.getVideoById(videoId) ?: return@launch

            if (video.isDrmProtected) {
                dao.updateStatus(video.id, CapturedVideoEntity.STATUS_DRM_BLOCKED)
                checkAndStopServiceIfIdle()
                return@launch
            }

            try {
                if (video.isStream || video.streamType != StreamType.DIRECT) {
                    executeAdaptiveStreamDownload(app, video)
                } else {
                    executeDirectFileDownload(app, video)
                }
            } catch (ce: CancellationException) {
                if (pausedVideoIds.contains(videoId)) {
                    dao.updateStatus(videoId, CapturedVideoEntity.STATUS_PAUSED)
                } else {
                    dao.updateStatus(videoId, CapturedVideoEntity.STATUS_FAILED)
                }
            } catch (se: SecurityException) {
                dao.updateStatus(videoId, CapturedVideoEntity.STATUS_DRM_BLOCKED)
            } catch (e: Exception) {
                e.printStackTrace()
                dao.updateStatus(videoId, CapturedVideoEntity.STATUS_FAILED)
            } finally {
                activeJobs.remove(videoId)
                checkAndStopServiceIfIdle()
            }
        }
        activeJobs[videoId] = job
    }

    private fun pauseDownloadJob(videoId: Long) {
        pausedVideoIds.add(videoId)
        activeJobs.remove(videoId)?.cancel()
        val app = applicationContext as? VideoHunterApplication
        serviceScope.launch {
            app?.database?.videoDao()?.updateStatus(videoId, CapturedVideoEntity.STATUS_PAUSED)
            checkAndStopServiceIfIdle()
        }
    }

    private fun cancelDownloadJob(videoId: Long) {
        pausedVideoIds.remove(videoId)
        activeJobs.remove(videoId)?.cancel()
        val app = applicationContext as? VideoHunterApplication
        serviceScope.launch {
            app?.database?.videoDao()?.updateStatus(videoId, CapturedVideoEntity.STATUS_FAILED)
            File(cacheDir, "stream_work_$videoId").deleteRecursively()
            File(cacheDir, "direct_part_$videoId.tmp").delete()
            checkAndStopServiceIfIdle()
        }
    }

    private fun cancelAllJobs() {
        pausedVideoIds.clear()
        val ids = activeJobs.keys.toList()
        for (id in ids) {
            activeJobs.remove(id)?.cancel()
            File(cacheDir, "stream_work_$id").deleteRecursively()
            File(cacheDir, "direct_part_$id.tmp").delete()
        }
        val app = applicationContext as? VideoHunterApplication
        serviceScope.launch {
            app?.database?.videoDao()?.cancelAllDownloading()
            checkAndStopServiceIfIdle()
        }
    }

    /**
     * Fluxo 1: Download de Arquivo Direto via OkHttp com suporte a Range Requests,
     * verificação prioritária do cabeçalho HTTP Content-Disposition e terminação única em ".mp4".
     */
    private suspend fun executeDirectFileDownload(
        app: VideoHunterApplication,
        video: CapturedVideoEntity
    ) = coroutineScope {
        val dao = app.database.videoDao()
        val tempFile = File(cacheDir, "direct_part_${video.id}.tmp")
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        val reqBuilder = Request.Builder().url(video.videoUrl).get()
        applyAnti403Headers(reqBuilder, video)
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        var serverContentDisposition: String? = null

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code}")
            }

            serverContentDisposition = response.header("Content-Disposition")
            val isPartial = response.code == 206
            val body = response.body ?: throw IllegalStateException("Empty body")
            val contentLength = body.contentLength()
            val startOffset = if (isPartial) existingBytes else 0L
            val totalBytes = if (contentLength > 0) startOffset + contentLength else video.fileSizeBytes

            if (!isPartial && tempFile.exists()) {
                tempFile.delete()
            }

            RandomAccessFile(tempFile, "rw").use { raf ->
                if (startOffset > 0L) {
                    raf.seek(startOffset)
                } else {
                    raf.setLength(0L)
                }

                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var downloaded = startOffset
                var lastReportTime = System.currentTimeMillis()
                var bytesSinceLastReport = 0L

                dao.updateDownloadProgress(
                    id = video.id,
                    status = CapturedVideoEntity.STATUS_DOWNLOADING,
                    progress = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99) else 0,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes,
                    speedBytesPerSec = 0L
                )

                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (!isActive || pausedVideoIds.contains(video.id)) {
                        throw CancellationException("Paused or cancelled")
                    }
                    raf.write(buffer, 0, read)
                    downloaded += read
                    bytesSinceLastReport += read

                    val now = System.currentTimeMillis()
                    val elapsedMs = now - lastReportTime
                    if (elapsedMs >= 500L) {
                        val speed = (bytesSinceLastReport * 1000L) / elapsedMs.coerceAtLeast(1L)
                        val prog = if (totalBytes > 0) {
                            ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        dao.updateDownloadProgress(
                            id = video.id,
                            status = CapturedVideoEntity.STATUS_DOWNLOADING,
                            progress = prog,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speed
                        )
                        updateNotificationProgress(video.id, video.fileName, prog, speed, false)
                        lastReportTime = now
                        bytesSinceLastReport = 0L
                    }
                }
            }
        }

        // Prioridade 1: Content-Disposition do servidor ou segmento válido da URL -> Prioridade 2: atributo <video> -> Timestamp
        val finalFileName = app.downloadHelper.resolveAndSanitizeFileName(
            videoUrl = video.videoUrl,
            candidateFileName = video.fileName,
            contentDisposition = serverContentDisposition
        )
        val mimeType = "video/mp4"
        val customFolderUri = app.settingsRepository.getDownloadFolderUriSnapshot()

        val exportedUri = MediaMuxerEngine.exportToPublicStorage(
            context = applicationContext,
            sourceFile = tempFile,
            finalFileName = finalFileName,
            mimeType = mimeType,
            customSafFolderUri = customFolderUri
        ) ?: throw IllegalStateException("Falha ao exportar vídeo para pasta pública")

        val finalSize = tempFile.length()
        tempFile.delete()

        dao.updateDownloadCompletedById(
            id = video.id,
            localUri = exportedUri,
            fileSizeBytes = finalSize,
            finalFileName = finalFileName,
            finalFileType = "mp4"
        )
        showCompletedNotification(video.id, finalFileName)
    }

    /**
     * Fluxo 2: Download de Stream Adaptativa (.m3u8 HLS e .mpd DASH) com:
     * - Seleção estrita de uma única variante de resolução/bitrate
     * - Deduplicação de URLs de fragmentos (.ts / .m4s) antes do download
     * - Download paralelo de vídeo e áudio quando em faixas separadas
     * - Multiplexação simultânea e normalização de timestamps baseada na soma das tags #EXTINF
     * - Terminação única em ".mp4"
     */
    private suspend fun executeAdaptiveStreamDownload(
        app: VideoHunterApplication,
        video: CapturedVideoEntity
    ) = coroutineScope {
        val dao = app.database.videoDao()
        val workDir = File(cacheDir, "stream_work_${video.id}").apply {
            if (!exists()) mkdirs()
        }

        try {
            var targetVideoUrl = video.variantVideoUrl
            var targetAudioUrl = video.variantAudioUrl
            val isAudioOnly = video.isAudioOnly

            // Seleciona estritamente UMA única variante do manifesto mestre (a escolhida ou a melhor disponível)
            if (targetVideoUrl.isNullOrBlank() && targetAudioUrl.isNullOrBlank()) {
                val parseResult = manifestParser.parseManifest(
                    manifestUrl = video.videoUrl,
                    streamType = video.streamType,
                    headers = video.headers
                )
                if (parseResult.isDrmProtected) {
                    throw SecurityException("DRM_PROTECTED")
                }
                val bestOption = if (isAudioOnly) {
                    parseResult.options.firstOrNull { it.isAudioOnly } ?: parseResult.options.firstOrNull()
                } else {
                    parseResult.options.firstOrNull { !it.isAudioOnly } ?: parseResult.options.firstOrNull()
                }
                targetVideoUrl = bestOption?.videoStreamUrl ?: if (!isAudioOnly) video.videoUrl else null
                targetAudioUrl = bestOption?.audioStreamUrl ?: if (isAudioOnly) video.videoUrl else null
            }

            // Resolve e deduplica a lista de fragmentos de vídeo
            val videoSegments = if (!isAudioOnly && !targetVideoUrl.isNullOrBlank()) {
                val resolved = if (video.streamType == StreamType.DASH) {
                    manifestParser.resolveDashSegments(targetVideoUrl, video.headers, isAudioTrack = false)
                } else {
                    manifestParser.resolveHlsSegments(targetVideoUrl, video.headers, isAudioTrack = false)
                }
                resolved.distinctBy { "${it.url}|${it.byteRange ?: ""}" }
            } else {
                emptyList()
            }

            // Resolve e deduplica a lista de fragmentos de áudio (quando em faixa separada ou modo apenas áudio)
            val audioSegments = if (!targetAudioUrl.isNullOrBlank() && targetAudioUrl != targetVideoUrl) {
                val resolved = if (video.streamType == StreamType.DASH) {
                    manifestParser.resolveDashSegments(targetAudioUrl, video.headers, isAudioTrack = true)
                } else {
                    manifestParser.resolveHlsSegments(targetAudioUrl, video.headers, isAudioTrack = true)
                }
                resolved.distinctBy { "${it.url}|${it.byteRange ?: ""}" }
            } else if (isAudioOnly && !targetVideoUrl.isNullOrBlank()) {
                manifestParser.resolveHlsSegments(targetVideoUrl, video.headers, isAudioTrack = true)
                    .distinctBy { "${it.url}|${it.byteRange ?: ""}" }
            } else {
                emptyList()
            }

            // Calcula a duração esperada exata a partir da soma das durações declaradas nas tags #EXTINF
            val primarySegmentsForDuration = if (videoSegments.isNotEmpty()) videoSegments else audioSegments
            val declaredDurationSec = primarySegmentsForDuration.sumOf { it.durationSec }
            val expectedDurationUs = if (declaredDurationSec > 0.0) {
                (declaredDurationSec * 1_000_000.0).roundToLong()
            } else {
                0L
            }

            val totalSegmentsCount = (videoSegments.size + audioSegments.size).coerceAtLeast(1)
            val completedSegments = AtomicLong(0L)
            val totalDownloadedBytes = AtomicLong(0L)
            val keyCache = ConcurrentHashMap<String, ByteArray>()
            var lastSpeedCheckTime = System.currentTimeMillis()
            val bytesSinceLastCheck = AtomicLong(0L)

            suspend fun onSegmentDownloaded(segBytes: Int) {
                val done = completedSegments.incrementAndGet()
                val totalBytesNow = totalDownloadedBytes.addAndGet(segBytes.toLong())
                val deltaBytes = bytesSinceLastCheck.addAndGet(segBytes.toLong())
                val now = System.currentTimeMillis()
                val elapsed = now - lastSpeedCheckTime
                if (elapsed >= 450L || done == totalSegmentsCount.toLong()) {
                    val speed = (deltaBytes * 1000L) / elapsed.coerceAtLeast(1L)
                    val prog = ((done * 90) / totalSegmentsCount).toInt().coerceIn(1, 92)
                    dao.updateDownloadProgress(
                        id = video.id,
                        status = CapturedVideoEntity.STATUS_DOWNLOADING,
                        progress = prog,
                        downloadedBytes = totalBytesNow,
                        totalBytes = 0L,
                        speedBytesPerSec = speed
                    )
                    updateNotificationProgress(video.id, video.fileName, prog, speed, false)
                    lastSpeedCheckTime = now
                    bytesSinceLastCheck.set(0L)
                }
            }

            // Download em paralelo das faixas separadas de vídeo e áudio
            val videoJoinedFileDeferred = async {
                if (videoSegments.isEmpty()) return@async null
                downloadAndAssembleTrack(
                    trackName = "video",
                    segments = videoSegments,
                    video = video,
                    workDir = workDir,
                    keyCache = keyCache,
                    onSegmentBytes = { onSegmentDownloaded(it) }
                )
            }

            val audioJoinedFileDeferred = async {
                if (audioSegments.isEmpty()) return@async null
                downloadAndAssembleTrack(
                    trackName = "audio",
                    segments = audioSegments,
                    video = video,
                    workDir = workDir,
                    keyCache = keyCache,
                    onSegmentBytes = { onSegmentDownloaded(it) }
                )
            }

            val rawVideoTrackFile = videoJoinedFileDeferred.await()
            val rawAudioTrackFile = audioJoinedFileDeferred.await()

            if (rawVideoTrackFile == null && rawAudioTrackFile == null) {
                throw IllegalStateException("Nenhum segmento de stream pôde ser baixado")
            }

            // Etapa de Multiplexação / Remuxing em faixas simultâneas para container .mp4
            dao.updateDownloadProgress(
                id = video.id,
                status = CapturedVideoEntity.STATUS_MUXING,
                progress = 95,
                downloadedBytes = totalDownloadedBytes.get(),
                totalBytes = totalDownloadedBytes.get(),
                speedBytesPerSec = 0L
            )
            updateNotificationProgress(video.id, video.fileName, 95, 0L, true)

            val finalFileName = app.downloadHelper.resolveAndSanitizeFileName(
                videoUrl = video.videoUrl,
                candidateFileName = video.fileName
            )
            val muxedOutputFile = File(workDir, "final_muxed.mp4")

            val muxSuccess = MediaMuxerEngine.remuxOrMultiplexToMp4(
                videoSourceFile = rawVideoTrackFile,
                audioSourceFile = rawAudioTrackFile,
                outputFile = muxedOutputFile,
                isAudioOnly = isAudioOnly,
                expectedDurationUs = expectedDurationUs
            )

            if (!muxSuccess || !muxedOutputFile.exists()) {
                throw IllegalStateException("Falha na multiplexação final do arquivo")
            }

            val customFolderUri = app.settingsRepository.getDownloadFolderUriSnapshot()

            val exportedUri = MediaMuxerEngine.exportToPublicStorage(
                context = applicationContext,
                sourceFile = muxedOutputFile,
                finalFileName = finalFileName,
                mimeType = "video/mp4",
                customSafFolderUri = customFolderUri
            ) ?: throw IllegalStateException("Falha ao exportar vídeo multiplexado")

            val finalSizeBytes = muxedOutputFile.length()

            dao.updateDownloadCompletedById(
                id = video.id,
                localUri = exportedUri,
                fileSizeBytes = finalSizeBytes,
                finalFileName = finalFileName,
                finalFileType = "mp4"
            )
            showCompletedNotification(video.id, finalFileName)
        } finally {
            // Limpeza rigorosa de todos os segmentos temporários (.ts / .m4s) do cacheDir
            workDir.deleteRecursively()
        }
    }

    private suspend fun downloadAndAssembleTrack(
        trackName: String,
        segments: List<HlsSegmentSpec>,
        video: CapturedVideoEntity,
        workDir: File,
        keyCache: ConcurrentHashMap<String, ByteArray>,
        onSegmentBytes: suspend (Int) -> Unit
    ): File = coroutineScope {
        val trackDir = File(workDir, trackName).apply { mkdirs() }
        var initSegmentFile: File? = null

        val firstInitUrl = segments.firstOrNull()?.initSegmentUrl
        if (!firstInitUrl.isNullOrBlank()) {
            val initBytes = fetchRawBytes(firstInitUrl, video, null)
            if (initBytes != null && initBytes.isNotEmpty()) {
                initSegmentFile = File(trackDir, "init.mp4").apply {
                    writeBytes(initBytes)
                }
            }
        }

        // Deduplicação final antes de baixar cada segmento
        val uniqueSegments = segments.distinctBy { "${it.url}|${it.byteRange ?: ""}" }
        val segmentFiles = mutableListOf<File>()

        for ((idx, seg) in uniqueSegments.withIndex()) {
            if (!isActive || pausedVideoIds.contains(video.id)) {
                throw CancellationException("Download paused or cancelled")
            }

            val segFile = File(trackDir, "seg_%05d.chunk".format(Locale.US, idx))
            if (!segFile.exists() || segFile.length() == 0L) {
                val rawBytes = fetchRawBytes(seg.url, video, seg.byteRange)
                    ?: throw IllegalStateException("Falha ao baixar segmento $idx")

                val processedBytes = if (seg.keyMethod.equals("AES-128", ignoreCase = true) && !seg.keyUri.isNullOrBlank()) {
                    val keyBytes = keyCache.getOrPut(seg.keyUri) {
                        fetchRawBytes(seg.keyUri, video, null) ?: ByteArray(0)
                    }
                    MediaMuxerEngine.decryptAes128Segment(
                        encryptedBytes = rawBytes,
                        keyBytes = keyBytes,
                        ivHex = seg.keyIvHex,
                        sequenceIndex = seg.sequenceIndex
                    )
                } else {
                    rawBytes
                }

                segFile.writeBytes(processedBytes)
                onSegmentBytes(processedBytes.size)
            } else {
                onSegmentBytes(segFile.length().toInt())
            }
            segmentFiles.add(segFile)
        }

        val assembledFile = File(workDir, "${trackName}_joined.tmp")
        MediaMuxerEngine.concatenateSegments(
            initSegmentFile = initSegmentFile,
            segmentFiles = segmentFiles,
            outputFile = assembledFile
        )
        // Apaga os segmentos individuais imediatamente após concatenar para economizar espaço
        trackDir.deleteRecursively()
        assembledFile
    }

    private fun fetchRawBytes(url: String, video: CapturedVideoEntity, byteRange: String?): ByteArray? {
        val reqBuilder = Request.Builder().url(url).get()
        applyAnti403Headers(reqBuilder, video)
        if (!byteRange.isNullOrBlank()) {
            val formattedRange = parseHlsByteRangeHeader(byteRange)
            if (formattedRange != null) {
                reqBuilder.header("Range", formattedRange)
            }
        }
        return okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) return null
            resp.body?.bytes()
        }
    }

    private fun parseHlsByteRangeHeader(byteRangeAttr: String): String? {
        val clean = byteRangeAttr.trim().trim('"')
        val length = clean.substringBefore('@').toLongOrNull() ?: return null
        val offset = clean.substringAfter('@', "").toLongOrNull() ?: return null
        if (length <= 0L || offset < 0L) return null
        val end = offset + length - 1L
        return "bytes=$offset-$end"
    }

    private fun applyAnti403Headers(builder: Request.Builder, video: CapturedVideoEntity) {
        video.headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) {
                builder.header(k, v)
            }
        }
        if (!video.headers.containsKey("User-Agent")) {
            builder.header("User-Agent", VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT)
        }
    }

    private fun ensureForegroundNotification(title: String, progress: Int, speedBytesPerSec: Long) {
        val notification = buildProgressNotification(
            videoId = -1L,
            fileName = title,
            progress = progress,
            speedBytesPerSec = speedBytesPerSec,
            isMuxing = false
        )
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun updateNotificationProgress(
        videoId: Long,
        fileName: String,
        progress: Int,
        speedBytesPerSec: Long,
        isMuxing: Boolean
    ) {
        val notification = buildProgressNotification(
            videoId = videoId,
            fileName = fileName,
            progress = progress,
            speedBytesPerSec = speedBytesPerSec,
            isMuxing = isMuxing
        )
        try {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun buildProgressNotification(
        videoId: Long,
        fileName: String,
        progress: Int,
        speedBytesPerSec: Long,
        isMuxing: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isMuxing) {
            "Multiplexando áudio e vídeo para MP4..."
        } else {
            val speedStr = formatSpeed(speedBytesPerSec)
            "$progress% • $speedStr"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(statusText)
            .setProgress(100, progress.coerceIn(0, 100), isMuxing)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)

        if (videoId != -1L && !isMuxing) {
            val pauseIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            val pausePending = PendingIntent.getService(
                this,
                videoId.toInt() + 1000,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pausar", pausePending)

            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            val cancelPending = PendingIntent.getService(
                this,
                videoId.toInt() + 2000,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPending)
        }

        return builder.build()
    }

    private fun showCompletedNotification(videoId: Long, fileName: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            videoId.toInt(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download concluído")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()
        try {
            notificationManager.notify(COMPLETED_NOTIFICATION_BASE_ID + videoId.toInt(), notification)
        } catch (_: Exception) {
        }
    }

    private fun checkAndStopServiceIfIdle() {
        val hasRunning = activeJobs.values.any { it.isActive }
        if (!hasRunning) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Motor de Downloads 1DM",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Exibe o progresso de downloads diretos e multiplexação de streams HLS/DASH"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "1dm_hybrid_download_channel"
        const val FOREGROUND_NOTIFICATION_ID = 4100
        const val COMPLETED_NOTIFICATION_BASE_ID = 5000

        const val ACTION_START_DOWNLOAD = "com.example.service.action.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.service.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.service.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.service.action.CANCEL_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "com.example.service.action.CANCEL_ALL"

        const val EXTRA_VIDEO_ID = "extra_video_id"

        fun startDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            startServiceSafe(context, intent)
        }

        fun pauseDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            startServiceSafe(context, intent)
        }

        fun resumeDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            startServiceSafe(context, intent)
        }

        fun cancelDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            startServiceSafe(context, intent)
        }

        fun cancelAllDownloads(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
            startServiceSafe(context, intent)
        }

        private fun startServiceSafe(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "Calculando..."
            val kb = bytesPerSec / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) {
                String.format(Locale.getDefault(), "%.2f MB/s", mb)
            } else {
                String.format(Locale.getDefault(), "%.0f KB/s", kb)
            }
        }
    }
}
