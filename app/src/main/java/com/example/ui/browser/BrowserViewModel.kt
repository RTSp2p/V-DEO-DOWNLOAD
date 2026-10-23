package com.example.ui.browser

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.browser.DetectedMediaPayload
import com.example.browser.VideoSnifferJsBridge
import com.example.data.SessionManager
import com.example.data.download.StreamQualityOption
import com.example.data.local.entity.BrowsingHistoryEntity
import com.example.data.local.entity.CapturedVideoEntity
import com.example.data.local.entity.StreamType
import com.example.data.repository.SettingsRepository
import com.example.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StreamQualityDialogState(
    val video: CapturedVideoEntity,
    val isLoading: Boolean = true,
    val isDrmProtected: Boolean = false,
    val drmMessage: String? = null,
    val options: List<StreamQualityOption> = emptyList()
)

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = BrowserViewModel.DEFAULT_HOME_URL,
    val title: String = "Início",
    val webStateBundle: Bundle = Bundle(),
    val isDesktopMode: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

class BrowserViewModel(
    private val repository: VideoRepository,
    private val sessionManager: SessionManager,
    private val settingsRepository: SettingsRepository? = null
) : ViewModel() {

    // Bundle persistido no escopo da ViewModel para preservar estado da WebView entre trocas de abas
    val webViewStateBundle: Bundle = Bundle()

    // Suporte a múltiplas abas (Tabs Manager)
    private val initialTabId = java.util.UUID.randomUUID().toString()
    private val initialTab = BrowserTab(
        id = initialTabId,
        url = sessionManager.getLastUrl() ?: DEFAULT_HOME_URL,
        title = "Início"
    )

    private val _tabs = MutableStateFlow<List<BrowserTab>>(listOf(initialTab))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>(initialTabId)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _isTabsSheetVisible = MutableStateFlow(false)
    val isTabsSheetVisible: StateFlow<Boolean> = _isTabsSheetVisible.asStateFlow()

    // Alternador de Modo Desktop (User-Agent)
    private val _isDesktopMode = MutableStateFlow(false)
    val isDesktopMode: StateFlow<Boolean> = _isDesktopMode.asStateFlow()

    // AdBlocker estado observable
    val isAdBlockerEnabled: StateFlow<Boolean> = (settingsRepository?.isAdBlockerEnabled
        ?: MutableStateFlow(true))
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

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

    // Current page URL with session restoration from DataStore
    private val _currentUrl = MutableStateFlow(
        sessionManager.getLastUrl() ?: DEFAULT_HOME_URL
    )
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("Início")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    // Chave de controle persistente (DataStore) para pausar ou retomar a detecção do video sniffer
    private val _isCaptureEnabled = MutableStateFlow(sessionManager.isCaptureEnabled())
    val isCaptureEnabled: StateFlow<Boolean> = _isCaptureEnabled.asStateFlow()
    val isSnifferEnabled: StateFlow<Boolean> = _isCaptureEnabled.asStateFlow()

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

    // Estado do Seletor de Qualidade / Alerta de DRM para Streams Adaptativas (HLS / DASH)
    private val _qualityDialogState = MutableStateFlow<StreamQualityDialogState?>(null)
    val qualityDialogState: StateFlow<StreamQualityDialogState?> = _qualityDialogState.asStateFlow()

    // Estado do Player Interno para pré-visualização online de vídeos/streams detectados
    private val _previewPlayingVideo = MutableStateFlow<CapturedVideoEntity?>(null)
    val previewPlayingVideo: StateFlow<CapturedVideoEntity?> = _previewPlayingVideo.asStateFlow()

    // UI events (e.g. toast / snackbar)
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    init {
        viewModelScope.launch {
            sessionManager.isCaptureEnabledFlow.collectLatest { enabled ->
                _isCaptureEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            sessionManager.lastUrlFlow.collectLatest { savedUrl ->
                if (!savedUrl.isNullOrBlank() && savedUrl != "about:blank" && _currentUrl.value == DEFAULT_HOME_URL) {
                    _currentUrl.value = savedUrl
                }
            }
        }
    }

    /**
     * Salva automaticamente a URL no disco/DataStore e sincroniza o estado atual da ViewModel e da aba ativa.
     */
    fun saveLastVisitedUrl(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isNotBlank() &&
            cleanUrl != "about:blank" &&
            !cleanUrl.startsWith("about:") &&
            !cleanUrl.startsWith("chrome:") &&
            !cleanUrl.startsWith("javascript:")
        ) {
            _currentUrl.value = cleanUrl
            sessionManager.saveLastUrl(cleanUrl)
            repository.updateWebViewContext(pageUrl = cleanUrl, userAgent = null)

            // Atualiza a URL na aba ativa
            val currentId = _activeTabId.value
            _tabs.value = _tabs.value.map {
                if (it.id == currentId) it.copy(url = cleanUrl) else it
            }
        }
    }

    fun updateUrl(newUrl: String) {
        saveLastVisitedUrl(newUrl)
    }

    fun updateWebViewContext(pageUrl: String?, userAgent: String?) {
        repository.updateWebViewContext(pageUrl = pageUrl, userAgent = userAgent)
    }

    fun setPageTitle(title: String?) {
        val resolved = title?.takeIf { it.isNotBlank() } ?: "Página Web"
        _pageTitle.value = resolved
        val url = _currentUrl.value

        // Atualiza título na aba ativa
        val currentId = _activeTabId.value
        _tabs.value = _tabs.value.map {
            if (it.id == currentId) it.copy(title = resolved) else it
        }

        if (url.isNotBlank() && url != "about:blank" && !url.startsWith("about:") && !url.startsWith("chrome:")) {
            viewModelScope.launch {
                repository.recordBrowsingVisit(url, resolved)
            }
        }
    }

    // --- Gerenciador de Abas (Tabs Manager) ---

    fun showTabsSheet() {
        _isTabsSheetVisible.value = true
    }

    fun hideTabsSheet() {
        _isTabsSheetVisible.value = false
    }

    fun openNewTab(url: String = DEFAULT_HOME_URL, title: String = "Início", currentBundle: Bundle? = null) {
        currentBundle?.let { saveActiveTabBundle(it) }

        val cleanUrl = if (url.isBlank()) DEFAULT_HOME_URL else url
        val newTab = BrowserTab(
            url = cleanUrl,
            title = title,
            isDesktopMode = _isDesktopMode.value
        )
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newTab.id
        _currentUrl.value = newTab.url
        _pageTitle.value = newTab.title
        _isTabsSheetVisible.value = false
        saveLastVisitedUrl(cleanUrl)
    }

    fun selectTab(tabId: String, currentBundle: Bundle? = null) {
        currentBundle?.let { saveActiveTabBundle(it) }
        val targetTab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        _activeTabId.value = tabId
        _currentUrl.value = targetTab.url
        _pageTitle.value = targetTab.title
        _isDesktopMode.value = targetTab.isDesktopMode
        _isTabsSheetVisible.value = false
    }

    fun closeTab(tabId: String, currentBundle: Bundle? = null) {
        currentBundle?.let { saveActiveTabBundle(it) }
        val currentList = _tabs.value
        val closingIndex = currentList.indexOfFirst { it.id == tabId }
        val updatedList = currentList.filterNot { it.id == tabId }

        if (updatedList.isEmpty()) {
            val freshTab = BrowserTab(url = DEFAULT_HOME_URL, title = "Início")
            _tabs.value = listOf(freshTab)
            _activeTabId.value = freshTab.id
            _currentUrl.value = freshTab.url
            _pageTitle.value = freshTab.title
            _isDesktopMode.value = false
            return
        }

        _tabs.value = updatedList
        if (_activeTabId.value == tabId) {
            val newIndex = (closingIndex - 1).coerceAtLeast(0).coerceAtMost(updatedList.lastIndex)
            val nextTab = updatedList[newIndex]
            _activeTabId.value = nextTab.id
            _currentUrl.value = nextTab.url
            _pageTitle.value = nextTab.title
            _isDesktopMode.value = nextTab.isDesktopMode
        }
    }

    fun saveActiveTabBundle(bundle: Bundle) {
        val currentId = _activeTabId.value
        _tabs.value = _tabs.value.map {
            if (it.id == currentId) {
                it.copy(webStateBundle = bundle)
            } else {
                it
            }
        }
    }

    // --- Alternador de Modo Desktop (User-Agent) ---

    fun toggleDesktopMode(): Boolean {
        val newMode = !_isDesktopMode.value
        _isDesktopMode.value = newMode
        val currentId = _activeTabId.value
        _tabs.value = _tabs.value.map {
            if (it.id == currentId) it.copy(isDesktopMode = newMode) else it
        }
        viewModelScope.launch {
            _userMessage.emit(if (newMode) "Modo Computador ativado" else "Modo Mobile ativado")
        }
        return newMode
    }

    // --- Configuração do AdBlocker ---

    fun setAdBlockerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository?.setAdBlockerEnabled(enabled)
            val msg = if (enabled) "AdBlocker ativado" else "AdBlocker desativado"
            _userMessage.emit(msg)
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setProgress(prog: Int) {
        _progress.value = prog
    }

    fun setCaptureEnabled(enabled: Boolean) {
        _isCaptureEnabled.value = enabled
        sessionManager.saveCaptureEnabled(enabled)
        viewModelScope.launch {
            if (enabled) {
                _userMessage.emit("Captura de vídeo ativada")
            } else {
                _userMessage.emit("Captura de vídeo pausada")
            }
        }
    }

    fun toggleCaptureEnabled() {
        setCaptureEnabled(!_isCaptureEnabled.value)
    }

    fun setSnifferEnabled(enabled: Boolean) {
        setCaptureEnabled(enabled)
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

    fun openPreviewPlayer(video: CapturedVideoEntity) {
        if (video.isDrmProtected) {
            _qualityDialogState.value = StreamQualityDialogState(
                video = video,
                isLoading = false,
                isDrmProtected = true,
                drmMessage = "Conteúdo protegido por DRM (Widevine / PlayReady / SAMPLE-AES)."
            )
            return
        }
        _previewPlayingVideo.value = video
    }

    fun closePreviewPlayer() {
        _previewPlayingVideo.value = null
    }

    fun dismissQualityDialog() {
        _qualityDialogState.value = null
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

    /**
     * Recebe mídia enriquecida com metadados, tipo de stream (DIRECT, HLS, DASH),
     * cabeçalhos anti-403 (Cookie, User-Agent, Referer) e flag de DRM.
     * NUNCA utiliza document.title ou título da aba.
     */
    fun onRichMediaDetected(payload: DetectedMediaPayload) {
        if (!_isCaptureEnabled.value) return

        viewModelScope.launch {
            val cleanFileName = VideoSnifferJsBridge.sanitizeAndroidFileName(payload.fileName, "mp4")
            val cleanTitle = cleanFileName.substringBeforeLast('.')
            val entity = CapturedVideoEntity(
                videoUrl = payload.url,
                fileName = cleanFileName,
                fileType = if (payload.isStream) payload.extension else "mp4",
                fileSizeBytes = payload.fileSizeBytes,
                downloadStatus = CapturedVideoEntity.STATUS_DETECTED,
                capturedAt = System.currentTimeMillis(),
                isStream = payload.isStream,
                streamType = payload.streamType,
                headers = payload.headers,
                thumbnailUrl = payload.thumbnailUrl,
                title = cleanTitle,
                isDrmProtected = payload.isDrmProtected
            )
            val inserted = repository.insertCapturedVideo(entity)
            if (inserted) {
                if (payload.isDrmProtected) {
                    _userMessage.emit("Aviso: Mídia protegida por DRM detectada ($cleanFileName)")
                } else {
                    val tag = if (payload.isStream) "[${payload.streamType.name}] " else ""
                    _userMessage.emit("Vídeo capturado: $tag$cleanFileName")
                }
            }
        }
    }

    fun onVideoDetected(url: String, fileName: String, extension: String) {
        if (!_isCaptureEnabled.value) return

        val headers = VideoSnifferJsBridge.buildContextHeaders(
            mediaUrl = url,
            pageReferer = _currentUrl.value,
            userAgent = VideoSnifferJsBridge.MODERN_MOBILE_USER_AGENT
        )
        val isAdaptive = extension.equals("m3u8", ignoreCase = true) || extension.equals("mpd", ignoreCase = true)
        val streamType = when {
            extension.equals("m3u8", ignoreCase = true) -> StreamType.HLS
            extension.equals("mpd", ignoreCase = true) -> StreamType.DASH
            else -> StreamType.DIRECT
        }
        val cleanFileName = if (fileName.isNotBlank()) {
            VideoSnifferJsBridge.sanitizeAndroidFileName(fileName, "mp4")
        } else {
            VideoSnifferJsBridge.buildFileName(url = url, videoElementTitle = null, extension = "mp4")
        }
        onRichMediaDetected(
            DetectedMediaPayload(
                url = url,
                fileName = cleanFileName,
                extension = if (isAdaptive) extension else "mp4",
                isStream = isAdaptive,
                streamType = streamType,
                headers = headers,
                title = cleanFileName.substringBeforeLast('.')
            )
        )
    }

    fun onVideoSniffed(url: String, fileName: String, extension: String) {
        onVideoDetected(url, fileName, extension)
    }

    /**
     * Inicia download individual.
     * - Se for protegido por DRM, exibe alerta explicativo.
     * - Se for Stream Adaptativa (HLS / DASH), abre o Seletor de Qualidade.
     * - Se for arquivo direto (.mp4 / .webm / .mkv), inicia imediatamente no DownloadService.
     */
    fun startSingleDownload(video: CapturedVideoEntity) {
        if (video.isDrmProtected) {
            _qualityDialogState.value = StreamQualityDialogState(
                video = video,
                isLoading = false,
                isDrmProtected = true,
                drmMessage = "Conteúdo protegido por direitos autorais (DRM Widevine / PlayReady / SAMPLE-AES)."
            )
            return
        }

        if (video.isStream || video.streamType != StreamType.DIRECT) {
            _qualityDialogState.value = StreamQualityDialogState(
                video = video,
                isLoading = true
            )
            viewModelScope.launch {
                val parseResult = repository.parseStreamManifest(video)
                _qualityDialogState.value = StreamQualityDialogState(
                    video = video,
                    isLoading = false,
                    isDrmProtected = parseResult.isDrmProtected,
                    drmMessage = parseResult.drmMessage,
                    options = parseResult.options
                )
            }
            return
        }

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

    fun confirmStreamQualityDownload(option: StreamQualityOption) {
        val currentDialog = _qualityDialogState.value ?: return
        val video = currentDialog.video
        _qualityDialogState.value = null

        viewModelScope.launch {
            val success = repository.startDownloadWithQuality(video, option)
            _selectedIds.value = _selectedIds.value - video.id
            if (success) {
                _userMessage.emit("Download iniciado (${option.label}): ${video.fileName}")
            } else {
                _userMessage.emit("Não foi possível iniciar o download deste stream")
            }
        }
    }

    fun startBatchDownload() {
        viewModelScope.launch {
            val idsToDownload = _selectedIds.value
            val videos = detectedVideos.value.filter { idsToDownload.contains(it.id) }
            if (videos.isNotEmpty()) {
                val drmCount = videos.count { it.isDrmProtected }
                val started = repository.startBatchDownload(videos)
                _selectedIds.value = emptySet()
                _isBatchSheetVisible.value = false
                if (drmCount > 0) {
                    _userMessage.emit("$started download(s) iniciado(s). $drmCount bloqueado(s) por proteção DRM.")
                } else {
                    _userMessage.emit("$started downloads em lote iniciados na maior resolução!")
                }
            }
        }
    }

    fun removeDetectedVideo(video: CapturedVideoEntity) {
        viewModelScope.launch {
            val removed = repository.deleteDetectedVideo(video.id)
            _selectedIds.value = _selectedIds.value - video.id
            if (removed) {
                _userMessage.emit("Vídeo removido da lista: ${video.fileName}")
            }
        }
    }

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

    companion object {
        const val DEFAULT_HOME_URL = "https://html5test.com"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
