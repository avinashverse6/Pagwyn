package io.pagwyn.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.pagwyn.reader.R
import io.pagwyn.reader.ui.widget.PageImageView
import io.pagwyn.reader.ui.widget.ZoomableLayout
import io.pagwyn.reader.ui.widget.colorFromAttr
import io.pagwyn.reader.ui.widget.dp

/**
 * One page in the continuous list: the page bitmap plus its own zoom/pan owner.
 *
 * Each slot hosts an independent [ZoomableLayout] so zooming is per page — the
 * standard behaviour in every serious PDF reader — while vertical scrolling stays
 * with the [RecyclerView].
 *
 * A slot is always in exactly one of three states: *loading* (a quiet placeholder),
 * *rendered* (the bitmap) or *failed* (a message plus a Retry button). The failed
 * state exists so a page that cannot be drawn never looks like one that is still
 * loading — that ambiguity is what turned a renderer bug into an infinite spinner.
 */
class PageSlotView(context: Context) : FrameLayout(context) {

    interface Host {
        fun onSlotTap()
        fun onSlotZoomChanged(slot: PageSlotView, scale: Float)
        fun onSlotScroll(dy: Float): Boolean
        fun onSlotLongPress(page: Int, x: Float, y: Float)
        fun onSlotRendered(page: Int, sharp: Boolean)

        /** Called after a slot is bound so the host can attach a cached bitmap. */
        fun onSlotBound(slot: PageSlotView)

        /** The user tapped Retry on a page that could not be rendered. */
        fun onSlotRetry(page: Int)
    }

    var host: Host? = null

    var pageIndex: Int = -1
        private set

    private val pageView = PageImageView(context)
    val zoomLayout = ZoomableLayout(context)

    /** Shown while the page has no bitmap yet. */
    private val placeholder: TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setText(R.string.reader_page_rendering)
        setTextColor(context.colorFromAttr(R.attr.pwOnSurfaceMuted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        visibility = View.GONE
    }

    private val errorText: TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(context.colorFromAttr(R.attr.pwOnSurfaceMuted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(24f).toInt(), 0, dp(24f).toInt(), 0)
    }

    private val retryButton: TextView = TextView(context).apply {
        setText(R.string.action_retry)
        setTextColor(context.colorFromAttr(R.attr.pwOnPrimary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        // Pill button with a 48dp touch target, per accessibility guidance.
        minHeight = dp(48f).toInt()
        setPadding(dp(24f).toInt(), dp(12f).toInt(), dp(24f).toInt(), dp(12f).toInt())
        background = io.pagwyn.reader.ui.widget.pillBackground(
            context,
            context.colorFromAttr(R.attr.pwPrimary),
        )
        contentDescription = context.getString(R.string.cd_retry_page)
        setOnClickListener { host?.onSlotRetry(pageIndex) }
    }

    private val errorPanel: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        addView(
            errorText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            retryButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(16f).toInt()
                it.gravity = Gravity.CENTER_HORIZONTAL
            },
        )
    }

    init {
        zoomLayout.addView(
            pageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(zoomLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(placeholder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER
        })
        addView(errorPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).also {
            it.gravity = Gravity.CENTER
        })
        zoomLayout.listener = object : ZoomableLayout.Listener {
            override fun onTap() {
                host?.onSlotTap()
            }

            override fun onDoubleTap() = Unit

            override fun onLongPress(x: Float, y: Float) {
                host?.onSlotLongPress(pageIndex, x, y)
            }

            override fun onZoomChanged(scale: Float) {
                host?.onSlotZoomChanged(this@PageSlotView, scale)
            }

            override fun onScrollBy(dy: Float): Boolean = host?.onSlotScroll(dy) ?: false
        }
    }

    fun bind(index: Int, aspect: Float, rotation: Int) {
        pageIndex = index
        pageView.pageAspect = aspect
        pageView.rotation = rotation
        placeholder.setText(R.string.reader_page_rendering)
        showLoading()
        resetZoomSilently()
    }

    /** Quiet placeholder; the page has not been drawn yet. */
    fun showLoading() {
        errorPanel.visibility = View.GONE
        placeholder.visibility = if (pageView.hasContent) View.GONE else View.VISIBLE
    }

    /**
     * The page could not be rendered. Deliberately distinct from loading: it states
     * the failure and offers a retry, so the reader can never look stuck.
     */
    fun showError() {
        placeholder.visibility = View.GONE
        errorText.text = context.getString(R.string.reader_page_failed, pageIndex + 1)
        errorPanel.visibility = View.VISIBLE
        pageView.setPageBitmap(null)
    }

    fun isShowingError(): Boolean = errorPanel.visibility == View.VISIBLE

    fun setBitmap(bitmap: Bitmap?, sharp: Boolean) {
        pageView.setPageBitmap(bitmap)
        if (bitmap != null) {
            errorPanel.visibility = View.GONE
            placeholder.visibility = View.GONE
        } else if (!isShowingError()) {
            placeholder.visibility = View.VISIBLE
        }
        if (bitmap != null && sharp) host?.onSlotRendered(pageIndex, true)
    }

    fun currentBitmap(): Bitmap? = pageView.currentBitmap()

    fun hasContent(): Boolean = pageView.hasContent

    fun setPageGap(gapPx: Int) {
        pageView.pageGap = gapPx
    }

    /** Width the page is currently drawn at, used to choose render resolution. */
    fun renderedWidthPx(): Int = zoomLayout.renderedPageWidthPx()

    fun scale(): Float = zoomLayout.currentScale

    fun resetZoomSilently() {
        zoomLayout.resetZoom(notify = false)
    }

    fun zoomIn() = zoomLayout.zoomIn()

    fun zoomOut() = zoomLayout.zoomOut()

    fun setZoom(scale: Float) = zoomLayout.setZoom(scale)
}
