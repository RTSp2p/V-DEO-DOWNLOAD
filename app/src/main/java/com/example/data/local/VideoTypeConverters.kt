package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.local.entity.StreamType
import org.json.JSONObject

class VideoTypeConverters {

    @TypeConverter
    fun fromStreamType(streamType: StreamType): String = streamType.name

    @TypeConverter
    fun toStreamType(value: String?): StreamType {
        if (value.isNullOrBlank()) return StreamType.DIRECT
        return try {
            StreamType.valueOf(value)
        } catch (_: IllegalArgumentException) {
            StreamType.DIRECT
        }
    }

    @TypeConverter
    fun fromHeadersMap(headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty()) return "{}"
        return try {
            val json = JSONObject()
            for ((k, v) in headers) {
                json.put(k, v)
            }
            json.toString()
        } catch (_: Exception) {
            "{}"
        }
    }

    @TypeConverter
    fun toHeadersMap(jsonString: String?): Map<String, String> {
        if (jsonString.isNullOrBlank() || jsonString == "{}") return emptyMap()
        return try {
            val json = JSONObject(jsonString)
            val result = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.optString(key, "")
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
