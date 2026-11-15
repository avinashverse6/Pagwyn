package io.pagwyn.reader.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.core.DocumentResolver
import io.pagwyn.reader.core.PagwynDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the home screen shows, in one immutable snapshot. */
data class HomeState(
    val loading: Boolean = true,
    val recent: List<PagwynDocument> = emptyList(),
    val starred: List<PagwynDocument> = emptyList(),
    val history: List<PagwynDocument> = emptyList(),
    val query: String = "",
    val results: List<PagwynDocument> = emptyList(),
    val lastRead: PagwynDocument? = null,
) {
    val isEmpty: Boolean get() = recent.isEmpty() && starred.isEmpty()
    val searching: Boolean get() = query.isNotBlank()
}

/**
 * Home screen state holder.
 *
 * All reads happen off the main thread; the screen only ever renders a snapshot.
 * Search is debounced so typing stays responsive even with a large history.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PagwynApplication.documents
    private val settings = PagwynApplication.settings

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        refresh()
        observeQuery()
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            queryFlow.debounce(160).collect { query ->
                if (query.isBlank()) {
                    _state.update { it.copy(query = "", results = emptyList()) }
                } else {
                    val hits = withContext(Dispatchers.IO) { store.search(query) }
                    _state.update { it.copy(query = query, results = hits) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val all = store.documents()
                HomeState(
                    loading = false,
                    recent = all.take(RECENT_LIMIT),
                    starred = all.filter { it.isStarred }.take(STARRED_LIMIT),
                    history = all.take(HISTORY_LIMIT),
                    lastRead = all.maxByOrNull { it.lastOpenedAt },
                    query = queryFlow.value,
                    results = if (queryFlow.value.isBlank()) emptyList() else store.search(queryFlow.value),
                )
            }
            _state.value = snapshot
        }
    }

    fun onQueryChanged(query: String) {
        queryFlow.value = query
        _state.update { it.copy(query = query) }
        if (query.isBlank()) _state.update { it.copy(results = emptyList()) }
    }

    fun toggleStar(document: PagwynDocument) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setStarred(document.uri, !document.isStarred) }
            refresh()
        }
    }

    fun removeDocument(document: PagwynDocument) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(document.uri) }
            refresh()
        }
    }

    /**
     * Records that a document was opened. Called before launching the reader so the
     * home list is correct even if the reader takes a moment to report page count.
     */
    fun onDocumentOpened(uri: Uri) {
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) { store.find(uri) }
            if (existing == null) {
                val resolved = withContext(Dispatchers.IO) {
                    DocumentResolver.resolve(getApplication(), uri)
                }
                withContext(Dispatchers.IO) {
                    store.upsert(
                        PagwynDocument.create(resolved, pageCount = 0).copy(
                            lastOpenedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            withContext(Dispatchers.IO) { store.trimHistory(settings.historyLimit) }
            refresh()
        }
    }

    private companion object {
        const val RECENT_LIMIT = 6
        const val STARRED_LIMIT = 8
        const val HISTORY_LIMIT = 50
    }
}
