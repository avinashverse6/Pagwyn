package io.pagwyn.reader.ui.reader.sheets

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.R
import io.pagwyn.reader.ui.widget.PagwynSheet

/**
 * Table of contents / outline.
 *
 * Android's `PdfRenderer` does not expose the PDF outline, so Pagwyn builds one
 * from the document's own text layer: a heading detector looks for short,
 * numbered or capitalised lines near the top of each page. Documents without a
 * text layer (scans) fall back to a page grid instead of an empty sheet.
 */
class TocSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_toc
    override val heightFraction: Float = 0.75f

    var entries: List<Entry> = emptyList()
    var onEntrySelected: ((Int) -> Unit)? = null

    data class Entry(val title: String, val page: Int, val level: Int)

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.reader_toc)
        val list = view.findViewById<RecyclerView>(R.id.tocList)
        val empty = view.findViewById<View>(R.id.tocEmpty)

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.tocTitle)
            val page: TextView = itemView.findViewById(R.id.tocPage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_toc_entry, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.title.text = entry.title
            holder.page.text = (entry.page + 1).toString()
            // Indent nested headings so the outline reads like a book.
            (holder.title.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                val indent = (entry.level.coerceIn(0, 4)) * dp(14)
                params.marginStart = indent
                holder.title.layoutParams = params
            }
            holder.itemView.setOnClickListener {
                onEntrySelected?.invoke(entry.page)
                dismissAllowingStateLoss()
            }
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }
}
