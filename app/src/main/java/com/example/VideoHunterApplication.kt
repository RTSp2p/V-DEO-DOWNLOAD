package com.example

import android.app.Application
import com.example.data.download.DownloadHelper
import com.example.data.local.AppDatabase
import com.example.data.repository.SettingsRepository
import com.example.data.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VideoHunterApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: AppDatabase
        private set

    lateinit var downloadHelper: DownloadHelper
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var repository: VideoRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        downloadHelper = DownloadHelper(this)
        settingsRepository = SettingsRepository(this)
        com.example.browser.AdBlocker.init(this)
        repository = VideoRepository(
            videoDao = database.videoDao(),
            browsingHistoryDao = database.browsingHistoryDao(),
            downloadHelper = downloadHelper,
            context = this,
            settingsRepository = settingsRepository
        )

        appScope.launch {
            settingsRepository.downloadFolderUri.collectLatest { folderUri ->
                downloadHelper.updateDownloadFolderUri(folderUri)
            }
        }
    }
}
