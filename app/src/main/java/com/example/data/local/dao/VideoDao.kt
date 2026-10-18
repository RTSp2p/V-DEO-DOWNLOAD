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

    @Query("SELECT * FROM captured_videos WHERE downloadStatus IN ('DETECTED', 'DOWNLOADING') ORDER BY capturedAt DESC")
    fun observeDetectedVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus = 'COMPLETED' ORDER BY capturedAt DESC")
    fun observeCompletedVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos ORDER BY capturedAt DESC")
    fun observeAllVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus = 'DOWNLOADING' ORDER BY capturedAt DESC")
    fun observeDownloadingVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus = 'FAILED' ORDER BY capturedAt DESC")
    fun observeFailedVideos(): Flow<List<CapturedVideoEntity>>

    @Query("SELECT * FROM captured_videos WHERE downloadStatus = 'DOWNLOADING'")
    suspend fun getDownloadingVideos(): List<CapturedVideoEntity>

    @Query("UPDATE captured_videos SET downloadStatus = 'FAILED' WHERE downloadStatus = 'DOWNLOADING'")
    suspend fun cancelAllDownloading()

    @Query("UPDATE captured_videos SET downloadStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE captured_videos SET fileName = :newName WHERE id = :id")
    suspend fun updateFileName(id: Long, newName: String)

    @Query("UPDATE captured_videos SET fileName = :newName, localUri = :newLocalUri WHERE id = :id")
    suspend fun updateFileNameAndUri(id: Long, newName: String, newLocalUri: String)

    @Query("DELETE FROM captured_videos WHERE id = :id")
    suspend fun deleteVideo(id: Long)

    @Query("UPDATE captured_videos SET downloadStatus = :status, downloadId = :downloadId WHERE id = :id")
    suspend fun updateStatusAndDownloadId(id: Long, status: String, downloadId: Long)

    @Query("UPDATE captured_videos SET downloadStatus = 'COMPLETED', localUri = :localUri, fileSizeBytes = :fileSizeBytes WHERE downloadId = :downloadId")
    suspend fun updateDownloadCompleted(downloadId: Long, localUri: String, fileSizeBytes: Long)

    @Query("SELECT * FROM captured_videos WHERE id = :id LIMIT 1")
    suspend fun getVideoById(id: Long): CapturedVideoEntity?

    @Query("SELECT * FROM captured_videos WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getVideoByDownloadId(downloadId: Long): CapturedVideoEntity?

    @Query("SELECT * FROM captured_videos WHERE videoUrl = :url LIMIT 1")
    suspend fun getVideoByUrl(url: String): CapturedVideoEntity?

    @Query("DELETE FROM captured_videos WHERE downloadStatus = 'DETECTED'")
    suspend fun clearDetectedVideos()
}
