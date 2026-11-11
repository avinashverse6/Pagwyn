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

    fun submit(pages: Int, initialInfo: (Int) -> PageInfo?) {
        pageCount = pages
        aspects.clear()
        boundBitmaps.clear()
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

    fun remember(page: Int, bitmap: Bitmap?) {
        if (bitmap == null) boundBitmaps.remove(page) else boundBitmaps[page] = bitmap
    }

    fun clearBound() = boundBitmaps.clear()

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
            }
        }
    }

    override fun onViewRecycled(holder: SlotHolder) {
        super.onViewRecycled(holder)
        holder.slot.currentBitmap()?.let { remember(holder.slot.pageIndex, it) }
        holder.slot.setBitmap(null, sharp = false)
    }

    private companion object {
        const val PAYLOAD_GEOMETRY = "geometry"
        const val PAYLOAD_GAP = "gap"
        const val PRIME_LIMIT = 300
        const val DEFAULT_ASPECT = 0.707f
    }
}
