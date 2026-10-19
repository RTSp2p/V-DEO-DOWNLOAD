package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.download.DownloadHelper
import com.example.data.local.AppDatabase
import com.example.data.local.entity.CapturedVideoEntity
import com.example.data.repository.SettingsRepository
import com.example.data.repository.ThemeMode
import com.example.data.repository.VideoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: VideoRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = VideoRepository(
            videoDao = database.videoDao(),
            browsingHistoryDao = database.browsingHistoryDao(),
            downloadHelper = DownloadHelper(context),
            context = context
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun readStringFromContext() {
        val appName = context.getString(R.string.app_name)
        assertEquals("1DM Video Hunter", appName)
    }

    @Test
    fun deletingDetectedVideoPhysicallyFreesUniqueIndexForRedetection() {
        runBlocking {
            val url = "https://cdn.example.com/videos/sample_clip.mp4"
            val entity = CapturedVideoEntity(
                videoUrl = url,
                fileName = "sample_clip.mp4",
                fileType = "mp4",
                downloadStatus = CapturedVideoEntity.STATUS_DETECTED
            )

            // First detection succeeds
            assertTrue(repository.insertCapturedVideo(entity))

            // Duplicate detection is ignored while in DB
            assertFalse(repository.insertCapturedVideo(entity))

            // Physical deletion of DETECTED video
            val saved = database.videoDao().getVideoByUrl(url)!!
            assertTrue(repository.deleteDetectedVideo(saved.id))

            // Re-detection of the exact same URL succeeds after physical deletion
            assertTrue(repository.insertCapturedVideo(entity))
        }
    }

    @Test
    fun removingCompletedVideoFromHistoryKeepsPhysicalFileAndAllowsRedetection() {
        runBlocking {
            val tempFile = File.createTempFile("downloaded_test_video", ".mp4", context.cacheDir)
            tempFile.writeText("dummy video bytes")
            assertTrue(tempFile.exists())

            val url = "https://cdn.example.com/videos/completed_movie.mp4"
            val entity = CapturedVideoEntity(
                videoUrl = url,
                fileName = "completed_movie.mp4",
                fileType = "mp4",
                localUri = android.net.Uri.fromFile(tempFile).toString(),
                fileSizeBytes = tempFile.length(),
                downloadStatus = CapturedVideoEntity.STATUS_COMPLETED
            )

            assertTrue(repository.insertCapturedVideo(entity))
            val saved = database.videoDao().getVideoByUrl(url)!!

            // Clear from history (batch selection cleanup on Home tabs)
            val removedCount = repository.removeVideosFromHistory(listOf(saved.id))
            assertEquals(1, removedCount)

            // Physical file MUST remain intact on disk
            assertTrue(tempFile.exists())

            // And the URL can be detected again in the future
            assertTrue(
                repository.insertCapturedVideo(
                    entity.copy(downloadStatus = CapturedVideoEntity.STATUS_DETECTED)
                )
            )

            tempFile.delete()
        }
    }

    @Test
    fun settingsRepositoryPersistsThemeModeAndDownloadFolderUri() {
        runBlocking {
            val settingsRepository = SettingsRepository(context)
            settingsRepository.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, settingsRepository.themeMode.first())

            val customUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
            settingsRepository.setDownloadFolderUri(customUri)
            assertEquals(customUri, settingsRepository.downloadFolderUri.first())
        }
    }
}
