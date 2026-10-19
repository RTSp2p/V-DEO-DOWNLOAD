package com.example.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SessionManager
import com.example.data.local.entity.BrowsingHistoryEntity
import com.example.data.local.entity.CapturedVideoEntity
import com.example.data.repository.SettingsRepository
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
    private val repository: VideoRepository,
    private val sessionManager: SessionManager,
    private val settingsRepository: SettingsRepository? = null
) : ViewModel() {

    // Detected videos from Room (status DETECTED)
    val detectedVideos: StateFlow<List<CapturedVideoEntity>> = repository.observeDetectedVideos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val downloadFolderUri: StateFlow<String?> = (settingsRepository?.downloadFolderUri
        ?: MutableStateFlow<String?>(null))
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Current page URL with session restoration
    private val _currentUrl = MutableStateFlow(
        sessionManager.getLastUrl() ?: "https://html5test.com"
    )
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("Início")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    // Chave de controle (toggle booleano) para pausar ou retomar a detecção do video sniffer
    private val _isSnifferEnabled = MutableStateFlow(true)
    val isSnifferEnabled: StateFlow<Boolean> = _isSnifferEnabled.asStateFlow()

    // Histórico de navegação
    val browsingHistory: StateFlow<List<BrowsingHistoryEntity>> = repository.observeBrowsingHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isHistoryDialogVisible = MutableStateFlow(false)
    val isHistoryDialogVisible: StateFlow<Boolean> = _isHistoryDialogVisible.asStateFlow()

    // Batch download selection state
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    // BottomSheet visibility
    private val _isBatchSheetVisible = MutableStateFlow(false)
    val isBatchSheetVisible: StateFlow<Boolean> = _isBatchSheetVisible.asStateFlow()

    // UI events (e.g. toast / snackbar)
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun updateUrl(newUrl: String) {
        _currentUrl.value = newUrl
        sessionManager.saveLastUrl(newUrl)
        repository.updateWebViewContext(pageUrl = newUrl, userAgent = null)
    }

    fun updateWebViewContext(pageUrl: String?, userAgent: String?) {
        repository.updateWebViewContext(pageUrl = pageUrl, userAgent = userAgent)
    }

    fun setPageTitle(title: String?) {
        val resolved = title?.takeIf { it.isNotBlank() } ?: "Página Web"
        _pageTitle.value = resolved
        // Registra visita no histórico
        val url = _currentUrl.value
        if (url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("chrome:")) {
            viewModelScope.launch {
                repository.recordBrowsingVisit(url, resolved)
            }
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setProgress(prog: Int) {
        _progress.value = prog
    }

    fun setSnifferEnabled(enabled: Boolean) {
        _isSnifferEnabled.value = enabled
        viewModelScope.launch {
            if (enabled) {
                _userMessage.emit("Captura de vídeo ativada")
            } else {
                _userMessage.emit("Captura de vídeo pausada")
            }
        }
    }

    fun openHistoryDialog() {
        _isHistoryDialogVisible.value = true
    }

    fun closeHistoryDialog() {
        _isHistoryDialogVisible.value = false
    }

    fun clearBrowsingHistory() {
        viewModelScope.launch {
            repository.clearBrowsingHistory()
            _userMessage.emit("Histórico de navegação limpo")
        }
    }

    fun openBatchSheet() {
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
        // Se a captura estiver pausada pelo usuário, descarta imediatamente
        if (!_isSnifferEnabled.value) return

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
            _selectedIds.value = _selectedIds.value - video.id
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
                _selectedIds.value = emptySet()
                _isBatchSheetVisible.value = false
                _userMessage.emit("$started downloads em lote iniciados!")
            }
        }
    }

    /**
     * Remoção individual (por gesto de swipe para a esquerda ou botão) de um vídeo DETECTED:
     * Executa DELETE FROM captured_videos WHERE id = :id AND downloadStatus = 'DETECTED',
     * liberando a URL para re-detecção futura e atualizando o contador do FAB.
     */
    fun removeDetectedVideo(video: CapturedVideoEntity) {
        viewModelScope.launch {
            val removed = repository.deleteDetectedVideo(video.id)
            _selectedIds.value = _selectedIds.value - video.id
            if (removed) {
                _userMessage.emit("Vídeo removido da lista: ${video.fileName}")
            }
        }
    }

    /**
     * Remoção em lote dos vídeos detectados selecionados.
     */
    fun removeSelectedDetectedVideos() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val removedCount = repository.deleteDetectedVideos(ids)
            _selectedIds.value = emptySet()
            if (removedCount > 0) {
                _userMessage.emit("$removedCount vídeo(s) detectado(s) removido(s)")
            }
        }
    }

    fun cancelActiveDownloads() {
        viewModelScope.launch {
            val count = repository.cancelAllActiveDownloads()
            if (count > 0) {
                _userMessage.emit("$count downloads ativos cancelados")
            } else {
                _userMessage.emit("Nenhum download ativo para cancelar")
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

    fun setDownloadFolderUri(uriString: String?) {
        viewModelScope.launch {
            settingsRepository?.setDownloadFolderUri(uriString)
            repository.updateDownloadFolderUri(uriString)
            _userMessage.emit("Pasta de download atualizada!")
        }
    }

    class Factory(
        private val repository: VideoRepository,
        private val sessionManager: SessionManager,
        private val settingsRepository: SettingsRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BrowserViewModel(repository, sessionManager, settingsRepository) as T
        }
    }
}
