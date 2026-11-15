package io.pagwyn.reader.ui.home

import android.content.Context
import android.view.HapticFeedbackConstants
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
import io.pagwyn.reader.ui.widget.CoverArtwork
import io.pagwyn.reader.ui.widget.colorFromAttr

/**
 * Library grid.
 *
 * Covers are **generated** from the file name ([CoverArtwork]) rather than rendered
 * from page 1: rendering a thumbnail here would mean calling the PDF renderer from
 * list binding, which is the threading mistake that aborts the process inside pdfium.
 * A generated cover is also instant and costs no bitmap memory.
 */
class DocumentCardAdapter(
    private val onOpen: (PagwynDocument) -> Unit,
    private val onStar: (PagwynDocument) -> Unit,
    private val onLongPress: (PagwynDocument, View) -> Unit,
) : ListAdapter<PagwynDocument, DocumentCardAdapter.Holder>(DIFF) {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cardCover)
        val title: TextView = view.findViewById(R.id.cardTitle)
        val meta: TextView = view.findViewById(R.id.cardMeta)
        val progress: ProgressBar = view.findViewById(R.id.cardProgress)
        val star: ImageButton = view.findViewById(R.id.cardStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_document_card, parent, false),
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val document = getItem(position)
        val context = holder.itemView.context

        holder.title.text = document.title
        holder.meta.text = metaLine(context, document)
        holder.progress.progress = (document.progress.coerceIn(0f, 1f) * 1000).toInt()
        holder.progress.visibility = if (document.pageCount > 0) View.VISIBLE else View.INVISIBLE

        holder.cover.setImageBitmap(
            CoverArtwork.create(
                size = COVER_SIZE_PX,
                seed = document.displayName.ifBlank { document.title },
                darkTheme = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES,
            ),
        )
        holder.cover.contentDescription = context.getString(
            R.string.cd_document_cover,
            CoverArtwork.initialsFor(document.title),
        )

        bindStar(context, holder.star, document, onStar)

        holder.itemView.setOnClickListener { onOpen(document) }
        holder.itemView.setOnLongClickListener {
            onLongPress(document, it)
            true
        }
        holder.itemView.pressScale()
    }

    private fun metaLine(context: Context, document: PagwynDocument): String =
        if (document.pageCount > 0) {
            context.getString(R.string.home_page_of, document.lastPage + 1, document.pageCount)
        } else if (document.sizeBytes > 0) {
            Formatters.fileSize(document.sizeBytes)
        } else {
            Formatters.relativeTime(document.lastOpenedAt)
        }

    companion object {
        private const val COVER_SIZE_PX = 256
        val DIFF = object : DiffUtil.ItemCallback<PagwynDocument>() {
            override fun areItemsTheSame(oldItem: PagwynDocument, newItem: PagwynDocument): Boolean =
                oldItem.uriString == newItem.uriString

            override fun areContentsTheSame(oldItem: PagwynDocument, newItem: PagwynDocument): Boolean =
                oldItem == newItem
        }
    }
}

/** Search results: a single-column row with the same generated cover at a small size. */
class DocumentRowAdapter(
    private val onOpen: (PagwynDocument) -> Unit,
    private val onStar: (PagwynDocument) -> Unit,
) : ListAdapter<PagwynDocument, DocumentRowAdapter.Holder>(DocumentCardAdapter.DIFF) {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.rowCover)
        val title: TextView = view.findViewById(R.id.rowTitle)
        val meta: TextView = view.findViewById(R.id.rowMeta)
        val star: ImageButton = view.findViewById(R.id.rowStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_document_row, parent, false),
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val document = getItem(position)
        val context = holder.itemView.context
        holder.title.text = document.title
        holder.meta.text = if (document.pageCount > 0) {
            context.getString(R.string.home_page_of, document.lastPage + 1, document.pageCount)
        } else {
            Formatters.relativeTime(document.lastOpenedAt)
        }
        holder.cover.setImageBitmap(
            CoverArtwork.create(
                size = 128,
                seed = document.displayName.ifBlank { document.title },
                darkTheme = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES,
            ),
        )
        holder.cover.contentDescription = null
        bindStar(context, holder.star, document, onStar)
        holder.itemView.setOnClickListener { onOpen(document) }
        holder.itemView.pressScale()
    }
}

/** Shared star binding for both list types. */
internal fun bindStar(
    context: Context,
    button: ImageButton,
    document: PagwynDocument,
    onStar: (PagwynDocument) -> Unit,
) {
    button.setImageResource(if (document.isStarred) R.drawable.ic_star_filled else R.drawable.ic_star)
    button.setColorFilter(
        context.colorFromAttr(if (document.isStarred) R.attr.pwPrimary else R.attr.pwOnSurfaceMuted),
    )
    button.contentDescription = context.getString(
        if (document.isStarred) R.string.action_unstar else R.string.action_star,
    )
    button.setOnClickListener {
        // A light haptic marks the state change; the star is a toggle, and this is the
        // one place in the library where a tap has a lasting effect.
        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onStar(document)
    }
}

/**
 * Press feedback: a 150ms scale to 0.97 while held.
 *
 * Deliberately an animation on the item view rather than a ripple: the card has 20dp
 * rounded corners, and a rectangular ripple that ignores them reads as a bug.
 */
internal fun View.pressScale(scale: Float = 0.97f, durationMs: Long = 150L) {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                view.animate().scaleX(scale).scaleY(scale).setDuration(durationMs).start()
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(durationMs).start()
        }
        false
    }
}
