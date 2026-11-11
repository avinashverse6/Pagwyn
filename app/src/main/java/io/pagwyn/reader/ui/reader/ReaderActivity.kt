package io.pagwyn.reader.ui.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.R
import io.pagwyn.reader.core.DocumentResolver
import io.pagwyn.reader.core.Formatters
import io.pagwyn.reader.core.Intents
import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.ReaderTheme
import io.pagwyn.reader.data.Settings
import io.pagwyn.reader.data.ZoomMode
import io.pagwyn.reader.databinding.ActivityReaderBinding
import io.pagwyn.reader.pdf.PageInfo
import io.pagwyn.reader.pdf.PdfDocument
import io.pagwyn.reader.pdf.SearchHit
import io.pagwyn.reader.render.PageBitmapCache
import io.pagwyn.reader.ui.metadata.MetadataActivity
import io.pagwyn.reader.ui.reader.sheets.AppearanceSheet
import io.pagwyn.reader.ui.reader.sheets.BookmarksSheet
import io.pagwyn.reader.ui.reader.sheets.GoToPageSheet
import io.pagwyn.reader.ui.reader.sheets.SearchSheet
import io.pagwyn.reader.ui.reader.sheets.ThumbnailSheet
import io.pagwyn.reader.ui.reader.sheets.TocSheet
import io.pagwyn.reader.ui.widget.PageImageView
import io.pagwyn.reader.ui.widget.colorFromAttr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reading surface.
 *
 * Responsibilities:
 *  - host the continuous list and the single-page viewport,
 *  - translate gestures and toolbar taps into ViewModel intent,
 *  - report the visible page window to the renderer (this is what keeps memory
 *    flat on huge documents),
 *  - keep the chrome out of the way while reading.
 */
class ReaderActivity : androidx.appcompat.app.AppCompatActivity(), PageSlotView.Host {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel
    private lateinit var settings: Settings

    private lateinit var continuousAdapter: ContinuousReaderAdapter
    private var layoutManager: LinearLayoutManager? = null

    private var singleSlot: PageSlotView? = null
    private var renderJob: Job? = null
    private var autoHideJob: Job? = null
    private var badgeJob: Job? = null

    private var currentUri: Uri? = null
    private var pendingPassword: String? = null
    private var restorePage = 0
    private var pendingRestore = false
    private var lastRenderWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = PagwynApplication.settings
        applyThemeMode()
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = androidx.lifecycle.ViewModelProvider(this)[ReaderViewModel::class.java]
        setupWindow()
        setupList()
        setupToolbar()
        setupInsets()
        observeState()

        viewModel.setRendererListener { page, bitmap, sharp -> onPageRendered(page, bitmap, sharp) }

        currentUri = resolveIntentUri(intent)
        if (currentUri == null) {
            finish()
            return
        }
        pendingRestore = savedInstanceState == null
        restorePage = intent.getIntExtra(EXTRA_START_PAGE, -1)
        viewModel.open(currentUri!!, password = null, resume = true)

