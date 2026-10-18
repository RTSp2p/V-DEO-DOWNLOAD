package com.example.ui.home

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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    ALL("TODOS"),
    DOWNLOADING("BAIXANDO"),
    COMPLETED("COMPLETOS"),
    FAILED("ERROS")
}

class HomeViewModel(
    private val repository: VideoRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(HomeTab.ALL)
    val selectedTab: StateFlow<HomeTab> = _selectedTab.asStateFlow()

    val allVideos: StateFlow<List<CapturedVideoEntity>> = repository.observeAllVideos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val downloadingVideos: StateFlow<List<CapturedVideoEntity>> = repository.observeDownloadingVideos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val completedVideos: StateFlow<List<CapturedVideoEntity>> = repository.observeCompletedVideos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val failedVideos: StateFlow<List<CapturedVideoEntity>> = repository.observeFailedVideos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activePlayingVideo = MutableStateFlow<CapturedVideoEntity?>(null)
    val activePlayingVideo: StateFlow<CapturedVideoEntity?> = _activePlayingVideo.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun selectTab(tab: HomeTab) {
        _selectedTab.value = tab
    }

    fun playVideo(video: CapturedVideoEntity) {
        _activePlayingVideo.value = video
    }

    fun closePlayer() {
        _activePlayingVideo.value = null
    }

    fun cancelDownload(video: CapturedVideoEntity) {
        viewModelScope.launch {
            repository.cancelSingleDownload(video)
            _userMessage.emit("Download cancelado: ${video.fileName}")
        }
    }

    fun retryDownload(video: CapturedVideoEntity) {
        viewModelScope.launch {
            val success = repository.retryDownload(video)
            if (success) {
                _userMessage.emit("Download reiniciado: ${video.fileName}")
            } else {
                _userMessage.emit("Falha ao reiniciar download")
            }
        }
    }

    fun deleteVideo(video: CapturedVideoEntity) {
        viewModelScope.launch {
            repository.deleteVideo(video.id)
            _userMessage.emit("Item removido")
        }
    }

    class Factory(private val repository: VideoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}
