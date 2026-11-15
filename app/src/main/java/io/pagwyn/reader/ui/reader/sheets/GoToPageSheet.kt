package io.pagwyn.reader.ui.reader.sheets

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import io.pagwyn.reader.R
import io.pagwyn.reader.core.Formatters
import io.pagwyn.reader.ui.widget.PagwynSheet

/** Jump to a page by number, or scrub with a slider. */
class GoToPageSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_goto_page
    override val heightFraction: Float = 0.45f

    var pageCount: Int = 1
    var currentPage: Int = 0
    var onPageSelected: ((Int) -> Unit)? = null

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.reader_go_to_page)
        val field = view.findViewById<EditText>(R.id.pageField)
        val confirm = view.findViewById<Button>(R.id.gotoConfirm)
        val hint = view.findViewById<TextView>(R.id.gotoHint)
        val seek = view.findViewById<SeekBar>(R.id.pageSeek)

        hint.text = getString(R.string.reader_page_range, pageCount)
        seek.max = (pageCount - 1).coerceAtLeast(0)
        seek.progress = currentPage.coerceIn(0, seek.max)
        field.setText((currentPage + 1).toString())
        field.setSelection(field.text.length)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) field.setText((progress + 1).toString())
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val target = (bar?.progress ?: 0).coerceIn(0, pageCount - 1)
                onPageSelected?.invoke(target)
                dismissAllowingStateLoss()
            }
        })

        fun submit() {
            val value = field.text?.toString()?.toIntOrNull()
            if (value == null || value < 1 || value > pageCount) {
                hint.text = getString(R.string.reader_page_invalid, pageCount)
                return
            }
            onPageSelected?.invoke(value - 1)
            dismissAllowingStateLoss()
        }

        confirm.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
    }

    /** Convenience for hosts that want the subtitle style label. */
    fun label(page: Int): String = Formatters.pageLabel(page, pageCount)
}
