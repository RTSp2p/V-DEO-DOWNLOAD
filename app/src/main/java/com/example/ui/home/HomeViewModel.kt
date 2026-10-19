package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.CapturedVideoEntity
import com.example.data.repository.SettingsRepository
import com.example.data.repository.ThemeMode
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
    private val repository: VideoRepository,
    private val settingsRepository: SettingsRepository? = null
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

    // Seleção em lote nas abas da Home
    private val _selectedVideoIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedVideoIds: StateFlow<Set<Long>> = _selectedVideoIds.asStateFlow()

    // Diálogo de Configurações
    private val _isSettingsDialogVisible = MutableStateFlow(false)
    val isSettingsDialogVisible: StateFlow<Boolean> = _isSettingsDialogVisible.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = (settingsRepository?.themeMode
        ?: MutableStateFlow(ThemeMode.DARK))
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.DARK
        )

    val downloadFolderUri: StateFlow<String?> = (settingsRepository?.downloadFolderUri
        ?: MutableStateFlow<String?>(null))
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _activePlayingVideo = MutableStateFlow<CapturedVideoEntity?>(null)
    val activePlayingVideo: StateFlow<CapturedVideoEntity?> = _activePlayingVideo.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun selectTab(tab: HomeTab) {
        _selectedTab.value = tab
        _selectedVideoIds.value = emptySet()
    }

    fun getCurrentTabVideos(): List<CapturedVideoEntity> {
        return when (_selectedTab.value) {
            HomeTab.ALL -> allVideos.value
            HomeTab.DOWNLOADING -> downloadingVideos.value
            HomeTab.COMPLETED -> completedVideos.value
            HomeTab.FAILED -> failedVideos.value
        }
    }

    fun toggleVideoSelection(id: Long) {
        val current = _selectedVideoIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedVideoIds.value = current
    }

    fun toggleSelectAllCurrentTab() {
        val currentTabItems = getCurrentTabVideos()
        if (currentTabItems.isEmpty()) return
        val currentTabIds = currentTabItems.map { it.id }.toSet()
        val selected = _selectedVideoIds.value
        if (selected.containsAll(currentTabIds)) {
            _selectedVideoIds.value = emptySet()
        } else {
            _selectedVideoIds.value = currentTabIds
        }
    }

    fun clearSelection() {
        _selectedVideoIds.value = emptySet()
    }

    /**
     * Limpa os itens selecionados do histórico da aba atual.
     * Para itens COMPLETED, mantém os arquivos físicos baixados intactos no armazenamento,
     * mas remove os registros do banco Room liberando a URL para re-detecção futura.
     */
    fun clearSelectedFromHistory() {
        val idsToClear = _selectedVideoIds.value.toList()
        if (idsToClear.isEmpty()) return
        viewModelScope.launch {
            val count = repository.removeVideosFromHistory(idsToClear)
            _selectedVideoIds.value = emptySet()
            if (count > 0) {
                _userMessage.emit("$count item(ns) removido(s) do histórico")
            }
        }
    }

    fun openSettingsDialog() {
        _isSettingsDialogVisible.value = true
    }

    fun closeSettingsDialog() {
        _isSettingsDialogVisible.value = false
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository?.setThemeMode(mode)
            _userMessage.emit("Tema alterado para: ${mode.label}")
        }
    }

    fun setDownloadFolderUri(uriString: String?) {
        viewModelScope.launch {
            settingsRepository?.setDownloadFolderUri(uriString)
            repository.updateDownloadFolderUri(uriString)
            if (uriString.isNullOrBlank()) {
                _userMessage.emit("Pasta de download restaurada para o padrão")
            } else {
                _userMessage.emit("Pasta de download atualizada!")
            }
        }
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
            repository.removeVideosFromHistory(listOf(video.id))
            _selectedVideoIds.value = _selectedVideoIds.value - video.id
            _userMessage.emit("Item removido do histórico")
        }
    }

    class Factory(
        private val repository: VideoRepository,
        private val settingsRepository: SettingsRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, settingsRepository) as T
        }
    }
}
