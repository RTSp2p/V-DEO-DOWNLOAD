package com.example.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.CapturedVideoEntity
import com.example.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val repository: VideoRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentSort = MutableStateFlow(SortOption.RECENT_FIRST)
    val currentSort: StateFlow<SortOption> = _currentSort.asStateFlow()

    // Base flow of all completed videos from Room
    private val _rawCompletedVideos = repository.observeCompletedVideos()

    // Filtered and sorted flow exposed to the UI
    val filteredVideos: StateFlow<List<CapturedVideoEntity>> = combine(
        _rawCompletedVideos,
        _searchQuery,
        _currentSort
    ) { videos, query, sort ->
        val filtered = if (query.isBlank()) {
            videos
        } else {
            videos.filter { it.fileName.contains(query, ignoreCase = true) }
        }

        when (sort) {
            SortOption.RECENT_FIRST -> filtered.sortedByDescending { it.capturedAt }
            SortOption.OLDEST_FIRST -> filtered.sortedBy { it.capturedAt }
            SortOption.SIZE_DESC -> filtered.sortedByDescending { it.fileSizeBytes }
            SortOption.SIZE_ASC -> filtered.sortedBy { it.fileSizeBytes }
            SortOption.NAME_ASC -> filtered.sortedBy { it.fileName.lowercase() }
            SortOption.NAME_DESC -> filtered.sortedByDescending { it.fileName.lowercase() }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // UI Feedback events
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    // Currently playing video for the internal Player
    private val _activePlayingVideo = MutableStateFlow<CapturedVideoEntity?>(null)
    val activePlayingVideo: StateFlow<CapturedVideoEntity?> = _activePlayingVideo.asStateFlow()

    // Rename dialog state
    private val _videoToRename = MutableStateFlow<CapturedVideoEntity?>(null)
    val videoToRename: StateFlow<CapturedVideoEntity?> = _videoToRename.asStateFlow()

    // Delete confirmation dialog state
    private val _videoToDelete = MutableStateFlow<CapturedVideoEntity?>(null)
    val videoToDelete: StateFlow<CapturedVideoEntity?> = _videoToDelete.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOption(option: SortOption) {
        _currentSort.value = option
    }

    fun playVideo(video: CapturedVideoEntity) {
        _activePlayingVideo.value = video
    }

    fun closePlayer() {
        _activePlayingVideo.value = null
    }

    fun promptRename(video: CapturedVideoEntity) {
        _videoToRename.value = video
    }

    fun dismissRename() {
        _videoToRename.value = null
    }

    fun confirmRename(id: Long, newName: String) {
        viewModelScope.launch {
            val success = repository.renameVideo(id, newName)
            _videoToRename.value = null
            if (success) {
                _userMessage.emit("Vídeo renomeado para $newName")
            } else {
                _userMessage.emit("Falha ao renomear arquivo")
            }
        }
    }

    fun promptDelete(video: CapturedVideoEntity) {
        _videoToDelete.value = video
    }

    fun dismissDelete() {
        _videoToDelete.value = null
    }

    fun confirmDelete(id: Long) {
        viewModelScope.launch {
            val success = repository.deleteVideo(id)
            _videoToDelete.value = null
            if (success) {
                _userMessage.emit("Vídeo e arquivo excluídos com sucesso")
            } else {
                _userMessage.emit("Falha ao excluir arquivo")
            }
        }
    }

    class Factory(private val repository: VideoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DownloadsViewModel(repository) as T
        }
    }
}
