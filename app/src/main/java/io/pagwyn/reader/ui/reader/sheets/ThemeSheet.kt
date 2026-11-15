package io.pagwyn.reader.ui.reader.sheets

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.R
import io.pagwyn.reader.data.AppTheme
import io.pagwyn.reader.ui.widget.PagwynSheet
import io.pagwyn.reader.ui.widget.ThemeApplier
import io.pagwyn.reader.ui.widget.colorFromAttr
import io.pagwyn.reader.ui.widget.dp
import io.pagwyn.reader.ui.widget.pillBackground

/**
 * The one theme picker, used by Settings **and** by the reader's "Theme" menu item.
 *
 * Both entry points show this same sheet so there is never a "settings theme" and a
 * separate "reader theme" that disagree. Choosing an option applies it to the whole
 * app immediately; the caller recreates its activity so already-inflated views pick
 * up the new resources.
 */
class ThemeSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_theme
    override val heightFraction: Float = 0.52f

    /** Invoked after the theme has been persisted and applied. */
    var onApplied: ((AppTheme) -> Unit)? = null

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.theme_title)
        view.findViewById<TextView>(R.id.sheetSubtitle).text = getString(R.string.theme_subtitle)

        val rows = view.findViewById<LinearLayout>(R.id.themeRows)
        val options = listOf(
            AppTheme.SYSTEM to R.string.theme_system,
            AppTheme.LIGHT to R.string.theme_light,
            AppTheme.DARK to R.string.theme_dark,
            AppTheme.BLACK to R.string.theme_black,
        )

        val labels = HashMap<AppTheme, TextView>()
        for ((option, labelRes) in options) {
            val row = TextView(requireContext()).apply {
                text = getString(labelRes)
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = dp(52f).toInt()
                setPadding(dp(20f).toInt(), 0, dp(20f).toInt(), 0)
                isClickable = true
                isFocusable = true
                contentDescription = getString(labelRes)
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(8f).toInt() }
            rows.addView(row, params)
            labels[option] = row
            row.setOnClickListener { choose(option, labels) }
        }

        style(rows, labels, PagwynApplication.settings.theme)
    }

    private fun choose(option: AppTheme, labels: Map<AppTheme, TextView>) {
        val settings = PagwynApplication.settings
        if (settings.theme == option) return
        settings.theme = option
        ThemeApplier.apply(option)
        style(labels.values.firstOrNull()?.parent as? LinearLayout ?: return, labels, option)
        onApplied?.invoke(option)
    }

    /** The selected option uses the accent fill; the rest stay on the raised surface. */
    private fun style(rows: LinearLayout, labels: Map<AppTheme, TextView>, selected: AppTheme) {
        val context = context ?: return
        for ((option, row) in labels) {
            val isSelected = option == selected
            row.background = pillBackground(
                context,
                context.colorFromAttr(if (isSelected) R.attr.pwPrimary else R.attr.pwSurfaceSunken),
            )
            row.setTextColor(
                context.colorFromAttr(if (isSelected) R.attr.pwOnPrimary else R.attr.pwOnSurface),
            )
            row.isSelected = isSelected
        }
        rows.tag = selected
    }
}
