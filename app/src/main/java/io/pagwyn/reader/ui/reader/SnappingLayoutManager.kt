package io.pagwyn.reader.ui.reader

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Vertical layout manager with two personalities:
 *
 *  - **Continuous mode**: ordinary vertical scrolling with free fling.
 *  - **Single-page mode**: exact page snapping, like a pager, while still being the
 *    same RecyclerView. Sharing one view hierarchy means switching modes never
 *    rebinds the whole document, never loses the zoom of the page you were reading,
 *    and never allocates a second render pipeline.
 */
class SnappingLayoutManager(context: Context) : LinearLayoutManager(context) {

    var snapToPages: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                snapHelper?.attachToRecyclerView(recyclerView)
            } else {
                snapHelper?.attachToRecyclerView(null)
            }
        }

    private val snapHelper: PagerSnapHelper? by lazy { PagerSnapHelper() }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (snapToPages) snapHelper?.attachToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        snapHelper?.attachToRecyclerView(null)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /** Page that is closest to the top of the viewport. */
    fun pageAtTop(): Int {
        val position = findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        val view = findViewByPosition(position) ?: return position
        // If the page is mostly scrolled past, the next page owns the viewport.
        return if (view.bottom < height / 3 && position + 1 < itemCount) position + 1 else position
    }
}
