package com.example.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.browser.VideoSnifferJsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.roundToLong

object MediaMuxerEngine {

    // Limites para detecção de descontinuidade artificial de PTS (500ms para recuo, 500ms para salto à frente)
    private const val MAX_BACKWARD_PTS_REORDER_US = 500_000L
    private const val MAX_FORWARD_PTS_GAP_US = 500_000L
    private const val DEFAULT_VIDEO_FRAME_DURATION_US = 33_333L
    private const val DEFAULT_AUDIO_FRAME_DURATION_US = 21_333L

    /**
     * Descriptografa um segmento HLS protegido por #EXT-X-KEY:METHOD=AES-128 padrão
     * usando AES/CBC/PKCS5Padding.
     */
    fun decryptAes128Segment(
        encryptedBytes: ByteArray,
        keyBytes: ByteArray,
        ivHex: String?,
        sequenceIndex: Int
    ): ByteArray {
        if (keyBytes.size != 16) return encryptedBytes
        val ivBytes = parseIv(ivHex, sequenceIndex)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(encryptedBytes)
    }

    private fun parseIv(ivHex: String?, sequenceIndex: Int): ByteArray {
        val cleanHex = ivHex?.removePrefix("0x")?.removePrefix("0X")?.trim()
        if (!cleanHex.isNullOrBlank() && cleanHex.length == 32) {
            return try {
                ByteArray(16) { i ->
                    cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) {
                buildSequenceIv(sequenceIndex)
            }
        }
        return buildSequenceIv(sequenceIndex)
    }

    private fun buildSequenceIv(sequenceIndex: Int): ByteArray {
        val iv = ByteArray(16)
        val seq = sequenceIndex.toLong()
        for (i in 0..7) {
            iv[15 - i] = ((seq ushr (i * 8)) and 0xFF).toByte()
        }
        return iv
    }

    /**
     * Concatena uma lista ordenada e já deduplicada de arquivos de segmento
     * (e opcionalmente um init segment fMP4) em um único arquivo contínuo no diretório temporário.
     */
    suspend fun concatenateSegments(
        initSegmentFile: File?,
        segmentFiles: List<File>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                val buffer = ByteArray(64 * 1024)
                if (initSegmentFile != null && initSegmentFile.exists()) {
                    BufferedInputStream(FileInputStream(initSegmentFile)).use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
                for (seg in segmentFiles) {
                    if (!seg.exists()) continue
                    BufferedInputStream(FileInputStream(seg)).use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
                out.flush()
            }
            outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Realiza o remuxing/multiplexação em faixas simultâneas (vídeo + áudio) para um container
     * final .mp4 válido, aplicando:
     * 1. Normalização do timestamp de início para zero (-avoid_negative_ts make_zero).
     * 2. Reindexação monotônica e eliminação de descontinuidades artificiais de apresentação (-fflags +genpts).
     * 3. Alinhamento da duração final com a soma exata das durações declaradas em #EXTINF (expectedDurationUs).
     */
    suspend fun remuxOrMultiplexToMp4(
        videoSourceFile: File?,
        audioSourceFile: File?,
        outputFile: File,
        isAudioOnly: Boolean,
        expectedDurationUs: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        if (isAudioOnly) {
            val source = audioSourceFile ?: videoSourceFile ?: return@withContext false
            if (!source.exists() || source.length() == 0L) return@withContext false

            val remuxed = tryRemuxTracks(
                videoInput = null,
                audioInput = source,
                outputFile = outputFile,
                expectedDurationUs = expectedDurationUs
            )
            if (remuxed && outputFile.exists() && outputFile.length() > 0L) {
                return@withContext true
            }
            source.copyTo(outputFile, overwrite = true)
            return@withContext outputFile.exists() && outputFile.length() > 0L
        }

        val vFile = videoSourceFile ?: return@withContext false
        if (!vFile.exists() || vFile.length() == 0L) return@withContext false

        val separateAudio = audioSourceFile?.takeIf {
            it.exists() && it.length() > 0L && it.absolutePath != vFile.absolutePath
        }

        val remuxed = tryRemuxTracks(
            videoInput = vFile,
            audioInput = separateAudio,
            outputFile = outputFile,
            expectedDurationUs = expectedDurationUs
        )
        if (remuxed && outputFile.exists() && outputFile.length() > 0L) {
            return@withContext true
        }

        // Fallback seguro se o container já for compatível
        vFile.copyTo(outputFile, overwrite = true)
        outputFile.exists() && outputFile.length() > 0L
    }

    private fun tryRemuxTracks(
        videoInput: File?,
        audioInput: File?,
        outputFile: File,
        expectedDurationUs: Long
    ): Boolean {
        var videoExtractor: MediaExtractor? = null
        var embeddedAudioExtractor: MediaExtractor? = null
        var separateAudioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxVideoTrackIndex = -1
            var muxAudioTrackIndex = -1
            var videoExtractorTrackIndex = -1
            var embeddedAudioExtractorTrackIndex = -1
            var separateAudioExtractorTrackIndex = -1

            if (videoInput != null && videoInput.exists()) {
                videoExtractor = MediaExtractor().apply {
                    setDataSource(videoInput.absolutePath)
                }
                for (i in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && videoExtractorTrackIndex == -1) {
                        videoExtractorTrackIndex = i
                        if (expectedDurationUs > 0L) {
                            format.setLong(MediaFormat.KEY_DURATION, expectedDurationUs)
                        }
                        muxVideoTrackIndex = muxer.addTrack(format)
                    } else if (mime.startsWith("audio/") && audioInput == null && embeddedAudioExtractorTrackIndex == -1) {
                        embeddedAudioExtractorTrackIndex = i
                        if (expectedDurationUs > 0L) {
                            format.setLong(MediaFormat.KEY_DURATION, expectedDurationUs)
                        }
                        muxAudioTrackIndex = muxer.addTrack(format)
                    }
                }
            }

            if (audioInput != null && audioInput.exists()) {
                separateAudioExtractor = MediaExtractor().apply {
                    setDataSource(audioInput.absolutePath)
                }
                for (i in 0 until separateAudioExtractor.trackCount) {
                    val format = separateAudioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/") && separateAudioExtractorTrackIndex == -1) {
                        separateAudioExtractorTrackIndex = i
                        if (expectedDurationUs > 0L) {
                            format.setLong(MediaFormat.KEY_DURATION, expectedDurationUs)
                        }
                        muxAudioTrackIndex = muxer.addTrack(format)
                        break
                    }
                }
            }

            if (muxVideoTrackIndex == -1 && muxAudioTrackIndex == -1) {
                return false
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024) // 2 MB sample buffer

            // 1. Faixa de Vídeo: normaliza timestamp inicial para 0 e ignora descontinuidades
            if (videoInput != null && videoExtractor != null && videoExtractorTrackIndex != -1 && muxVideoTrackIndex != -1) {
                writeNormalizedTrackSamples(
                    sourceFile = videoInput,
                    extractor = videoExtractor,
                    extractorTrackIndex = videoExtractorTrackIndex,
                    muxer = muxer,
                    muxerTrackIndex = muxVideoTrackIndex,
                    buffer = buffer,
                    expectedDurationUs = expectedDurationUs,
                    isAudio = false
                )
            }

            // 2. Faixa de Áudio Embutida (muxing em faixa simultânea no mesmo container MP4)
            if (videoInput != null && embeddedAudioExtractorTrackIndex != -1 && muxAudioTrackIndex != -1) {
                embeddedAudioExtractor = MediaExtractor().apply {
                    setDataSource(videoInput.absolutePath)
                }
                writeNormalizedTrackSamples(
                    sourceFile = videoInput,
                    extractor = embeddedAudioExtractor,
                    extractorTrackIndex = embeddedAudioExtractorTrackIndex,
                    muxer = muxer,
                    muxerTrackIndex = muxAudioTrackIndex,
                    buffer = buffer,
                    expectedDurationUs = expectedDurationUs,
                    isAudio = true
                )
            }

            // 3. Faixa de Áudio Separada (muxing em faixa simultânea no mesmo container MP4)
            if (audioInput != null && separateAudioExtractor != null && separateAudioExtractorTrackIndex != -1 && muxAudioTrackIndex != -1) {
                writeNormalizedTrackSamples(
                    sourceFile = audioInput,
                    extractor = separateAudioExtractor,
                    extractorTrackIndex = separateAudioExtractorTrackIndex,
                    muxer = muxer,
                    muxerTrackIndex = muxAudioTrackIndex,
                    buffer = buffer,
                    expectedDurationUs = expectedDurationUs,
                    isAudio = true
                )
            }

            muxer.stop()
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                videoExtractor?.release()
            } catch (_: Exception) {
            }
            try {
                embeddedAudioExtractor?.release()
            } catch (_: Exception) {
            }
            try {
                separateAudioExtractor?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Lê todos os samples da faixa, normaliza os timestamps de apresentação (início em 0,
     * remoção de descontinuidades e alinhamento com a soma de #EXTINF) e grava no MediaMuxer.
     */
    private fun writeNormalizedTrackSamples(
        sourceFile: File,
        extractor: MediaExtractor,
        extractorTrackIndex: Int,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        buffer: ByteBuffer,
        expectedDurationUs: Long,
        isAudio: Boolean
    ) {
        // Passo 1: Inspeciona os timestamps brutos de todos os samples da faixa
        val rawPtsList = ArrayList<Long>(1024)
        extractor.selectTrack(extractorTrackIndex)
        while (true) {
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0L && extractor.readSampleData(buffer, 0) < 0) {
                break
            }
            rawPtsList.add(sampleTime)
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(extractorTrackIndex)

        if (rawPtsList.isEmpty()) return

        val normalizedPtsSchedule = normalizeTrackTimestamps(
            rawPtsUs = rawPtsList.toLongArray(),
            expectedDurationUs = expectedDurationUs,
            isAudio = isAudio
        )

        // Passo 2: Reabre um MediaExtractor limpo para garantir leitura desde o primeiro byte/sample
        val writeExtractor = MediaExtractor()
        try {
            writeExtractor.setDataSource(sourceFile.absolutePath)
            writeExtractor.selectTrack(extractorTrackIndex)

            val bufferInfo = MediaCodec.BufferInfo()
            var sampleIndex = 0
            while (sampleIndex < normalizedPtsSchedule.size) {
                buffer.clear()
                val sampleSize = writeExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = normalizedPtsSchedule[sampleIndex]

                // Converte explicitamente SAMPLE_FLAG_SYNC para BUFFER_FLAG_KEY_FRAME
                // (evita que SAMPLE_FLAG_PARTIAL_FRAME = 4 seja interpretado como BUFFER_FLAG_END_OF_STREAM = 4)
                val extractorFlags = writeExtractor.sampleFlags
                var codecFlags = 0
                if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                bufferInfo.flags = codecFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                sampleIndex++
                if (!writeExtractor.advance()) break
            }
        } finally {
            try {
                writeExtractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Normaliza os timestamps de apresentação de uma faixa de mídia (equivalente a
     * "-fflags +genpts -avoid_negative_ts make_zero" e normalização de descontinuidades):
     * 1. Desloca o timestamp inicial para zero (0L), removendo offsets de relógio de stream (ex: 44s em HLS).
     * 2. Ignora descontinuidades artificiais (saltos bruscos para frente > 500ms ou resets para trás < -500ms),
     *    substituindo o salto pelo incremento nominal de um frame/sample (stepUs).
     * 3. Reindexa os timestamps dentro de cada bloco contínuo de forma estritamente monotônica (>= 0L).
     * 4. Caso expectedDurationUs (> 0L, soma das tags #EXTINF) seja fornecida e haja divergência
     *    significativa na duração acumulada, ajusta proporcionalmente a escala de tempo para que
     *    a duração final reflita a soma exata das durações declaradas nas tags #EXTINF.
     */
    internal fun normalizeTrackTimestamps(
        rawPtsUs: LongArray,
        expectedDurationUs: Long = 0L,
        isAudio: Boolean = false
    ): LongArray {
        if (rawPtsUs.isEmpty()) return LongArray(0)

        val defaultStepUs = if (isAudio) DEFAULT_AUDIO_FRAME_DURATION_US else DEFAULT_VIDEO_FRAME_DURATION_US

        // 1. Calcula o passo nominal (duração média de um frame/sample) a partir de deltas válidos
        val validSmallDeltas = ArrayList<Long>(rawPtsUs.size)
        for (i in 1 until rawPtsUs.size) {
            val prev = rawPtsUs[i - 1]
            val curr = rawPtsUs[i]
            if (prev >= 0L && curr >= 0L) {
                val delta = curr - prev
                if (delta in 1_000L..200_000L) {
                    validSmallDeltas.add(delta)
                }
            }
        }
        val nominalStepUs = if (validSmallDeltas.isNotEmpty()) {
            validSmallDeltas.sort()
            validSmallDeltas[validSmallDeltas.size / 2]
        } else {
            defaultStepUs
        }

        // 2. Agrupa os samples em blocos contínuos separados por descontinuidades artificiais
        //    e ordena/normaliza cada bloco para iniciar exatamente após o bloco anterior (começando em 0L)
        val result = LongArray(rawPtsUs.size)
        var chunkStart = 0
        var nextChunkStartPtsUs = 0L

        while (chunkStart < rawPtsUs.size) {
            var chunkEnd = chunkStart + 1
            while (chunkEnd < rawPtsUs.size) {
                val prevRaw = rawPtsUs[chunkEnd - 1]
                val currRaw = rawPtsUs[chunkEnd]
                if (prevRaw < 0L || currRaw < 0L) {
                    break
                }
                val rawDelta = currRaw - prevRaw
                if (rawDelta < -MAX_BACKWARD_PTS_REORDER_US || rawDelta > MAX_FORWARD_PTS_GAP_US) {
                    // Descontinuidade artificial detectada!
                    break
                }
                chunkEnd++
            }

            val chunkLength = chunkEnd - chunkStart
            val chunkRaw = LongArray(chunkLength) { idx -> rawPtsUs[chunkStart + idx] }

            if (chunkLength == 1 || chunkRaw.any { it < 0L }) {
                for (idx in 0 until chunkLength) {
                    result[chunkStart + idx] = nextChunkStartPtsUs + (idx * nominalStepUs)
                }
                nextChunkStartPtsUs = result[chunkEnd - 1] + nominalStepUs
            } else {
                // Ordena os timestamps do bloco contínuo para eliminar reordenação B-frame negativa no stts
                // mantendo a duração exata dos frames originais e começando em nextChunkStartPtsUs
                val sortedChunk = chunkRaw.sortedArray()
                val minChunkPts = sortedChunk[0]
                var prevAssignedPts = nextChunkStartPtsUs - 1L

                for (idx in 0 until chunkLength) {
                    val relativePts = (sortedChunk[idx] - minChunkPts).coerceAtLeast(0L)
                    var candidatePts = nextChunkStartPtsUs + relativePts
                    if (candidatePts <= prevAssignedPts) {
                        candidatePts = prevAssignedPts + 1_000L
                    }
                    result[chunkStart + idx] = candidatePts
                    prevAssignedPts = candidatePts
                }
                nextChunkStartPtsUs = result[chunkEnd - 1] + nominalStepUs
            }

            chunkStart = chunkEnd
        }

        // 3. Garante que o primeiro sample comece estritamente em 0L (-avoid_negative_ts make_zero)
        val firstPts = result[0]
        if (firstPts != 0L) {
            for (i in result.indices) {
                result[i] = (result[i] - firstPts).coerceAtLeast(0L)
            }
        }

        // 4. Se expectedDurationUs (soma das tags #EXTINF) foi informada, ajusta a linha do tempo
        //    quando houver desvio artificial para refletir a duração exata declarada em #EXTINF
        if (expectedDurationUs > 0L && result.size > 1) {
            val currentTotalDurationUs = result.last() + nominalStepUs
            val toleranceUs = (expectedDurationUs * 0.08).toLong().coerceAtLeast(250_000L)
            if (currentTotalDurationUs > 0L && abs(currentTotalDurationUs - expectedDurationUs) > toleranceUs) {
                val targetLastPtsUs = (expectedDurationUs - nominalStepUs).coerceAtLeast(result.size.toLong())
                val rawLastPtsUs = result.last().coerceAtLeast(1L)
                var prevScaledPts = -1L
                for (i in result.indices) {
                    var scaled = ((result[i].toDouble() * targetLastPtsUs.toDouble()) / rawLastPtsUs.toDouble()).roundToLong()
                    if (i == 0) scaled = 0L
                    if (scaled <= prevScaledPts) {
                        scaled = prevScaledPts + 1L
                    }
                    result[i] = scaled
                    prevScaledPts = scaled
                }
            }
        }

        return result
    }

    /**
     * Exporta o arquivo final para o armazenamento público via MediaStore (Movies/1DMVideoHunter ou Downloads)
     * ou para a pasta SAF escolhida pelo usuário, garantindo nome sanitizado e extensão ".mp4" única.
     */
    suspend fun exportToPublicStorage(
        context: Context,
        sourceFile: File,
        finalFileName: String,
        mimeType: String,
        customSafFolderUri: String?
    ): String? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return@withContext null

        val sanitizedFinalName = VideoSnifferJsBridge.sanitizeAndroidFileName(finalFileName, "mp4")

        // 1. Se o usuário configurou uma pasta personalizada via SAF, salva nela prioritariamente
        if (!customSafFolderUri.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(customSafFolderUri)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                if (!treeDocId.isNullOrBlank()) {
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                    val createdDocUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        "video/mp4",
                        sanitizedFinalName
                    )
                    if (createdDocUri != null) {
                        FileInputStream(sourceFile).use { input ->
                            context.contentResolver.openOutputStream(createdDocUri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        return@withContext createdDocUri.toString()
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. Salva via MediaStore.Video.Media
        try {
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val relativePath = Environment.DIRECTORY_MOVIES + "/1DMVideoHunter"

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedFinalName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val insertedUri = resolver.insert(collection, values)
                if (insertedUri != null) {
                    resolver.openOutputStream(insertedUri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    val completeValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(insertedUri, completeValues, null, null)
                    return@withContext insertedUri.toString()
                }
            }
        } catch (_: Exception) {
        }

        // 3. Fallback direto em DIRECTORY_DOWNLOADS
        return@withContext try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val destFile = File(downloadsDir, sanitizedFinalName)
            sourceFile.copyTo(destFile, overwrite = true)
            Uri.fromFile(destFile).toString()
        } catch (_: Exception) {
            Uri.fromFile(sourceFile).toString()
        }
    }
}
