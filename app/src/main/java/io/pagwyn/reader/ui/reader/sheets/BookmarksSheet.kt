package io.pagwyn.reader.ui.reader.sheets

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.R
import io.pagwyn.reader.ui.widget.PagwynSheet

/** Page bookmarks for the open document, with jump-to and remove. */
class BookmarksSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_bookmarks
    override val heightFraction: Float = 0.6f

    var pages: List<Int> = emptyList()
    var onPageSelected: ((Int) -> Unit)? = null
    var onRemove: ((Int) -> Unit)? = null

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.reader_bookmarks)
        val list = view.findViewById<RecyclerView>(R.id.bookmarkList)
        val empty = view.findViewById<View>(R.id.bookmarkEmpty)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
        empty.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.rowIcon)
            val title: TextView = itemView.findViewById(R.id.rowTitle)
            val remove: ImageButton = itemView.findViewById(R.id.rowAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_simple_row, parent, false))

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            holder.icon.setImageResource(R.drawable.ic_bookmark_filled)
            val label = getString(R.string.reader_bookmark_page, page + 1)
            holder.title.text = label
            holder.remove.visibility = View.VISIBLE
            holder.remove.setImageResource(R.drawable.ic_close)
            holder.remove.contentDescription = getString(R.string.reader_remove_bookmark)
            holder.itemView.setOnClickListener {
                onPageSelected?.invoke(page)
                dismissAllowingStateLoss()
            }
            holder.remove.setOnClickListener {
                onRemove?.invoke(page)
                dismissAllowingStateLoss()
            }
        }
    }
}
