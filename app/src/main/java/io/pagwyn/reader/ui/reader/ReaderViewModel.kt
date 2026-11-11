package io.pagwyn.reader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.core.DocumentResolver
import io.pagwyn.reader.core.DocumentSource
import io.pagwyn.reader.core.Formatters
import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.ReaderTheme
import io.pagwyn.reader.data.ZoomMode
import io.pagwyn.reader.pdf.PageInfo
import io.pagwyn.reader.pdf.PdfDocument
import io.pagwyn.reader.pdf.PdfOpenException
import io.pagwyn.reader.pdf.SearchHit
import io.pagwyn.reader.render.PageBitmapCache
import io.pagwyn.reader.render.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reader ViewModel.
 *
 * Owns the [PageRenderer] lifetime, the reading session state and persistence of
 * the reading position. Views stay *dumb*: they render [state] and forward user
 * intent, which keeps rotation/resume logic in one testable place.
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = PagwynApplication.settings
    private val documents = PagwynApplication.documents
    private val cache = PagwynApplication.pageCache

    private val renderer = PageRenderer(application, cache)

    private val _state = MutableStateFlow(
        ReaderState(
            mode = settings.defaultMode,
            zoomMode = settings.defaultZoom,
            theme = settings.readerTheme,
        ),
    )
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    var document: PdfDocument? = null
        private set

    /** Source information for the open document, used by metadata and sharing. */
    var source: DocumentSource? = null
        private set

    private var openUri: Uri? = null
    private var pendingPassword: String? = null
    private var searchJob: Job? = null
    private var lastPersistedPage = -1
    private var resumePage = 0
    private var offsetPt = 0f
    private var sharpWidthPx = 0
    private val bookmarkedPages = mutableSetOf<Int>()

    val pageCache: PageBitmapCache get() = cache
    val pageRenderer: PageRenderer get() = renderer

    // ------------------------------------------------------------------ opening

    /**
     * Opens [uri].
     *
     * @param resume true when the document is being restored (uses the saved
     *   reading position); false for a fresh open, which still restores the
     *   position because that is what readers expect.
     */
    fun open(uri: Uri, password: String? = null, resume: Boolean = true) {
        if (openUri == uri && document != null && password == pendingPassword) return
        openUri = uri
        pendingPassword = password
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            val resolved = withContext(Dispatchers.IO) { DocumentResolver.resolve(app, uri) }
            source = resolved
            _state.update {
                it.copy(
                    documentTitle = resolved.title,
                    documentSubtitle = buildSubtitle(resolved, 0),
                )
            }

            val result = runCatching { renderer.open(uri, password, resolved) }
            result.fold(
                onSuccess = { pdf -> onDocumentOpened(pdf, resolved, resume) },
                onFailure = { throwable -> handleOpenFailure(throwable) },
            )
        }
    }

    private fun onDocumentOpened(pdf: PdfDocument, resolved: DocumentSource, resume: Boolean) {
        document = pdf
        val existing = documents.find(pdf.source.uri)
        bookmarkedPages.clear()
        bookmarkedPages.addAll(documents.bookmarks(pdf.source.uri))

        resumePage = if (resume) existing?.lastPage?.coerceIn(0, pdf.pageCount - 1) ?: 0 else 0
        offsetPt = if (resume) existing?.lastOffsetPt ?: 0f else 0f

        val record = existing?.copy(
            displayName = resolved.displayName,
            pageCount = pdf.pageCount,
            sizeBytes = if (resolved.size > 0) resolved.size else existing.sizeBytes,
            lastOpenedAt = System.currentTimeMillis(),
            location = resolved.location ?: existing.location,
        ) ?: io.pagwyn.reader.core.PagwynDocument.create(resolved, pdf.pageCount)

        viewModelScope.launch(Dispatchers.IO) {
            documents.upsert(record)
            documents.trimHistory(settings.historyLimit)
        }
        settings.lastDocumentUri = pdf.source.uri.toString()

        if (existing != null) {
            settings.lastDocumentUri = existing.uriString
        }

        _state.update {
            it.copy(
                loading = false,
                error = null,
                pageCount = pdf.pageCount,
                currentPage = resumePage,
                documentTitle = resolved.title,
                documentSubtitle = buildSubtitle(resolved, pdf.pageCount),
                isBookmarked = bookmarkedPages.contains(resumePage),
                canJumpBack = false,
            )
        }
        restoreZoom(existing)
    }

    private fun restoreZoom(existing: io.pagwyn.reader.core.PagwynDocument?) {
        if (existing == null) {
            _state.update { it.copy(zoomMode = settings.defaultZoom, zoomPercent = 100) }
            return
        }
        val mode = ZoomMode.from(existing.lastZoomMode)
        _state.update {
            it.copy(
                zoomMode = mode,
                zoomPercent = if (existing.lastZoom > 1f) (existing.lastZoom * 100).toInt() else 100,
                rotation = existing.lastRotation,
            )
        }
    }

    private fun handleOpenFailure(throwable: Throwable) {
        val error = when (throwable) {
            is PdfOpenException.NeedsPassword -> ReaderError.Password
            is PdfOpenException.WrongPassword -> ReaderError.WrongPassword
            is PdfOpenException.UnsupportedEncryption -> ReaderError.UnsupportedEncryption
            is PdfOpenException.NotFound -> ReaderError.NotFound
            is PdfOpenException.TooLarge -> ReaderError.TooLarge
            is PdfOpenException.Corrupt -> ReaderError.Corrupt
            is SecurityException -> ReaderError.Password
            else -> ReaderError.Corrupt
        }
        document = null
        _state.update { it.copy(loading = false, error = error) }
    }

    private fun buildSubtitle(source: DocumentSource, pageCount: Int): String {
        val parts = mutableListOf<String>()
        if (pageCount > 0) parts.add("$pageCount pages")
        if (source.size > 0) parts.add(Formatters.fileSize(source.size))
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ navigation

    fun onPageVisible(page: Int) {
        val count = _state.value.pageCount
        if (count <= 0) return
        val clamped = page.coerceIn(0, count - 1)
        if (clamped == _state.value.currentPage) {
            persistIfNeeded(clamped)
            return
        }
        _state.update {
            it.copy(
                currentPage = clamped,
                isBookmarked = bookmarkedPages.contains(clamped),
                canJumpBack = resumePage > 0 && clamped > resumePage + 3,
                jumpBackPage = resumePage,
            )
        }
        persistIfNeeded(clamped)
    }

    private fun persistIfNeeded(page: Int) {
        val uri = document?.source?.uri ?: return
        if (page == lastPersistedPage) return
        lastPersistedPage = page
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            documents.saveProgress(
                uri = uri,
                page = page,
                pageCount = s.pageCount,
                offsetPt = offsetPt,
                zoom = s.zoomPercent / 100f,
                zoomMode = s.zoomMode.storedValue,
                rotation = s.rotation,
            )
        }
    }

    /** Called when the reader is leaving, so the position survives a process death. */
    fun onLeaving() {
        val uri = document?.source?.uri ?: return
        val s = _state.value
        runCatching {
            documents.saveProgress(
                uri = uri,
                page = s.currentPage,
                pageCount = s.pageCount,
                offsetPt = offsetPt,
                zoom = s.zoomPercent / 100f,
                zoomMode = s.zoomMode.storedValue,
                rotation = s.rotation,
            )
        }
    }

    fun setZoomPercent(percent: Int) {
        _state.update { it.copy(zoomPercent = percent, zoomMode = ZoomMode.ACTUAL_SIZE) }
    }

    fun setZoomMode(mode: ZoomMode) {
        _state.update {
            it.copy(zoomMode = mode, zoomPercent = if (mode == ZoomMode.FIT_WIDTH || mode == ZoomMode.FIT_PAGE) 100 else it.zoomPercent)
        }
    }

    fun rotate() {
        _state.update { it.copy(rotation = (it.rotation + 90) % 360) }
        persistIfNeeded(_state.value.currentPage)
    }

    fun toggleBookmark(): Boolean {
        val uri = document?.source?.uri ?: return false
        val page = _state.value.currentPage
        val added = documents.toggleBookmark(uri, page)
        if (added) bookmarkedPages.add(page) else bookmarkedPages.remove(page)
        _state.update { it.copy(isBookmarked = added) }
        return added
    }

    fun isBookmarked(page: Int): Boolean = bookmarkedPages.contains(page)

    /** Removes a bookmark from the bookmarks sheet. */
    fun removeBookmark(page: Int) {
        val uri = document?.source?.uri ?: return
        documents.removeBookmark(uri, page)
        bookmarkedPages.remove(page)
        _state.update { if (it.currentPage == page) it.copy(isBookmarked = false) else it }
    }

    /** Live zoom feedback from a gesture; does not change the chosen fit mode. */
    fun onZoomChanged(percent: Int) {
        _state.update { it.copy(zoomPercent = percent) }
    }

    /** Renders a small page bitmap for the thumbnail grid. */
    suspend fun renderThumbnail(page: Int, widthPx: Int = THUMBNAIL_WIDTH): Bitmap? =
        renderer.renderThumbnail(page, widthPx)

    fun bookmarks(): List<Int> = bookmarkedPages.sorted()

    fun setTheme(theme: ReaderTheme) {
        settings.readerTheme = theme
        _state.update { it.copy(theme = theme) }
    }

    fun toggleTheme() {
        val next = when (_state.value.theme) {
            ReaderTheme.LIGHT -> ReaderTheme.SEPIA
            ReaderTheme.SEPIA -> ReaderTheme.DARK
            ReaderTheme.DARK -> ReaderTheme.LIGHT
            ReaderTheme.FOLLOW_APP -> ReaderTheme.SEPIA
        }
        setTheme(next)
    }

    fun setMode(mode: ReadingMode) {
        if (_state.value.mode == mode) return
        settings.defaultMode = mode
        _state.update { it.copy(mode = mode) }
    }

    fun setControlsVisible(visible: Boolean) {
        _state.update { it.copy(controlsVisible = visible) }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _state.update { it.copy(fullscreen = fullscreen) }
    }

    fun pageInfo(index: Int): PageInfo? = document?.let { runCatching { it.pageInfo(index) }.getOrNull() }

    fun rememberSharpWidth(widthPx: Int) {
        sharpWidthPx = widthPx
    }

    val lastSharpWidth: Int get() = sharpWidthPx

    // ------------------------------------------------------------------ search

    fun startSearch() {
        _state.update { it.copy(searchActive = true) }
    }

    fun stopSearch() {
        searchJob?.cancel()
        _state.update {
            it.copy(searchActive = false, searchQuery = "", searchResults = emptyList(), searchRunning = false)
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchQuery = query, searchResults = emptyList(), searchRunning = false) }
            return
        }
        _state.update { it.copy(searchQuery = query, searchRunning = true) }
        searchJob = viewModelScope.launch {
            val doc = document ?: return@launch
            val hits = withContext(Dispatchers.IO) {
                runCatching { doc.search(query, SEARCH_LIMIT) { searchJob?.isCancelled == true } }
                    .getOrDefault(emptyList())
            }
            _state.update { it.copy(searchResults = hits, searchRunning = false) }
        }
    }

    fun hasTextLayer(onResult: (Boolean) -> Unit) {
        val doc = document
        if (doc == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            onResult(withContext(Dispatchers.IO) { runCatching { doc.hasTextLayer }.getOrDefault(false) })
        }
    }

    suspend fun pageText(page: Int): String? = renderer.pageText(page)

    // ------------------------------------------------------------------ rendering

    /**
     * Tells the renderer what to draw, most important page first.
     *
     * Called on every scroll frame, so it must stay allocation-light and is a no-op
     * when nothing relevant changed.
     */
    fun requestRender(visiblePages: List<Int>, widthPx: Int) {
        val rotation = _state.value.rotation
        renderer.setViewport(visiblePages, widthPx, rotation)
    }

    suspend fun pageInfoAsync(index: Int): PageInfo? = renderer.pageInfo(index)

    suspend fun docPageInfo(index: Int): PageInfo? = renderer.pageInfo(index)

    fun cachedBitmap(page: Int, widthPx: Int, rotation: Int): Bitmap? =
        renderer.cached(page, widthPx, rotation)

    fun cachedBitmapAny(page: Int): Bitmap? = renderer.cachedAny(page)

    fun trimCache(minPage: Int, maxPage: Int) = renderer.trimAround(minPage, maxPage)

    fun setRendererListener(listener: PageRenderer.Listener?) {
        renderer.listener = listener
    }

    fun setStarred(starred: Boolean) {
        val uri = document?.source?.uri ?: return
        viewModelScope.launch(Dispatchers.IO) { documents.setStarred(uri, starred) }
    }

    override fun onCleared() {
        onLeaving()
        renderer.listener = null
        renderer.close()
        document = null
        super.onCleared()
    }

    private companion object {
        const val SEARCH_LIMIT = 200
        const val THUMBNAIL_WIDTH = 180
    }
}
