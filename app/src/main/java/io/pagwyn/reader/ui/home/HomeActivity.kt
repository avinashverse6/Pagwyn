package io.pagwyn.reader.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.pagwyn.reader.BuildConfig
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.R
import io.pagwyn.reader.core.DocumentResolver
import io.pagwyn.reader.core.Formatters
import io.pagwyn.reader.core.PagwynDocument
import io.pagwyn.reader.databinding.ActivityHomeBinding
import io.pagwyn.reader.ui.metadata.MetadataActivity
import io.pagwyn.reader.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

/**
 * Library / home screen.
 *
 * Kept intentionally shallow: a search field, one prominent "continue reading"
 * card, recent documents, starred documents and (when searching) results. Opening
 * a PDF uses the Storage Access Framework, so no storage permission is requested.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var recentAdapter: DocumentRowAdapter
    private lateinit var starredAdapter: DocumentCardAdapter
    private lateinit var searchAdapter: DocumentRowAdapter

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let { openPdf(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyThemeMode()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()
        setupLists()
        setupSearch()
        setupActions()
        observe()
        if (savedInstanceState == null && intent?.action == ACTION_CONTINUE_READING) {
            binding.root.post { continueReading() }
        }
        binding.versionLabel.text = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
    }

    private fun applyThemeMode() {
        val mode = when (PagwynApplication.settings.theme) {
            io.pagwyn.reader.data.AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            io.pagwyn.reader.data.AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            io.pagwyn.reader.data.AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.homeScroll.updatePadding(top = bars.top, bottom = bars.bottom)
            // Keep content clear of the status bar without a second layout pass.
            binding.homeScroll.clipToPadding = true
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupLists() {
        recentAdapter = DocumentRowAdapter(
            onOpen = ::openPdf,
            onStar = viewModel::toggleStar,
            onLongPress = ::showDocumentMenu,
        )
        starredAdapter = DocumentCardAdapter(onOpen = ::openPdf)
        searchAdapter = DocumentRowAdapter(
            onOpen = ::openPdf,
            onStar = viewModel::toggleStar,
            onLongPress = ::showDocumentMenu,
        )

        binding.recentList.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = recentAdapter
            itemAnimator = null
        }
        binding.starredList.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = starredAdapter
            itemAnimator = null
        }
        binding.searchResultsList.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = searchAdapter
            itemAnimator = null
        }
    }

    private fun setupSearch() {
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                binding.searchClear.isVisible = query.isNotEmpty()
                viewModel.onQueryChanged(query)
            }
        })
        binding.searchClear.setOnClickListener {
            binding.searchField.setText("")
        }
    }

    private fun setupActions() {
        binding.btnBrowse.setOnClickListener { pickDocument() }
        binding.searchFieldContainer.isClickable = false
        binding.btnSettings.setOnClickListener { SettingsSheet().show(supportFragmentManager, "settings") }
        binding.recentSeeAll.setOnClickListener { showHistory() }
        binding.continueCard.continueRoot.setOnClickListener {
            viewModel.state.value.lastRead?.let(::openPdf)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: HomeState) {
        binding.recentList.isVisible = !state.searching && state.recent.isNotEmpty()
        binding.recentEmpty.isVisible = !state.searching && state.recent.isEmpty() && !state.loading
        binding.recentHeader.isVisible = !state.searching
        binding.recentSeeAll.isVisible = state.history.size > state.recent.size

        recentAdapter.submitList(state.recent)

        binding.starredHeader.isVisible = !state.searching && state.starred.isNotEmpty()
        binding.starredList.isVisible = !state.searching && state.starred.isNotEmpty()
        starredAdapter.submitList(state.starred)

        val lastRead = state.lastRead
        binding.continueCard.root.isVisible = !state.searching && lastRead != null && lastRead.pageCount > 0
        if (lastRead != null) bindContinueCard(lastRead)

        // Search mode replaces the library with results.
        val searchVisible = state.searching
        binding.searchResultsHeader.isVisible = searchVisible
        binding.searchResultsList.isVisible = searchVisible && state.results.isNotEmpty()
        binding.searchNoMatch.isVisible = searchVisible && state.results.isEmpty() && !state.loading
        if (searchVisible) {
            searchAdapter.submitList(state.results)
            binding.searchNoMatch.text = getString(R.string.home_no_match, state.query)
        }
    }

    private fun bindContinueCard(document: PagwynDocument) {
        val card = binding.continueCard
        card.continueTitle.text = document.title
        card.continueMeta.text = getString(
            R.string.home_resume_page,
            document.lastPage + 1,
            document.pageCount,
        )
        card.continueProgress.progress = (document.progress.coerceIn(0f, 1f) * 1000).toInt()
        card.continuePercent.text = getString(
            R.string.reader_percent,
            Formatters.percent(document.progress),
        )
    }

    // ------------------------------------------------------------------ document opening

    private fun pickDocument() {
        runCatching {
            openDocument.launch(arrayOf("application/pdf"))
        }.onFailure {
            toast(getString(R.string.error_generic))
        }
    }

    fun openPdf(document: PagwynDocument) = openUri(document.uri)

    fun openPdf(uri: Uri) = openUri(uri)

    private fun openUri(uri: Uri) {
        val resolved = DocumentResolver.resolve(this, uri)
        if (!DocumentResolver.looksLikePdf(this, uri, resolved.displayName, resolved.mimeType)) {
            toast(getString(R.string.error_open_pdf_only))
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onDocumentOpened(uri)
        startActivity(ReaderActivity.intent(this, uri))
    }

    private fun showDocumentMenu(document: PagwynDocument, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_OPEN, 0, R.string.action_open)
        popup.menu.add(0, MENU_INFO, 1, R.string.action_info)
        popup.menu.add(
            0,
            MENU_STAR,
            2,
            if (document.isStarred) R.string.action_unstar else R.string.action_star,
        )
        popup.menu.add(0, MENU_SHARE, 3, R.string.action_share)
        popup.menu.add(0, MENU_REMOVE, 4, R.string.home_remove_recent)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN -> { openPdf(document); true }
                MENU_INFO -> { startActivity(MetadataActivity.intent(this, document.uri)); true }
                MENU_STAR -> { viewModel.toggleStar(document); true }
                MENU_SHARE -> {
                    io.pagwyn.reader.core.Intents.shareDocument(this, document.uri, document.title)
                    true
                }
                MENU_REMOVE -> { confirmRemove(document); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmRemove(document: PagwynDocument) {
        AlertDialog.Builder(this)
            .setTitle(R.string.home_remove_recent)
            .setMessage(document.title)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ -> viewModel.removeDocument(document) }
            .show()
    }

    private fun showHistory() {
        val state = viewModel.state.value
        val items = state.history.map { "${it.title}\u0000${Formatters.relativeTime(it.lastOpenedAt)}" }
        if (items.isEmpty()) return
        val labels = state.history.map {
            "${it.title} — ${if (it.pageCount > 0) "page ${it.lastPage + 1} of ${it.pageCount}" else Formatters.relativeTime(it.lastOpenedAt)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.home_history)
            .setItems(labels) { _, which -> state.history.getOrNull(which)?.let(::openPdf) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { openUri(it) }
            ACTION_CONTINUE_READING -> continueReading()
        }
    }

    /** Launcher shortcut: reopen the most recently read document. */
    private fun continueReading() {
        val last = viewModel.state.value.lastRead
        if (last != null) {
            openPdf(last)
        } else {
            pickDocument()
        }
    }

    /** Reader/library state for instrumented tests. */
    @androidx.annotation.VisibleForTesting
    fun viewModelSnapshot(): HomeState = viewModel.state.value

    /** Drives the search box the way the user's keyboard does. */
    @androidx.annotation.VisibleForTesting
    fun searchForTest(query: String) {
        binding.searchField.setText(query)
    }

    private companion object {
        const val ACTION_CONTINUE_READING = "io.pagwyn.reader.action.CONTINUE_READING"
        const val MENU_OPEN = 1
        const val MENU_INFO = 2
        const val MENU_STAR = 3
        const val MENU_SHARE = 4
        const val MENU_REMOVE = 5
    }
}
