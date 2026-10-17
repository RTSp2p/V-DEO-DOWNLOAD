package com.example.ui.browser

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

class BrowserViewModel(
    private val repository: VideoRepository
) : ViewModel() {

    // Detected videos from Room
    val detectedVideos: StateFlow<List<CapturedVideoEntity>> = repository.observeDetectedVideos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current page URL and state
    private val _currentUrl = MutableStateFlow("https://html5test.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("Início")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    // Batch download selection state
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    // BottomSheet visibility
    private val _isBatchSheetVisible = MutableStateFlow(false)
    val isBatchSheetVisible: StateFlow<Boolean> = _isBatchSheetVisible.asStateFlow()

    // UI events (e.g. download started toast)
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun updateUrl(newUrl: String) {
        _currentUrl.value = newUrl
    }

    fun setPageTitle(title: String?) {
        _pageTitle.value = title?.takeIf { it.isNotBlank() } ?: "Página Web"
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setProgress(prog: Int) {
        _progress.value = prog
    }

    fun openBatchSheet() {
        // Pre-select all detected items when opening sheet
        val allIds = detectedVideos.value.map { it.id }.toSet()
        _selectedIds.value = allIds
        _isBatchSheetVisible.value = true
    }

    fun closeBatchSheet() {
        _isBatchSheetVisible.value = false
    }

    fun toggleVideoSelection(id: Long) {
        val current = _selectedIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedIds.value = current
    }

    fun toggleSelectAll() {
        val current = _selectedIds.value
        val allDetected = detectedVideos.value
        if (current.size == allDetected.size) {
            _selectedIds.value = emptySet()
        } else {
            _selectedIds.value = allDetected.map { it.id }.toSet()
        }
    }

    fun onVideoSniffed(url: String, fileName: String, extension: String) {
        viewModelScope.launch {
            val entity = CapturedVideoEntity(
                videoUrl = url,
                fileName = fileName,
                fileType = extension,
                downloadStatus = CapturedVideoEntity.STATUS_DETECTED,
                capturedAt = System.currentTimeMillis()
            )
            val inserted = repository.insertCapturedVideo(entity)
            if (inserted) {
                _userMessage.emit("Vídeo capturado: $fileName")
            }
        }
    }

    fun startSingleDownload(video: CapturedVideoEntity) {
        viewModelScope.launch {
            val success = repository.startDownload(video)
            if (success) {
                _userMessage.emit("Download iniciado: ${video.fileName}")
            } else {
                _userMessage.emit("Falha ao iniciar download")
            }
        }
    }

    fun startBatchDownload() {
        viewModelScope.launch {
            val idsToDownload = _selectedIds.value
            val videos = detectedVideos.value.filter { idsToDownload.contains(it.id) }
            if (videos.isNotEmpty()) {
                val started = repository.startBatchDownload(videos)
                _isBatchSheetVisible.value = false
                _userMessage.emit("$started downloads em lote iniciados!")
            }
        }
    }

    fun clearDetected() {
        viewModelScope.launch {
            repository.clearDetected()
            _selectedIds.value = emptySet()
            _userMessage.emit("Lista de detectados limpa")
        }
    }

    class Factory(private val repository: VideoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BrowserViewModel(repository) as T
        }
    }
}
