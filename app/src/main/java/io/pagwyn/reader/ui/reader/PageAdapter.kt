package io.pagwyn.reader.ui.reader

import android.util.SparseArray
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.pdf.PageInfo

/**
 * RecyclerView adapter for continuous reading.
 *
 * Only pages near the viewport exist as views; the adapter therefore supports
 * documents with thousands of pages without allocating thousands of slots.
 */
class PageAdapter(
    private val host: PageSlotView.Host,
) : RecyclerView.Adapter<PageAdapter.SlotHolder>() {

    private val pageInfos = SparseArray<PageInfo>()
    private var pageCount = 0

    /** Total pages in the document, even when [pageCount] is only partly primed. */
    var totalPages: Int = 0
        private set
    var rotation: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_ROTATION)
            }
        }

    var pageGapPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_GAP)
            }
        }

    /** Pages that currently have a bitmap, used to restore on rebind. */
    private val attachedBitmaps = HashMap<Int, android.graphics.Bitmap>()

    class SlotHolder(val slot: PageSlotView) : RecyclerView.ViewHolder(slot)

    /**
     * Starts a new document.
     *
     * Dimensions are discovered lazily: priming thousands of pages up front would
     * make opening a large file slow, so only the first [INFO_PRIME_LIMIT] pages
     * are measured here and the rest arrive through [setPageInfo] while scrolling.
     */
    fun submit(pageCount: Int, infoProvider: (Int) -> PageInfo?) {
        this.pageCount = pageCount
        totalPages = pageCount
        pageInfos.clear()
        attachedBitmaps.clear()
        val primeLimit = minOf(pageCount, INFO_PRIME_LIMIT)
        for (i in 0 until primeLimit) {
            infoProvider(i)?.let { pageInfos.put(i, it) }
        }
        notifyDataSetChanged()
    }

    /** Appends a batch of pages (used when switching to single-page mode). */
    fun resetTo(pageCount: Int) {
        this.pageCount = pageCount
        totalPages = pageCount
        notifyDataSetChanged()
    }

    fun setPageInfo(info: PageInfo) {
        pageInfos.put(info.index, info)
        val position = info.index
        if (position in 0 until itemCount) notifyItemChanged(position, PAYLOAD_ASPECT)
    }

    fun aspectFor(page: Int): Float = pageInfos.get(page)?.aspectRatio ?: 0.707f

    fun rotationFor(page: Int): Int = rotation + (pageInfos.get(page)?.rotation ?: 0)

    fun rememberBitmap(page: Int, bitmap: android.graphics.Bitmap?) {
        if (bitmap == null) attachedBitmaps.remove(page) else attachedBitmaps[page] = bitmap
    }

    fun knownBitmap(page: Int): android.graphics.Bitmap? = attachedBitmaps[page]

    fun clearBitmaps() = attachedBitmaps.clear()

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotHolder {
        val slot = PageSlotView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            this.host = host
        }
        return SlotHolder(slot)
    }

    override fun onBindViewHolder(holder: SlotHolder, position: Int) {
        val slot = holder.slot
        slot.bind(position, aspectFor(position), rotationFor(position))
        slot.setPageGap(pageGapPx)
        val bitmap = knownBitmap(position) ?: hostBitmap(position)
        if (bitmap != null && !bitmap.isRecycled) {
            slot.setBitmap(bitmap, sharp = false)
        }
        host.onSlotBound(slot)
    }

    override fun onBindViewHolder(holder: SlotHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        for (payload in payloads) {
            when (payload) {
                PAYLOAD_ROTATION -> {
                    val slot = holder.slot
                    val info = pageInfos.get(position)
                    if (info != null) slot.bind(position, info.aspectRatio, rotationFor(position))
                }
                PAYLOAD_GAP -> holder.slot.setPageGap(pageGapPx)
                PAYLOAD_ASPECT -> {
                    val info = pageInfos.get(position) ?: continue
                    holder.slot.bind(position, info.aspectRatio, rotationFor(position))
                    knownBitmap(position)?.let { holder.slot.setBitmap(it, sharp = false) }
                }
            }
        }
    }

    override fun onViewRecycled(holder: SlotHolder) {
        super.onViewRecycled(holder)
        holder.slot.currentBitmap()?.let { rememberBitmap(holder.slot.pageIndex, it) }
        holder.slot.setBitmap(null, sharp = false)
    }

    /** Supplies a cached bitmap when a slot is rebound, avoiding a blank flash. */
    var bitmapProvider: ((Int) -> android.graphics.Bitmap?)? = null

    private fun hostBitmap(page: Int): android.graphics.Bitmap? = bitmapProvider?.invoke(page)

    private companion object {
        const val PAYLOAD_ROTATION = "rotation"
        const val PAYLOAD_GAP = "gap"
        const val PAYLOAD_ASPECT = "aspect"
        const val INFO_PRIME_LIMIT = 400
    }
}
