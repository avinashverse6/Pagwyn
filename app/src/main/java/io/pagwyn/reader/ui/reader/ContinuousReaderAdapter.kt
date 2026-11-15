package io.pagwyn.reader.ui.reader

import android.graphics.Bitmap
import android.util.SparseArray
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.pdf.PageInfo

/**
 * Adapter for continuous vertical reading.
 *
 * Page heights are derived from the page aspect ratio, so the scrollbar is
 * accurate for the whole document without rendering anything. RecyclerView keeps
 * only the visible window of slots alive, which is why a 3000-page document uses
 * the same memory as a 30-page one.
 */
class ContinuousReaderAdapter(
    private val host: PageSlotView.Host,
    private val spanProvider: () -> PageInfo?,
) : RecyclerView.Adapter<ContinuousReaderAdapter.SlotHolder>() {

    private val aspects = SparseArray<Float>()
    private val boundBitmaps = HashMap<Int, Bitmap>()

    var pageCount: Int = 0
        private set

    var rotation: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_GEOMETRY)
            }
        }

    var pageGapPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_GAP)
            }
        }

    /** Supplied by the reader to repaint a slot from the shared cache. */
    var bitmapLookup: ((Int) -> Bitmap?)? = null

    /**
     * Pages that failed to render, so a rebind restores the error state instead of
     * resetting the slot to "Rendering…" and starting the spinner over.
     */
    private val failedPages = HashSet<Int>()

    fun markFailed(page: Int) {
        if (failedPages.add(page) && page in 0 until itemCount) {
            notifyItemChanged(page, PAYLOAD_FAILED)
        }
    }

    fun clearFailed(page: Int) {
        if (failedPages.remove(page) && page in 0 until itemCount) {
            notifyItemChanged(page, PAYLOAD_FAILED)
        }
    }

    fun clearAllFailures() {
        if (failedPages.isEmpty()) return
        failedPages.clear()
        notifyDataSetChanged()
    }

    fun submit(pages: Int, initialInfo: (Int) -> PageInfo?) {
        pageCount = pages
        aspects.clear()
        boundBitmaps.clear()
        failedPages.clear()
        val prime = minOf(pages, PRIME_LIMIT)
        for (i in 0 until prime) {
            initialInfo(i)?.let { aspects.put(i, it.aspectRatio) }
        }
        notifyDataSetChanged()
    }

    fun setAspect(page: Int, aspect: Float) {
        if (aspect <= 0f) return
        val previous = aspects.get(page)
        aspects.put(page, aspect)
        if (previous == null || kotlin.math.abs(previous - aspect) > 0.001f) {
            if (page in 0 until itemCount) notifyItemChanged(page, PAYLOAD_GEOMETRY)
        }
    }

    fun aspectOf(page: Int): Float =
        aspects.get(page) ?: spanProvider()?.aspectRatio ?: DEFAULT_ASPECT

    fun bitmapOf(page: Int): Bitmap? = boundBitmaps[page]

    /**
     * Pages whose bitmaps are currently drawn by a view holder.
     *
     * The renderer must never recycle these: freeing a bitmap that a view is painting
     * crashes the reader ("Canvas: trying to use a recycled bitmap").
     */
    fun visiblePages(): Set<Int> {
        val pages = HashSet<Int>(boundBitmaps.size)
        for (holder in attachedHolders) {
            pages.add(holder.slot.pageIndex)
        }
        return pages
    }

    private val attachedHolders = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<SlotHolder, Boolean>(),
    ).let { set ->
        java.util.Collections.synchronizedSet(set)
    }

    private fun track(holder: SlotHolder, attached: Boolean) {
        if (attached) attachedHolders.add(holder) else attachedHolders.remove(holder)
    }

    fun remember(page: Int, bitmap: Bitmap?) {
        if (bitmap == null) boundBitmaps.remove(page) else boundBitmaps[page] = bitmap
    }

    fun clearBound() {
        boundBitmaps.clear()
        attachedHolders.clear()
    }

    override fun getItemCount(): Int = pageCount

    inner class SlotHolder(val slot: PageSlotView) : RecyclerView.ViewHolder(slot)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotHolder {
        val slot = PageSlotView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            host = this@ContinuousReaderAdapter.host
        }
        return SlotHolder(slot)
    }

    override fun onBindViewHolder(holder: SlotHolder, position: Int) {
        val slot = holder.slot
        slot.bind(position, aspectOf(position), rotation)
        slot.setPageGap(pageGapPx)
        val existing = boundBitmaps[position] ?: bitmapLookup?.invoke(position)
        if (existing != null && !existing.isRecycled) {
            slot.setBitmap(existing, sharp = false)
        } else if (position in failedPages) {
            slot.showError()
        } else {
            slot.setBitmap(null, sharp = false)
        }
    }

    override fun onBindViewHolder(holder: SlotHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        for (payload in payloads) {
            when (payload) {
                PAYLOAD_GEOMETRY -> {
                    val bitmap = boundBitmaps[position]
                    holder.slot.bind(position, aspectOf(position), rotation)
                    holder.slot.setPageGap(pageGapPx)
                    if (bitmap != null && !bitmap.isRecycled) holder.slot.setBitmap(bitmap, sharp = false)
                }
                PAYLOAD_GAP -> holder.slot.setPageGap(pageGapPx)
                PAYLOAD_FAILED -> {
                    if (position in failedPages) holder.slot.showError() else holder.slot.showLoading()
                }
            }
        }
    }

    override fun onViewRecycled(holder: SlotHolder) {
        super.onViewRecycled(holder)
        holder.slot.currentBitmap()?.let { remember(holder.slot.pageIndex, it) }
        holder.slot.setBitmap(null, sharp = false)
    }

    override fun onViewAttachedToWindow(holder: SlotHolder) = track(holder, true)

    override fun onViewDetachedFromWindow(holder: SlotHolder) = track(holder, false)

    private companion object {
        const val PAYLOAD_GEOMETRY = "geometry"
        const val PAYLOAD_GAP = "gap"
        const val PAYLOAD_FAILED = "failed"
        const val PRIME_LIMIT = 300
        const val DEFAULT_ASPECT = 0.707f
    }
}
