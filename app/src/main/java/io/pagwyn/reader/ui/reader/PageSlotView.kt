package io.pagwyn.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.pagwyn.reader.R
import io.pagwyn.reader.ui.widget.PageImageView
import io.pagwyn.reader.ui.widget.ZoomableLayout

/**
 * One page in the continuous list: the page bitmap plus its own zoom/pan owner.
 *
 * Each slot hosts an independent [ZoomableLayout] so zooming is per page — the
 * standard behaviour in every serious PDF reader — while vertical scrolling stays
 * with the [RecyclerView].
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
    }

    var host: Host? = null

    var pageIndex: Int = -1
        private set

    private val pageView = PageImageView(context)
    val zoomLayout = ZoomableLayout(context)
    private val placeholder: TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFF9AA0AE.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        visibility = View.GONE
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
        placeholder.text = context.getString(R.string.reader_page_rendering)
        placeholder.visibility = View.GONE
        resetZoomSilently()
    }

    fun setBitmap(bitmap: Bitmap?, sharp: Boolean) {
        pageView.setPageBitmap(bitmap)
        placeholder.visibility = if (bitmap == null) View.VISIBLE else View.GONE
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
