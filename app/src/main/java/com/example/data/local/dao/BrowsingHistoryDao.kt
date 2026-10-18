package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.BrowsingHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowsingHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: BrowsingHistoryEntity): Long

    @Query("SELECT * FROM browsing_history ORDER BY visitedAt DESC LIMIT 30")
    fun observeHistory(): Flow<List<BrowsingHistoryEntity>>

    @Query("DELETE FROM browsing_history WHERE id NOT IN (SELECT id FROM browsing_history ORDER BY visitedAt DESC LIMIT 30)")
    suspend fun trimHistory()

    @Query("DELETE FROM browsing_history")
    suspend fun clearHistory()
}
