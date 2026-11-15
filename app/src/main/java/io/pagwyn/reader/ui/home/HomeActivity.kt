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
import io.pagwyn.reader.ui.widget.CoverArtwork
import io.pagwyn.reader.ui.widget.ThemeApplier
import io.pagwyn.reader.ui.widget.colorFromAttr
import io.pagwyn.reader.ui.widget.dp
import io.pagwyn.reader.ui.widget.pillBackground
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
    private lateinit var libraryAdapter: DocumentCardAdapter
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
        binding.brandGreeting.text = getString(greetingRes())
    }

    /**
     * Re-applies the stored theme before the window is created.
     *
     * [io.pagwyn.reader.ui.widget.ThemeApplier] is idempotent, so calling it here after
     * the Application already did means a theme chosen while the app is running is in
     * effect for the very next activity, without a relaunch.
     */
    private fun applyThemeMode() {
        ThemeApplier.applyStored(PagwynApplication.settings)
    }

    /** A warmer greeting than a static title, without pretending to know the name. */
    private fun greetingRes(): Int = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..16 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
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
        libraryAdapter = DocumentCardAdapter(
            onOpen = ::openPdf,
            onStar = viewModel::toggleStar,
            onLongPress = ::showDocumentMenu,
        )
        searchAdapter = DocumentRowAdapter(
            onOpen = ::openPdf,
            onStar = viewModel::toggleStar,
        )

        // Two columns, 12dp apart: cards stay wide enough for two lines of title on a
        // 360dp screen while still reading as a grid rather than a list.
        binding.libraryGrid.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(
                this@HomeActivity,
                GRID_COLUMNS,
            )
            adapter = libraryAdapter
            itemAnimator = null
            addItemDecoration(GridSpacing(GRID_GAP_DP))
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
        binding.btnEmptyAdd.setOnClickListener { pickDocument() }
        binding.chipAll.setOnClickListener { viewModel.setFilter(LibraryFilter.ALL) }
        binding.chipStarred.setOnClickListener { viewModel.setFilter(LibraryFilter.STARRED) }
        binding.chipRecent.setOnClickListener { viewModel.setFilter(LibraryFilter.RECENT) }
        binding.searchFieldContainer.isClickable = false
        binding.btnSettings.setOnClickListener {
            SettingsSheet().apply {
                // Recreate only when the theme actually changed: recreating on every
                // settings change would throw away the sheet's scroll position.
                onThemeChanged = { applyThemeToRunningScreen() }
            }.show(supportFragmentManager, "settings")
        }
        binding.continueCard.setOnClickListener {
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
        val searchVisible = state.searching

        // Search replaces the library entirely while a query is active.
        binding.searchResultsList.isVisible = searchVisible
        binding.searchNoMatch.isVisible = searchVisible && state.results.isEmpty() && !state.loading
        if (searchVisible) {
            searchAdapter.submitList(state.results)
            binding.searchNoMatch.text = getString(R.string.home_no_match, state.query)
        } else {
            searchAdapter.submitList(emptyList())
        }

        binding.libraryHeader.isVisible = !searchVisible
        binding.filterScroll.isVisible = !searchVisible && !state.isEmpty
        binding.libraryGrid.isVisible = !searchVisible && !state.isEmpty
        binding.emptyState.isVisible = !searchVisible && state.isEmpty && !state.loading

        if (!searchVisible) {
            libraryAdapter.submitList(state.visible)
            renderFilters(state)
            binding.libraryTitle.text = when (state.filter) {
                LibraryFilter.ALL -> getString(R.string.home_your_library)
                LibraryFilter.STARRED -> getString(R.string.filter_starred)
                LibraryFilter.RECENT -> getString(R.string.filter_recent)
            }
        }

        val lastRead = state.lastRead
        binding.continueCard.isVisible = !searchVisible && lastRead != null && lastRead.pageCount > 0
        if (lastRead != null) bindContinueCard(lastRead)
    }

    /** Selected chip: accent fill and accent-tinted text; the rest stay neutral. */
    private fun renderFilters(state: HomeState) {
        val chips = mapOf(
            binding.chipAll to LibraryFilter.ALL,
            binding.chipStarred to LibraryFilter.STARRED,
            binding.chipRecent to LibraryFilter.RECENT,
        )
        for ((chip, filter) in chips) {
            val selected = state.filter == filter
            chip.isSelected = selected
            chip.background = if (selected) {
                pillBackground(this, colorFromAttr(R.attr.pwPrimary))
            } else {
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_chip)
            }
            chip.setTextColor(
                colorFromAttr(if (selected) R.attr.pwOnPrimary else R.attr.pwOnSurfaceMuted),
            )
        }
    }

    private fun bindContinueCard(document: PagwynDocument) {
        binding.continueTitle.text = document.title
        binding.continueMeta.text = getString(
            R.string.home_resume_page,
            document.lastPage + 1,
            document.pageCount,
        )
        binding.continueProgress.progress = (document.progress.coerceIn(0f, 1f) * 1000).toInt()

        // The card carries the same gradient as its cover, so the two read as one
        // surface (Spotify's album-card pattern) instead of a picture on a box.
        val seed = document.displayName.ifBlank { document.title }
        val dark = ThemeApplier.isDark(this)
        val (start, end) = CoverArtwork.colorsFor(seed, dark)
        binding.continueCard.background =
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(start, end),
            ).apply { cornerRadius = dp(24f) }
        binding.continueCover.setImageBitmap(CoverArtwork.create(192, seed, dark))
        binding.continueCover.contentDescription = getString(
            R.string.cd_document_cover,
            CoverArtwork.initialsFor(document.title),
        )
    }

    /** Re-resolves every inflated view after the user picks a new theme. */
    private fun applyThemeToRunningScreen() {
        runCatching { recreate() }
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

        /** Two columns keeps titles readable on a 360dp screen. */
        const val GRID_COLUMNS = 2
        const val GRID_GAP_DP = 12
    }
}
