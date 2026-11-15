package io.pagwyn.reader.ui.reader.sheets

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.pagwyn.reader.R
import io.pagwyn.reader.pdf.SearchHit
import io.pagwyn.reader.ui.widget.PagwynSheet

/**
 * Full-text search with incremental, debounced input.
 *
 * The heavy lifting happens in the ViewModel on a background thread; this sheet
 * only renders results. Selecting a result jumps to the page and closes the sheet.
 */
class SearchSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_search
    override val heightFraction: Float = 0.85f

    var onQueryChanged: ((String) -> Unit)? = null
    var onResultSelected: ((Int) -> Unit)? = null

    private lateinit var field: EditText
    private lateinit var results: RecyclerView
    private lateinit var count: TextView
    private lateinit var empty: View
    private lateinit var emptyLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var clear: ImageButton
    private lateinit var adapter: ResultAdapter

    private var currentHits: List<SearchHit> = emptyList()
    private var running: Boolean = false
    private var textLayerAvailable: Boolean = true

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.searchCount).also { count = it }
        field = view.findViewById(R.id.searchField)
        results = view.findViewById(R.id.searchResults)
        empty = view.findViewById(R.id.searchEmpty)
        emptyLabel = view.findViewById(R.id.searchEmptyLabel)
        progress = view.findViewById(R.id.searchProgress)
        clear = view.findViewById(R.id.searchClear)

        adapter = ResultAdapter()
        results.layoutManager = LinearLayoutManager(requireContext())
        results.adapter = adapter

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                onQueryChanged?.invoke(query)
            }
        })

        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }

        clear.setOnClickListener {
            field.setText("")
            field.requestFocus()
        }

        field.post {
            field.requestFocus()
            field.showKeyboard()
        }
        renderState()
    }

    /** Called by the host whenever the ViewModel search state changes. */
    fun submit(hits: List<SearchHit>, isRunning: Boolean, queryPresent: Boolean, textLayer: Boolean) {
        if (!isAdded) return
        currentHits = hits
        running = isRunning
        textLayerAvailable = textLayer
        val query = if (::field.isInitialized) field.text?.toString().orEmpty() else ""
        empty.visibility = if (!queryPresent || hits.isNotEmpty() || isRunning) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        renderState()
        if (query.isEmpty().not()) results.scrollToPosition(0)
    }

    private fun renderState() {
        progress.visibility = if (running) View.VISIBLE else View.GONE
        val query = if (::field.isInitialized) field.text?.toString().orEmpty() else ""
        count.text = when {
            query.isBlank() -> ""
            running -> getString(R.string.reader_searching)
            currentHits.isEmpty() -> ""
            else -> resources.getQuantityString(
                R.plurals.reader_matches,
                currentHits.size,
                currentHits.size,
            )
        }
        emptyLabel.text = if (!textLayerAvailable) {
            getString(R.string.reader_text_selection_unavailable)
        } else {
            getString(R.string.reader_no_results)
        }
    }

    private fun EditText.showKeyboard() {
        val imm = this.context?.getSystemService<InputMethodManager>() ?: return
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = field.context?.getSystemService<InputMethodManager>() ?: return
        imm.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val page: TextView = itemView.findViewById(R.id.resultPage)
            val snippet: TextView = itemView.findViewById(R.id.resultSnippet)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(inflater(parent))

        override fun getItemCount(): Int = currentHits.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val hit = currentHits[position]
            holder.page.text = getString(R.string.reader_result_item, hit.pageIndex + 1)
            holder.snippet.text = hit.snippet
            holder.itemView.setOnClickListener {
                onResultSelected?.invoke(hit.pageIndex)
                dismissAllowingStateLoss()
            }
        }

        private fun inflater(parent: ViewGroup): View =
            layoutInflater.inflate(R.layout.item_search_result, parent, false)
    }
}
