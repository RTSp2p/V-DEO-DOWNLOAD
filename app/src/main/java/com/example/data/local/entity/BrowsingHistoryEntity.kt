package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "browsing_history")
data class BrowsingHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis()
)
