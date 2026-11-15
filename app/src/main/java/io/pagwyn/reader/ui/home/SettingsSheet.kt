package io.pagwyn.reader.ui.home

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.pagwyn.reader.BuildConfig
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.R
import io.pagwyn.reader.data.AppTheme
import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.Settings
import io.pagwyn.reader.data.ZoomMode
import io.pagwyn.reader.ui.reader.sheets.ThemeSheet
import io.pagwyn.reader.ui.widget.PagwynSheet

/** All application settings. Every control applies immediately and persists. */
class SettingsSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_settings
    override val heightFraction: Float = 0.9f

    private val settings: Settings get() = PagwynApplication.settings

    /** Raised when a change needs the calling activity to re-theme. */
    var onThemeChanged: (() -> Unit)? = null
    var onHistoryCleared: (() -> Unit)? = null

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.settings_title)
        view.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        bindTheme(view)
        bindMode(view)
        bindZoom(view)
        bindReadingOptions(view)
        bindPrivacy(view)
    }

    /**
     * One entry point for the theme, opening the same picker the reader's Theme item
     * uses. The old radio group is gone: it duplicated the picker and, because the
     * reader painted its own tint, changing it here never affected the reader.
     */
    private fun bindTheme(view: View) {
        val row = view.findViewById<TextView>(R.id.rowTheme)
        row.text = getString(R.string.settings_app_theme)
        row.setOnClickListener {
            ThemeSheet().apply {
                onApplied = {
                    dismissAllowingStateLoss()
                    onThemeChanged?.invoke()
                }
            }.show(parentFragmentManager, "theme")
        }
        updateThemeSummary(row)
    }

    private fun updateThemeSummary(row: TextView) {
        val label = when (settings.theme) {
            AppTheme.SYSTEM -> R.string.theme_system
            AppTheme.LIGHT -> R.string.theme_light
            AppTheme.DARK -> R.string.theme_dark
            AppTheme.BLACK -> R.string.theme_black
        }
        row.text = getString(R.string.settings_app_theme_value, getString(label))
    }

    private fun bindMode(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.modeGroup)
        group.check(
            if (settings.defaultMode == ReadingMode.SINGLE_PAGE) R.id.modeSingle else R.id.modeContinuous,
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            settings.defaultMode =
                if (checkedId == R.id.modeSingle) ReadingMode.SINGLE_PAGE else ReadingMode.CONTINUOUS
        }
    }

    private fun bindZoom(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.zoomGroup)
        when (settings.defaultZoom) {
            ZoomMode.FIT_WIDTH -> group.check(R.id.zoomFitWidth)
            ZoomMode.FIT_PAGE -> group.check(R.id.zoomFitPage)
            ZoomMode.ACTUAL_SIZE -> group.check(R.id.zoomActual)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            settings.defaultZoom = when (checkedId) {
                R.id.zoomFitPage -> ZoomMode.FIT_PAGE
                R.id.zoomActual -> ZoomMode.ACTUAL_SIZE
                else -> ZoomMode.FIT_WIDTH
            }
        }
    }

    private fun bindReadingOptions(view: View) {
        val keepOn = view.findViewById<Switch>(R.id.switchKeepScreenOn)
        keepOn.isChecked = settings.keepScreenOn
        keepOn.setOnCheckedChangeListener { _, checked -> settings.keepScreenOn = checked }

        val hide = view.findViewById<Switch>(R.id.switchHideControls)
        hide.isChecked = settings.hideControls
        hide.setOnCheckedChangeListener { _, checked -> settings.hideControls = checked }

        val spacing = view.findViewById<SeekBar>(R.id.spacingSeek)
        spacing.max = MAX_SPACING
        spacing.progress = settings.pageSpacingDp.coerceIn(0, MAX_SPACING)
        spacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) settings.pageSpacingDp = progress
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun bindPrivacy(view: View) {
        view.findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_confirm_clear)
                .setMessage(R.string.settings_clear_history_desc)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear) { _, _ ->
                    PagwynApplication.documents.clearHistory()
                    settings.clearHistoryPreferences()
                    onHistoryCleared?.invoke()
                }
                .show()
        }
    }

    private companion object {
        const val MAX_SPACING = 24
    }
}
