package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.BrowsingHistoryDao
import com.example.data.local.dao.VideoDao
import com.example.data.local.entity.BrowsingHistoryEntity
import com.example.data.local.entity.CapturedVideoEntity

@Database(
    entities = [
        CapturedVideoEntity::class,
        BrowsingHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao
    abstract fun browsingHistoryDao(): BrowsingHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "videohunter_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
