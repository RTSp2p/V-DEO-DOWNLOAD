package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val fileType: String,
    val fileSizeBytes: Long = 0L,
    val localUri: String? = null,
    val downloadStatus: String, // "DETECTED", "DOWNLOADING", "COMPLETED"
    val downloadId: Long = -1L,
    val capturedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_DETECTED = "DETECTED"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_COMPLETED = "COMPLETED"
    }
}
