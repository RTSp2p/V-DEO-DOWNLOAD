package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.CapturedVideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideo(video: CapturedVideoEntity): Long

    @Query("SELECT * FROM captured_videos WHERE downloadStatus = 'DETECTED' ORDER BY capturedAt DESC")
    fun observeDetectedVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus = 'COMPLETED' ORDER BY capturedAt DESC")
    fun observeCompletedVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos ORDER BY capturedAt DESC")
    fun observeAllVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus IN ('DOWNLOADING', 'PAUSED', 'MUXING') ORDER BY capturedAt DESC")
    fun observeDownloadingVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus IN ('FAILED', 'DRM_BLOCKED') ORDER BY capturedAt DESC")
    fun observeFailedVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus IN ('DOWNLOADING', 'PAUSED', 'MUXING')")
    suspend fun getDownloadingVideos(): List<CapturedVideoEntity>

    @Query("UPDATE captured_videos SET downloadStatus = 'FAILED', downloadSpeedBytesPerSec = 0 WHERE downloadStatus IN ('DOWNLOADING', 'PAUSED', 'MUXING')")
    suspend fun cancelAllDownloading()

    @Query("UPDATE captured_videos SET downloadStatus = :status, downloadSpeedBytesPerSec = 0 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query(
        """
        UPDATE captured_videos 
        SET downloadStatus = :status,
            downloadProgress = :progress,
            downloadedBytes = :downloadedBytes,
            fileSizeBytes = CASE WHEN :totalBytes > 0 THEN :totalBytes ELSE fileSizeBytes END,
            downloadSpeedBytesPerSec = :speedBytesPerSec
        WHERE id = :id
        """
    )
    suspend fun updateDownloadProgress(
        id: Long,
        status: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    )

    @Query(
        """
        UPDATE captured_videos 
        SET selectedQuality = :selectedQuality,
            variantVideoUrl = :variantVideoUrl,
            variantAudioUrl = :variantAudioUrl,
            isAudioOnly = :isAudioOnly,
            fileType = :fileType,
            fileName = :fileName
        WHERE id = :id
        """
    )
    suspend fun updateStreamSelection(
        id: Long,
        selectedQuality: String?,
        variantVideoUrl: String?,
        variantAudioUrl: String?,
        isAudioOnly: Boolean,
        fileType: String,
        fileName: String
    )

    @Query("UPDATE captured_videos SET fileName = :newName WHERE id = :id")
    suspend fun updateFileName(id: Long, newName: String)

    @Query("UPDATE captured_videos SET fileName = :newName, localUri = :newLocalUri WHERE id = :id")
    suspend fun updateFileNameAndUri(id: Long, newName: String, newLocalUri: String)

    @Query("DELETE FROM captured_videos WHERE id = :id")
    suspend fun deleteVideo(id: Long)

    @Query("DELETE FROM captured_videos WHERE id IN (:ids)")
    suspend fun deleteVideosByIds(ids: List<Long>): Int

    @Query("DELETE FROM captured_videos WHERE id = :id AND downloadStatus = 'DETECTED'")
    suspend fun deleteDetectedVideoById(id: Long): Int

    @Query("DELETE FROM captured_videos WHERE id IN (:ids) AND downloadStatus = 'DETECTED'")
    suspend fun deleteDetectedVideosByIds(ids: List<Long>): Int

    @Query("UPDATE captured_videos SET downloadStatus = :status, downloadId = :downloadId WHERE id = :id")
    suspend fun updateStatusAndDownloadId(id: Long, status: String, downloadId: Long)

    @Query(
        """
        UPDATE captured_videos 
        SET downloadStatus = 'COMPLETED', 
            localUri = :localUri, 
            fileSizeBytes = :fileSizeBytes,
            downloadedBytes = :fileSizeBytes,
            downloadProgress = 100,
            downloadSpeedBytesPerSec = 0
        WHERE downloadId = :downloadId
        """
    )
    suspend fun updateDownloadCompleted(downloadId: Long, localUri: String, fileSizeBytes: Long)

    @Query(
        """
        UPDATE captured_videos 
        SET downloadStatus = 'COMPLETED', 
            localUri = :localUri, 
            fileSizeBytes = :fileSizeBytes,
            downloadedBytes = :fileSizeBytes,
            downloadProgress = 100,
            downloadSpeedBytesPerSec = 0,
            fileName = :finalFileName,
            fileType = :finalFileType
        WHERE id = :id
        """
    )
    suspend fun updateDownloadCompletedById(
        id: Long,
        localUri: String,
        fileSizeBytes: Long,
        finalFileName: String,
        finalFileType: String
    )

    @Query("SELECT * FROM captured_videos WHERE id = :id LIMIT 1")
    suspend fun getVideoById(id: Long): CapturedVideoEntity?

    @Query("SELECT * FROM captured_videos WHERE id IN (:ids)")
    suspend fun getVideosByIds(ids: List<Long>): List<CapturedVideoEntity>

    @Query("SELECT * FROM captured_videos WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getVideoByDownloadId(downloadId: Long): CapturedVideoEntity?

    @Query("SELECT * FROM captured_videos WHERE videoUrl = :url LIMIT 1")
    suspend fun getVideoByUrl(url: String): CapturedVideoEntity?

    @Query("SELECT * FROM captured_videos WHERE downloadStatus = 'DETECTED'")
    suspend fun getDetectedVideos(): List<CapturedVideoEntity>

    @Query("DELETE FROM captured_videos WHERE downloadStatus = 'DETECTED'")
    suspend fun clearDetectedVideos()
}
