package io.pagwyn.reader.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.R
import io.pagwyn.reader.core.Formatters
import io.pagwyn.reader.core.PagwynDocument

/**
 * RecyclerView adapters for document lists.
 *
 * `ListAdapter` is used so updates are diffed: keeping scroll position and
 * avoiding full rebinds when only a star or a page number changed.
 */
class DocumentRowAdapter(
    private val onOpen: (PagwynDocument) -> Unit,
    private val onStar: (PagwynDocument) -> Unit,
    private val onLongPress: (PagwynDocument, View) -> Unit,
) : ListAdapter<PagwynDocument, DocumentRowAdapter.Holder>(DIFF) {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.docTitle)
        val meta: TextView = view.findViewById(R.id.docMeta)
        val position: TextView = view.findViewById(R.id.docPosition)
        val progress: ProgressBar = view.findViewById(R.id.docProgress)
        val star: ImageButton = view.findViewById(R.id.docStar)
        val thumbnail: ImageView = view.findViewById(R.id.docThumbnail)
        val fallback: ImageView = view.findViewById(R.id.docFallbackIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val document = getItem(position)
        holder.title.text = document.title
        holder.meta.text = metaLine(document)
        holder.position.text = if (document.pageCount > 0) "p. ${document.lastPage + 1}" else ""
        holder.progress.progress = (document.progress.coerceIn(0f, 1f) * 1000).toInt()

        holder.star.setImageResource(
            if (document.isStarred) R.drawable.ic_star_filled else R.drawable.ic_star,
        )
        holder.star.setColorFilter(
            if (document.isStarred) {
                androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.amber_500)
            } else {
                io.pagwyn.reader.ui.widget.colorFromAttr(holder.itemView.context, R.attr.pwOnSurfaceMuted)
            },
        )
        holder.star.contentDescription = holder.itemView.context.getString(
            if (document.isStarred) R.string.action_unstar else R.string.action_star,
        )
        holder.star.setOnClickListener { onStar(document) }

        holder.thumbnail.visibility = View.GONE
        holder.fallback.visibility = View.VISIBLE

        holder.itemView.setOnClickListener { onOpen(document) }
        holder.itemView.setOnLongClickListener {
            onLongPress(document, it)
            true
        }
    }

    private fun metaLine(document: PagwynDocument): String {
        val parts = mutableListOf<String>()
        if (document.pageCount > 0) parts.add("${document.pageCount} pages")
        if (document.sizeBytes > 0) parts.add(Formatters.fileSize(document.sizeBytes))
        document.location?.takeIf { it.isNotBlank() }?.let(parts::add)
        if (parts.isEmpty()) parts.add(Formatters.relativeTime(document.lastOpenedAt))
        return parts.joinToString(" · ")
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PagwynDocument>() {
            override fun areItemsTheSame(oldItem: PagwynDocument, newItem: PagwynDocument): Boolean =
                oldItem.uriString == newItem.uriString

            override fun areContentsTheSame(oldItem: PagwynDocument, newItem: PagwynDocument): Boolean =
                oldItem == newItem
        }
    }
}

/** Compact cards used for the starred rail. */
class DocumentCardAdapter(
    private val onOpen: (PagwynDocument) -> Unit,
) : ListAdapter<PagwynDocument, DocumentCardAdapter.Holder>(DocumentRowAdapter.DIFF) {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.docTitle)
        val meta: TextView = view.findViewById(R.id.docMeta)
        val progress: ProgressBar = view.findViewById(R.id.docProgress)
        val badge: ImageView = view.findViewById(R.id.docStarBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_document_compact, parent, false),
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val document = getItem(position)
        holder.title.text = document.title
        holder.meta.text = if (document.pageCount > 0) {
            "p. ${document.lastPage + 1} of ${document.pageCount}"
        } else {
            Formatters.relativeTime(document.lastOpenedAt)
        }
        holder.progress.progress = (document.progress.coerceIn(0f, 1f) * 1000).toInt()
        holder.badge.visibility = if (document.isStarred) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onOpen(document) }
    }
}
