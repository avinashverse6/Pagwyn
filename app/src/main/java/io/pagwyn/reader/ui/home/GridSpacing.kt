package io.pagwyn.reader.ui.home

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.ui.widget.dp

/**
 * Even gaps for the two-column library grid.
 *
 * Applied as a decoration rather than as item margins so the outer edges line up with
 * the rest of the screen: the first column's left gap and the last column's right gap
 * are omitted, leaving only the space *between* cards.
 */
class GridSpacing(private val gapDp: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val spanCount = (parent.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
            ?.spanCount ?: return
        val gap = view.context.dp(gapDp.toFloat()).toInt()
        val column = position % spanCount
        outRect.left = if (column == 0) 0 else gap / 2
        outRect.right = if (column == spanCount - 1) 0 else gap / 2
        outRect.top = if (position < spanCount) 0 else gap
    }
}
