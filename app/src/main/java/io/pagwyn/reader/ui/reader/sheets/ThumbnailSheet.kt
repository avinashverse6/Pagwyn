package io.pagwyn.reader.ui.reader.sheets

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.R
import io.pagwyn.reader.ui.widget.PagwynSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page thumbnail grid.
 *
 * Thumbnails are rendered lazily and sequentially on a background thread at a
 * small width (cheap: ~40 KB each), and only for the pages currently in the grid
 * viewport. A fast scroller never triggers a storm of renders.
 */
class ThumbnailSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_thumbnails
    override val heightFraction: Float = 0.85f

    var pageCount: Int = 0
    var currentPage: Int = 0
    var onPageSelected: ((Int) -> Unit)? = null

    /** Supplied by the reader: renders one page into a small bitmap. */
    var thumbnailProvider: (suspend (page: Int) -> Bitmap?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val thumbnails = HashMap<Int, Bitmap>()
    private var adapter: Adapter? = null

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.reader_thumbnails)
        val list = view.findViewById<RecyclerView>(R.id.thumbnailList)
        val columns = resources.getInteger(R.integer.thumbnail_columns)
        list.layoutManager = GridLayoutManager(requireContext(), columns)
        val a = Adapter()
        adapter = a
        list.adapter = a
        list.scrollToPosition(currentPage)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        thumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
        thumbnails.clear()
        adapter = null
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val frame: View = itemView.findViewById(R.id.thumbFrame)
            val image: ImageView = itemView.findViewById(R.id.thumbImage)
            val progress: View = itemView.findViewById(R.id.thumbProgress)
            val label: TextView = itemView.findViewById(R.id.thumbLabel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_thumbnail, parent, false))

        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.label.text = (position + 1).toString()
            holder.frame.setBackgroundResource(
                if (position == currentPage) R.drawable.bg_thumb_page_current else R.drawable.bg_thumb_page,
            )

            val cached = thumbnails[position]
            if (cached != null && !cached.isRecycled) {
                holder.image.setImageBitmap(cached)
                holder.progress.visibility = View.GONE
            } else {
                holder.image.setImageDrawable(null)
                holder.progress.visibility = View.VISIBLE
                requestThumbnail(position)
            }

            holder.itemView.setOnClickListener {
                onPageSelected?.invoke(position)
                dismissAllowingStateLoss()
            }
        }

        private fun requestThumbnail(page: Int) {
            val provider = thumbnailProvider ?: return
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { provider(page) }.getOrNull() }
                if (bitmap != null && !bitmap.isRecycled) {
                    thumbnails[page] = bitmap
                    notifyItemChanged(page)
                }
            }
        }
    }
}
