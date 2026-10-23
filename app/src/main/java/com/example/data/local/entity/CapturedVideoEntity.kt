package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.browser.VideoSnifferJsBridge

enum class StreamType {
    DIRECT,
    HLS,
    DASH
}

@Entity(
    tableName = "captured_videos",
    indices = [
        Index(value = ["videoUrl"], unique = true)
    ]
)
data class CapturedVideoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val videoUrl: String,
    val fileName: String,
    val fileType: String = "mp4",
    val fileSizeBytes: Long = 0L,
    val localUri: String? = null,
    val downloadStatus: String,
    val downloadId: Long = -1L,
    val capturedAt: Long = System.currentTimeMillis(),
    val isStream: Boolean = false,
    val streamType: StreamType = StreamType.DIRECT,
    val headers: Map<String, String> = emptyMap(),
    val thumbnailUrl: String? = null,
    val title: String = "",
    val isDrmProtected: Boolean = false,
    val selectedQuality: String? = null,
    val variantVideoUrl: String? = null,
    val variantAudioUrl: String? = null,
    val isAudioOnly: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadedBytes: Long = 0L,
    val downloadSpeedBytesPerSec: Long = 0L
) {
    /**
     * Retorna o nome do ficheiro estritamente sanitizado (sem \ / : * ? " < > |)
     * e com extensão ".mp4" única (evitando ".mp4.mp4").
     */
    fun sanitizedMp4FileName(): String {
        return VideoSnifferJsBridge.sanitizeAndroidFileName(fileName, "mp4")
    }

    /**
     * Retorna um pacote de entidade com fileName e title normalizados segundo as regras de nomenclatura limpa.
     */
    fun withNormalizedFileName(contentDisposition: String? = null): CapturedVideoEntity {
        val cleanName = if (fileName.isNotBlank() && !VideoSnifferJsBridge.isGenericMediaIdentifier(VideoSnifferJsBridge.stripKnownExtensions(fileName))) {
            VideoSnifferJsBridge.sanitizeAndroidFileName(fileName, "mp4")
        } else {
            VideoSnifferJsBridge.buildFileName(
                url = videoUrl,
                videoElementTitle = title.takeIf { it.isNotBlank() },
                extension = "mp4",
                contentDisposition = contentDisposition
            )
        }
        val cleanBaseTitle = cleanName.substringBeforeLast('.')
        return copy(
            fileName = cleanName,
            fileType = if (isStream) fileType else "mp4",
            title = cleanBaseTitle
        )
    }

    companion object {
        const val STATUS_DETECTED = "DETECTED"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_MUXING = "MUXING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_DRM_BLOCKED = "DRM_BLOCKED"
    }
}
