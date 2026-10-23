package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.browser.VideoSnifferJsBridge
import com.example.data.download.DownloadHelper
import com.example.data.download.MediaMuxerEngine
import com.example.data.download.StreamManifestParser
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
    fun hlsLoopingPlaylistWithoutEndlistCapturesSingleIterationOf6Seconds() = runBlocking {
        val parser = StreamManifestParser()
        val loopingM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:2.000,
            seg_0.ts
            #EXTINF:2.000,
            seg_1.ts
            #EXTINF:2.000,
            seg_2.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:2.000,
            seg_0.ts
            #EXTINF:2.000,
            seg_1.ts
            #EXTINF:2.000,
            seg_2.ts
        """.trimIndent()

        val segments = parser.parseHlsMediaPlaylistText(
            playlistUrl = "https://cdn.example.com/hls/playlist.m3u8",
            text = loopingM3u8
        )

        assertEquals(3, segments.size)
        assertEquals(6.0, segments.sumOf { it.durationSec }, 0.001)
    }

    @Test
    fun ptsNormalizationFixes6SecondVideoWith44SecondOffsetAndDiscontinuities() {
        // Simula vídeo de 6s (6 frames de 1s para simplicidade) cujo PTS inicial no TS é 44s (44_000_000 us)
        // e tem um salto artificial de descontinuidade no meio
        val rawPts = longArrayOf(
            44_000_000L,
            45_000_000L,
            46_000_000L,
            90_000_000L, // Salto artificial de descontinuidade
            91_000_000L,
            92_000_000L
        )
        val expectedDurationUs = 6_000_000L

        val normalized = MediaMuxerEngine.normalizeTrackTimestamps(
            rawPtsUs = rawPts,
            expectedDurationUs = expectedDurationUs,
            isAudio = false
        )

        // Deve iniciar exatamente em 0L
        assertEquals(0L, normalized.first())
        // O último sample + 1 passo deve refletir os 6 segundos esperados (e nunca 50s ou 92s)
        assertTrue(normalized.last() in 4_500_000L..5_999_999L)
    }

    @Test
    fun fileNameFollowsStrictPriorityAndSanitizationRules() {
        // Prioridade 1: Extração a partir do segmento da URL antes de "?"
        val url1 = "https://exemplo.com/media/aula_01.mp4?token=xyz"
        val name1 = VideoSnifferJsBridge.buildFileName(
            url = url1,
            videoElementTitle = "Ignorar se URL tem nome valido",
            extension = "mp4"
        )
        assertEquals("aula_01.mp4", name1)

        // Prioridade 1 via Content-Disposition
        val nameFromHeader = VideoSnifferJsBridge.buildFileName(
            url = "https://exemplo.com/stream.m3u8",
            videoElementTitle = "Titulo Video",
            extension = "mp4",
            contentDisposition = "attachment; filename=\"modulo_02_intro.mp4\""
        )
        assertEquals("modulo_02_intro.mp4", nameFromHeader)

        // Prioridade 2: URL genérica ("/stream.m3u8") utiliza o atributo title/aria-label da tag <video>
        // removendo caracteres inválidos (\ / : * ? " < > |) e evitando .mp4.mp4
        val name2 = VideoSnifferJsBridge.buildFileName(
            url = "https://exemplo.com/live/stream.m3u8?token=abc",
            videoElementTitle = "Aula 03: Introdução / Conceitos*Básicos?.mp4",
            extension = "mp4"
        )
        assertEquals("Aula 03 Introdução ConceitosBásicos.mp4", name2)

        // Fallback: URL genérica e sem atributo na tag <video> -> "video_YYYYMMDD_HHmmss.mp4"
        val fallbackName = VideoSnifferJsBridge.buildFileName(
            url = "https://exemplo.com/hls/master.m3u8",
            videoElementTitle = null,
            extension = "mp4"
        )
        assertTrue(Regex("""^video_\d{8}_\d{6}\.mp4$""").matches(fallbackName))
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
