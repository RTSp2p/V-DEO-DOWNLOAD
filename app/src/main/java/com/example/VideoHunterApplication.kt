package com.example

import android.app.Application
import com.example.data.download.DownloadHelper
import com.example.data.local.AppDatabase
import com.example.data.repository.VideoRepository

class VideoHunterApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var downloadHelper: DownloadHelper
        private set

    lateinit var repository: VideoRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        downloadHelper = DownloadHelper(this)
        repository = VideoRepository(
            videoDao = database.videoDao(),
            browsingHistoryDao = database.browsingHistoryDao(),
            downloadHelper = downloadHelper,
            context = this
        )
    }
}