        if (savedInstanceState == null) {
            binding.topBar.alpha = 0f
            binding.topBar.animate().alpha(1f).setDuration(160).start()
        }
    }

    // ------------------------------------------------------------------ setup

    private fun applyThemeMode() {
        val mode = when (settings.theme) {
            io.pagwyn.reader.data.AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            io.pagwyn.reader.data.AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            io.pagwyn.reader.data.AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        updateKeepScreenOn()
        applyBrightnessOverlay((settings.readingBrightness * 100).toInt())
    }

    private fun updateKeepScreenOn() {
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupInsets() {
        val bottomBase = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = bottomBase + bars.bottom)
            binding.loadingPanel.updatePadding(bottom = bars.bottom)
            binding.pageList.updatePadding(top = bars.top, bottom = bottomBase + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.readerRoot)
    }

    private fun setupList() {
        continuousAdapter = ContinuousReaderAdapter(this) { viewModel.pageInfo(0) }
        continuousAdapter.bitmapLookup = { page ->
            viewModel.cachedBitmapAny(page)
        }
        layoutManager = LinearLayoutManager(this)
        binding.pageList.layoutManager = layoutManager
        binding.pageList.adapter = continuousAdapter
        binding.pageList.itemAnimator = null
        binding.pageList.setHasFixedSize(false)
        binding.pageList.setItemViewCacheSize(4)
        binding.pageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateVisibleWindow()
                if (dy != 0 && settings.hideControls && !viewModel.state.value.fullscreen) {
                    // Scrolling means the user is reading: get the chrome out of the
                    // way. A tap anywhere brings it back.
                    if (viewModel.state.value.controlsVisible) {
                        viewModel.setControlsVisible(false)
                    }
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateVisibleWindow()
                }
            }
        })
        continuousAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                if (pendingRestore) restorePosition()
            }
        })
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnBookmark.setOnClickListener { toggleBookmark() }
        binding.btnMore.setOnClickListener { showMoreMenu(it) }
        binding.btnZoomIn.setOnClickListener { zoomStep(in = true) }
        binding.btnZoomOut.setOnClickListener { zoomStep(in = false) }
        binding.btnFit.setOnClickListener { cycleFitMode() }
        binding.btnReadingTheme.setOnClickListener { showAppearanceSheet() }
        binding.btnRotate.setOnClickListener { viewModel.rotate() }
        binding.btnShare.setOnClickListener { shareDocument() }
        binding.btnBrightness.setOnClickListener { showAppearanceSheet() }
        binding.btnToc.setOnClickListener { openToc() }
        binding.btnThumbnails.setOnClickListener { openThumbnails() }
        binding.btnPrevPage.setOnClickListener { stepPage(-1) }
        binding.btnNextPage.setOnClickListener { stepPage(1) }
        binding.pageIndicator.setOnClickListener { openGoToPage() }
        binding.errorClose.setOnClickListener { finish() }
        binding.errorRetry.setOnClickListener { currentUri?.let { viewModel.open(it, pendingPassword) } }
        binding.transientPageBadge.setOnClickListener { jumpToResume() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> renderState(state) }
            }
        }
    }

    // ------------------------------------------------------------------ state rendering

    private fun renderState(state: ReaderState) {
        binding.loadingPanel.isVisible = state.loading
        binding.errorPanel.isVisible = state.error != null
        binding.topBar.isVisible = !state.fullscreen || state.controlsVisible
        binding.bottomBar.isVisible = !state.fullscreen || state.controlsVisible

        state.error?.let { error ->
            binding.loadingPanel.isVisible = false
            binding.errorTitle.setText(error.titleRes)
            binding.errorBody.setText(error.bodyRes)
            binding.errorRetry.isVisible = error.retryable
            binding.pageList.isVisible = false
            binding.singlePageContainer.isVisible = false
        }

        if (state.error == null && !state.loading) {
            binding.pageList.isVisible = state.mode == ReadingMode.CONTINUOUS
            binding.singlePageContainer.isVisible = state.mode == ReadingMode.SINGLE_PAGE
        }

        binding.documentTitle.text = state.documentTitle
        binding.documentSubtitle.text = state.documentSubtitle
        binding.pageIndicator.text = state.pageLabel
        binding.zoomLabel.text = getString(R.string.reader_zoom_level, state.zoomPercent)

        binding.btnBookmark.setImageResource(
            if (state.isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark,
        )
        binding.btnBookmark.contentDescription = getString(
            if (state.isBookmarked) R.string.reader_remove_bookmark else R.string.reader_add_bookmark,
        )
        binding.btnBookmark.setColorFilter(
            if (state.isBookmarked) colorFromAttr(R.attr.pwPrimary) else colorFromAttr(R.attr.pwOnSurface),
        )

        val fraction = state.progressFraction
        if (settings.showProgressBar) {
            binding.readingProgressBar.isVisible = true
            binding.readingProgressBar.progress = (fraction * 1000).toInt()
        } else {
            binding.readingProgressBar.isVisible = false
        }

        applyReaderTheme(state.theme)
        binding.transientPageBadge.isVisible = state.canJumpBack && state.controlsVisible
        if (state.canJumpBack) {
            binding.transientPageBadge.text = getString(R.string.reader_jump_back, state.jumpBackPage + 1)
        }

        updateChromeVisibility(state)
        syncMode(state.mode)
    }

    private fun updateChromeVisibility(state: ReaderState) {
        // In full-screen the chrome only appears when the user taps.
        if (state.fullscreen) {
            binding.topBar.isVisible = state.controlsVisible
            binding.bottomBar.isVisible = state.controlsVisible
        }
    }

    private fun applyReaderTheme(theme: ReaderTheme) {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val resolved = when (theme) {
            ReaderTheme.FOLLOW_APP -> if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT
            else -> theme
        }
        val background = when (resolved) {
            ReaderTheme.SEPIA -> 0xFFF0E6D2.toInt()
            ReaderTheme.DARK -> 0xFF0E1016.toInt()
            else -> 0xFFE9E5DC.toInt()
        }
        binding.readerRoot.setBackgroundColor(background)
        binding.pageList.setBackgroundColor(background)
        binding.singlePageContainer.setBackgroundColor(background)

        val isDark = resolved == ReaderTheme.DARK
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
        binding.topBar.setBackgroundColor(
            if (resolved == ReaderTheme.SEPIA) 0xFFE7DBC2.toInt()
            else if (isDark) colorFromAttr(R.attr.pwToolbarBackground) else colorFromAttr(R.attr.pwToolbarBackground),
        )
    }

    private fun applyBrightnessOverlay(percent: Int) {
        applyBrightnessFraction(percent / 100f)
    }

    private fun applyBrightnessFraction(value: Float) {
        val fraction = value.coerceIn(0f, 1f)
        // Only dim when the user asked for it: 100% means "no overlay".
        val alpha = if (fraction >= 0.995f) 0 else ((1f - fraction) * 0.72f * 255).toInt()
        binding.brightnessOverlay.isVisible = alpha > 0
        binding.brightnessOverlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
        if (alpha == 0) binding.brightnessOverlay.isVisible = false
    }

    // ------------------------------------------------------------------ mode handling

    private fun syncMode(mode: ReadingMode) {
        val currentlySingle = singleSlot != null
        if (mode == ReadingMode.SINGLE_PAGE && !currentlySingle) {
            setupSinglePage()
        } else if (mode == ReadingMode.CONTINUOUS && currentlySingle) {
            teardownSinglePage()
        }
        if (mode == ReadingMode.SINGLE_PAGE) {
            binding.pageList.isVisible = false
            binding.singlePageContainer.isVisible = true
        }
    }

    private fun setupSinglePage() {
        val state = viewModel.state.value
        if (state.pageCount <= 0) return
        val slot = PageSlotView(this).apply {
            host = this@ReaderActivity
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        binding.singlePageContainer.addView(slot)
        singleSlot = slot
        bindSinglePage(state.currentPage)
    }

    private fun teardownSinglePage() {
        singleSlot?.let { binding.singlePageContainer.removeView(it) }
        singleSlot = null
    }

    private fun bindSinglePage(page: Int) {
        val slot = singleSlot ?: return
        val info = viewModel.pageInfo(page)
        slot.bind(page, info?.aspectRatio ?: 0.707f, viewModel.state.value.rotation)
        slot.setPageGap(0)
        val cached = viewModel.cachedBitmapAny(page)
        if (cached != null) slot.setBitmap(cached, sharp = false)
        requestSinglePageRender(page)
    }

    private fun requestSinglePageRender(page: Int) {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val slot = singleSlot ?: return@launch
            val info = withContext(Dispatchers.IO) { viewModel.pageInfoAsync(page) }
            if (info == null) return@launch
            slot.bind(page, info.aspectRatio, viewModel.state.value.rotation)
            val width = slot.renderedWidthPx().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            viewModel.requestRender(listOf(page), width)
        }
    }

    // ------------------------------------------------------------------ viewport

    /** Reports visible pages + pages just outside the viewport to the renderer. */
    private fun updateVisibleWindow() {
        val state = viewModel.state.value
        if (state.pageCount <= 0) return
        if (state.mode == ReadingMode.SINGLE_PAGE) return

        val manager = layoutManager ?: return
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return

        val focus = when {
            first == last -> first
            else -> {
                // Prefer the page that covers most of the viewport.
                var best = first
                var bestVisible = 0
                for (i in first..last) {
                    val view = manager.findViewByPosition(i) ?: continue
                    val visible = minOf(view.bottom, binding.pageList.height) - maxOf(view.top, 0)
                    if (visible > bestVisible) {
                        bestVisible = visible
                        best = i
                    }
                }
                best
            }
        }

        viewModel.onPageVisible(focus)

        val width = currentRenderWidthPx()
        if (width <= 0) return
        lastRenderWidth = width

        // Render order: current page first, then neighbours outward, then the
        // rest of the visible window. This makes page turns feel instant.
        val order = LinkedHashSet<Int>()
        order.add(focus)
        var delta = 1
        while (order.size < RENDER_WINDOW) {
            var added = false
            if (focus + delta < state.pageCount) {
                order.add(focus + delta)
                added = true
            }
            if (focus - delta >= 0) {
                order.add(focus - delta)
                added = true
            }
            delta++
            if (!added) break
        }

        viewModel.requestRender(order.toList(), width)
        // Keep only nearby pages in the bitmap cache.
        viewModel.trimCache(focus - CACHE_WINDOW, focus + CACHE_WINDOW)

        // Discover dimensions for pages we have not measured yet, so scrolling
        // stays smooth instead of triggering a layout pass per page.
        prefetchGeometry(focus, state.pageCount)
    }

    private fun prefetchGeometry(focus: Int, pageCount: Int) {
        lifecycleScope.launch {
            for (offset in 1..GEOMETRY_AHEAD) {
                for (page in intArrayOf(focus + offset, focus - offset)) {
                    if (page < 0 || page >= pageCount) continue
                    val info = viewModel.pageInfoAsync(page) ?: continue
                    continuousAdapter.setAspect(page, info.aspectRatio)
                }
            }
        }
    }

    private fun currentRenderWidthPx(): Int {
        val state = viewModel.state.value
        if (state.mode == ReadingMode.SINGLE_PAGE) {
            val slot = singleSlot ?: return resources.displayMetrics.widthPixels
            val width = slot.renderedWidthPx()
            if (width > 0) return width
        }
        // Continuous: the page is as wide as the list minus page margins.
        val listWidth = binding.pageList.width - binding.pageList.paddingStart - binding.pageList.paddingEnd
        if (listWidth > 0) return (listWidth * 1f).toInt()
        return resources.displayMetrics.widthPixels
    }

    // ------------------------------------------------------------------ render callbacks

    private fun onPageRendered(page: Int, bitmap: Bitmap, sharp: Boolean) {
        if (isFinishing || isDestroyed) return
        val state = viewModel.state.value
        if (state.mode == ReadingMode.SINGLE_PAGE) {
            if (page == state.currentPage) singleSlot?.setBitmap(bitmap, sharp)
            return
        }
        if (binding.pageList.isComputingLayout) return
        val manager = layoutManager ?: return
        val index = if (continuousAdapter.pageCount > page) page else -1
        if (index < 0) return
        val view = manager.findViewByPosition(index) as? PageSlotView
        if (view != null) {
            view.setBitmap(bitmap, sharp)
            continuousAdapter.remember(index, bitmap)
        } else {
            continuousAdapter.remember(index, bitmap)
        }
    }

    // ------------------------------------------------------------------ gestures (Host)

    override fun onSlotTap() {
        val next = !viewModel.state.value.controlsVisible
        viewModel.setControlsVisible(next)
        if (next) scheduleAutoHide()
    }

    override fun onSlotZoomChanged(slot: PageSlotView, scale: Float) {
        val percent = (scale * 100).toInt()
        viewModel.onZoomChanged(percent)
        // Re-render at the new resolution so zoomed text stays crisp.
        val width = slot.renderedWidthPx()
        if (width > 0) {
            viewModel.requestRender(listOf(slot.pageIndex), width)
        }
    }

    override fun onSlotScroll(dy: Float): Boolean {
        binding.pageList.scrollBy(0, dy.toInt())
        return true
    }

    override fun onSlotLongPress(page: Int, x: Float, y: Float) {
        copyPageText(page)
    }

    override fun onSlotRendered(page: Int, sharp: Boolean) = Unit

    override fun onSlotBound(slot: PageSlotView) = Unit

    // ------------------------------------------------------------------ navigation

    private fun restorePosition() {
        if (!pendingRestore) return
        pendingRestore = false
        val target = if (restorePage >= 0) restorePage else viewModel.state.value.currentPage
        jumpToPage(target, smooth = false, select = false)
    }

    private fun stepPage(delta: Int) {
        val state = viewModel.state.value
        if (state.pageCount <= 0) return
        val target = (state.currentPage + delta).coerceIn(0, state.pageCount - 1)
        if (target == state.currentPage && state.mode == ReadingMode.SINGLE_PAGE) return
        jumpToPage(target, smooth = false)
    }

    fun jumpToPage(page: Int, smooth: Boolean = false, select: Boolean = true) {
        val state = viewModel.state.value
        if (state.pageCount <= 0) return
        val target = page.coerceIn(0, state.pageCount - 1)
        if (state.mode == ReadingMode.SINGLE_PAGE) {
            viewModel.onPageVisible(target)
            bindSinglePage(target)
            if (select) showPageBadge(target)
            return
        }
        if (smooth) {
            binding.pageList.smoothScrollToPosition(target)
        } else {
            binding.pageList.scrollToPosition(target)
        }
        viewModel.onPageVisible(target)
        if (select) showPageBadge(target)
    }

    private fun jumpToResume() {
        jumpToPage(viewModel.state.value.jumpBackPage + 1)
    }

    private fun showPageBadge(page: Int) {
        if (viewModel.state.value.controlsVisible) return
        binding.transientPageBadge.text = "${page + 1} / ${viewModel.state.value.pageCount}"
        binding.transientPageBadge.isVisible = true
        badgeJob?.cancel()
        badgeJob = lifecycleScope.launch {
            delay(900)
            binding.transientPageBadge.isVisible = false
        }
    }

    // ------------------------------------------------------------------ toolbar actions

    private fun zoomStep(increasing: Boolean) {
        val state = viewModel.state.value
        if (state.mode == ReadingMode.SINGLE_PAGE) {
            val slot = singleSlot ?: return
            if (increasing) slot.zoomIn() else slot.zoomOut()
            return
        }
        val manager = layoutManager ?: return
        val position = state.currentPage
        val view = manager.findViewByPosition(position) as? PageSlotView
        if (view != null) {
            if (increasing) view.zoomIn() else view.zoomOut()
        } else {
            // No live view for the page: adjust the stored zoom mode instead.
            val next = if (increasing) {
                minOf(400, state.zoomPercent + 25)
            } else {
                maxOf(50, state.zoomPercent - 25)
            }
            viewModel.setZoomPercent(next)
        }
    }

    private fun cycleFitMode() {
        val state = viewModel.state.value
        val next = when (state.zoomMode) {
            ZoomMode.FIT_WIDTH -> ZoomMode.FIT_PAGE
            ZoomMode.FIT_PAGE -> ZoomMode.ACTUAL_SIZE
            ZoomMode.ACTUAL_SIZE -> ZoomMode.FIT_WIDTH
        }
        viewModel.setZoomMode(next)
        // Fit width restores 100% (page fills width). Fit page and actual size
        // adjust the page's own zoom so the whole page is visible.
        when (next) {
            ZoomMode.FIT_WIDTH -> applyZoomToCurrent(1f)
            ZoomMode.FIT_PAGE -> applyZoomToCurrent(1f)
            ZoomMode.ACTUAL_SIZE -> applyZoomToCurrent(1.6f)
        }
        updateFitIcon(next)
        toast(
            when (next) {
                ZoomMode.FIT_WIDTH -> getString(R.string.reader_zoom_fit_width)
                ZoomMode.FIT_PAGE -> getString(R.string.reader_zoom_fit_page)
                ZoomMode.ACTUAL_SIZE -> getString(R.string.reader_zoom_actual)
            },
        )
    }

    private fun applyZoomToCurrent(scale: Float) {
        val state = viewModel.state.value
        if (state.mode == ReadingMode.SINGLE_PAGE) {
            singleSlot?.setZoom(scale)
            return
        }
        val manager = layoutManager ?: return
        val view = manager.findViewByPosition(state.currentPage) as? PageSlotView
        view?.setZoom(scale)
        viewModel.setZoomPercent((scale * 100).toInt())
    }

    private fun updateFitIcon(mode: ZoomMode) {
        binding.btnFit.setImageResource(
            when (mode) {
                ZoomMode.FIT_WIDTH -> R.drawable.ic_fit_width
                ZoomMode.FIT_PAGE -> R.drawable.ic_fit_page
                ZoomMode.ACTUAL_SIZE -> R.drawable.ic_scroll
            },
        )
    }

    private fun toggleBookmark() {
        val added = viewModel.toggleBookmark()
        toast(getString(if (added) R.string.reader_bookmark_added else R.string.reader_bookmark_removed))
    }

    private fun showMoreMenu(anchor: View) {
        val state = viewModel.state.value
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_reader_more, popup.menu)
        popup.menu.findItem(R.id.action_keep_screen_on).isChecked = settings.keepScreenOn
        popup.menu.findItem(R.id.action_bookmarks).isEnabled = state.pageCount > 0
        popup.menu.findItem(R.id.action_goto_page).isEnabled = state.pageCount > 0
        popup.menu.findItem(R.id.action_copy_text).isEnabled = state.pageCount > 0
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_bookmarks -> { openBookmarks(); true }
                R.id.action_goto_page -> { openGoToPage(); true }
                R.id.action_appearance -> { showAppearanceSheet(); true }
                R.id.action_fullscreen -> { toggleFullscreen(); true }
                R.id.action_keep_screen_on -> {
                    settings.keepScreenOn = !settings.keepScreenOn
                    item.isChecked = settings.keepScreenOn
                    updateKeepScreenOn()
                    true
                }
                R.id.action_copy_text -> { copyPageText(viewModel.state.value.currentPage); true }
                R.id.action_share -> { shareDocument(); true }
                R.id.action_info -> { openMetadata(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleFullscreen() {
        val state = viewModel.state.value
        val next = !state.fullscreen
        viewModel.setFullscreen(next)
        if (next) {
            viewModel.setControlsVisible(false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            viewModel.setControlsVisible(true)
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        toast(getString(if (next) R.string.reader_fullscreen_on else R.string.reader_fullscreen_off))
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        if (!settings.hideControls) return
        autoHideJob = lifecycleScope.launch {
            delay(3500)
            if (!viewModel.state.value.fullscreen) {
                viewModel.setControlsVisible(false)
            }
        }
    }

    // ------------------------------------------------------------------ sheets

    private fun openSearch() {
        val sheet = SearchSheet().apply {
            onQueryChanged = { query -> viewModel.search(query) }
            onResultSelected = { page -> jumpToPage(page) }
        }
        viewModel.startSearch()
        sheet.show(supportFragmentManager, "search")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    sheet.submit(
                        hits = state.searchResults,
                        isRunning = state.searchRunning,
                        queryPresent = state.searchQuery.isNotBlank(),
                        textLayer = state.textLayerAvailable,
                    )
                }
            }
        }
    }

    private fun openToc() {
        lifecycleScope.launch {
            val entries = buildOutline()
            if (isFinishing || isDestroyed) return@launch
            val sheet = TocSheet().apply {
                this.entries = entries
                onEntrySelected = { page -> jumpToPage(page) }
            }
            sheet.show(supportFragmentManager, "toc")
        }
    }

    /**
     * Derives a usable outline from the text layer.
     *
     * Android's renderer exposes no `/Outlines`; heading detection is a reliable
     * fallback for text PDFs and yields an empty list for scans.
     */
    private suspend fun buildOutline(): List<TocSheet.Entry> {
        val doc = viewModel.document ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val result = ArrayList<TocSheet.Entry>()
            val limit = minOf(doc.pageCount, OUTLINE_SCAN_PAGES)
            for (page in 0 until limit) {
                if (result.size >= OUTLINE_MAX) break
                val text = runCatching { doc.pageText(page) }.getOrNull() ?: continue
                val line = text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.length in 3..90 && looksLikeHeading(it) }
                    ?: continue
                result.add(TocSheet.Entry(line, page, levelFor(line)))
            }
            result
        }
    }

    private fun looksLikeHeading(line: String): Boolean {
        if (line.length < 3) return false
        if (line.any { it.isDigit() } && line.length < 12) return false
        val capitalised = line.first().isUpperCase()
        val numbered = Regex("^(chapter|part|section|appendix|\\d+[.)])\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(line)
        return capitalised && (numbered || line.length in 4..60)
    }

    private fun levelFor(line: String): Int = when {
        Regex("^(part|appendix)", RegexOption.IGNORE_CASE).containsMatchIn(line) -> 0
        Regex("^(chapter|section)", RegexOption.IGNORE_CASE).containsMatchIn(line) -> 1
        line.firstOrNull()?.isDigit() == true -> 2
        else -> 2
    }

    private fun openBookmarks() {
        val sheet = BookmarksSheet()
        sheet.pages = viewModel.bookmarks()
        sheet.onPageSelected = { page -> jumpToPage(page) }
        sheet.onRemove = { page ->
            viewModel.removeBookmark(page)
        }
        sheet.show(supportFragmentManager, "bookmarks")
    }

    private fun openThumbnails() {
        val state = viewModel.state.value
        val sheet = ThumbnailSheet()
        sheet.pageCount = state.pageCount
        sheet.currentPage = state.currentPage
        sheet.onPageSelected = { page -> jumpToPage(page) }
        sheet.thumbnailProvider = { page -> renderThumbnail(page) }
        sheet.show(supportFragmentManager, "thumbnails")
    }

    private suspend fun renderThumbnail(page: Int): Bitmap? = withContext(Dispatchers.IO) {
        viewModel.renderThumbnail(page)
    }

    private fun openGoToPage() {
        val state = viewModel.state.value
        if (state.pageCount <= 0) return
        val sheet = GoToPageSheet().apply {
            pageCount = state.pageCount
            currentPage = state.currentPage
            onPageSelected = { page -> jumpToPage(page) }
        }
        sheet.show(supportFragmentManager, "goto")
    }

    private fun showAppearanceSheet() {
        val state = viewModel.state.value
        val sheet = AppearanceSheet().apply {
            theme = state.theme
            mode = state.mode
            spacingDp = settings.pageSpacingDp
            brightness = (settings.readingBrightness * 100).toInt().takeIf { it > 0 } ?: 100
            onThemeChanged = { viewModel.setTheme(it) }
            onModeChanged = { viewModel.setMode(it) }
            onSpacingChanged = { dp ->
                settings.pageSpacingDp = dp
                applyPageSpacing(dp)
            }
            onBrightnessChanged = { percent ->
                settings.readingBrightness = percent / 100f
                applyBrightnessOverlay(percent)
            }
        }
        sheet.show(supportFragmentManager, "appearance")
    }

    private fun applyPageSpacing(dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        continuousAdapter.pageGapPx = px
    }

    // ------------------------------------------------------------------ text & sharing

    private fun copyPageText(page: Int) {
        lifecycleScope.launch {
            val text = viewModel.pageText(page)
            if (text.isNullOrBlank()) {
                toast(getString(R.string.reader_text_selection_unavailable))
                return@launch
            }
            val clipboard = getSystemService<ClipboardManager>()
            clipboard?.setPrimaryClip(ClipData.newPlainText(viewModel.state.value.documentTitle, text))
            toast(getString(R.string.reader_text_copied))
        }
    }

    private fun shareDocument() {
        val uri = currentUri ?: return
        Intents.shareDocument(this, uri, viewModel.state.value.documentTitle)
    }

    private fun openMetadata() {
        val uri = currentUri ?: return
        startActivity(MetadataActivity.intent(this, uri))
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        applyThemeMode()
        if (settings.hideControls && viewModel.state.value.controlsVisible) scheduleAutoHide()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onLeaving()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URI, currentUri?.toString())
    }

    override fun onDestroy() {
        autoHideJob?.cancel()
        badgeJob?.cancel()
        renderJob?.cancel()
        continuousAdapter.clearBound()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = resolveIntentUri(intent) ?: return
        if (uri != currentUri) {
            currentUri = uri
            viewModel.open(uri, pendingPassword)
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    private fun resolveIntentUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val data = intent.data
        if (data != null) {
            persistPermission(data)
            return data
        }
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            val uri = clip.getItemAt(0).uri ?: return null
            persistPermission(uri)
            return uri
        }
        @Suppress("DEPRECATION")
        val extra = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (extra != null) {
            persistPermission(extra)
            return extra
        }
        return null
    }

    private fun persistPermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.pageList.post { updateVisibleWindow() }
    }

    companion object {
        private const val EXTRA_START_PAGE = "io.pagwyn.reader.extra.START_PAGE"
        private const val STATE_URI = "state_uri"

        /** Pages kept in memory around the current page. */
        private const val CACHE_WINDOW = 3

        /** Pages requested for rendering per viewport update. */
        private const val RENDER_WINDOW = 5

        private const val GEOMETRY_AHEAD = 4
        private const val OUTLINE_SCAN_PAGES = 600
        private const val OUTLINE_MAX = 120

        fun intent(context: android.content.Context, uri: Uri, startPage: Int = -1): Intent =
            Intent(context, ReaderActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (startPage >= 0) putExtra(EXTRA_START_PAGE, startPage)
            }
    }
}
