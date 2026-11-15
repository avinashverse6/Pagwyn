package io.pagwyn.reader.ui.reader

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Vertical layout manager shared by both reading modes.
 *
 * Single-page mode is implemented with a [PagerSnapHelper] attached to the very
 * same RecyclerView: switching modes never rebinds the document, never loses the
 * zoom of the page being read, and needs no second render pipeline.
 */
class SnappingLayoutManager(context: Context) : LinearLayoutManager(context) {

    private val pagerSnap = PagerSnapHelper()

    /** Enables page-by-page snapping (single-page mode). */
    fun setSnapToPages(recyclerView: RecyclerView, enabled: Boolean) {
        pagerSnap.attachToRecyclerView(if (enabled) recyclerView else null)
    }

    /** Index of the page that currently owns the top of the viewport. */
    fun pageAtTop(): Int {
        val position = findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        val view = findViewByPosition(position) ?: return position
        // If the page is mostly scrolled past, the next page owns the viewport.
        return if (view.bottom < height / 3 && position + 1 < itemCount) position + 1 else position
    }
}
