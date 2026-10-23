package com.example.browser

import com.example.data.local.entity.StreamType

data class DetectedMediaPayload(
    val url: String,
    val fileName: String,
    val extension: String,
    val isStream: Boolean = false,
    val streamType: StreamType = StreamType.DIRECT,
    val headers: Map<String, String> = emptyMap(),
    val title: String = "",
    val thumbnailUrl: String? = null,
    val isDrmProtected: Boolean = false,
    val fileSizeBytes: Long = 0L
)
