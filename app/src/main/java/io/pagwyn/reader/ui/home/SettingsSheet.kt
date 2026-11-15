package io.pagwyn.reader.ui.home

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import io.pagwyn.reader.BuildConfig
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.R
import io.pagwyn.reader.data.AppTheme
import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.ReaderTheme
import io.pagwyn.reader.data.Settings
import io.pagwyn.reader.data.ZoomMode
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
        bindReaderTheme(view)
        bindMode(view)
        bindZoom(view)
        bindReadingOptions(view)
        bindPrivacy(view)
    }

    private fun bindTheme(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.themeGroup)
        when (settings.theme) {
            AppTheme.SYSTEM -> group.check(R.id.themeSystem)
            AppTheme.LIGHT -> group.check(R.id.themeLight)
            AppTheme.DARK -> group.check(R.id.themeDark)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.themeLight -> AppTheme.LIGHT
                R.id.themeDark -> AppTheme.DARK
                else -> AppTheme.SYSTEM
            }
            settings.theme = theme
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                },
            )
            onThemeChanged?.invoke()
        }
    }

    private fun bindReaderTheme(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.readerThemeGroup)
        when (settings.readerTheme) {
            ReaderTheme.FOLLOW_APP -> group.check(R.id.readerThemeFollow)
            ReaderTheme.LIGHT -> group.check(R.id.readerThemeLight)
            ReaderTheme.SEPIA -> group.check(R.id.readerThemeSepia)
            ReaderTheme.DARK -> group.check(R.id.readerThemeDark)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            settings.readerTheme = when (checkedId) {
                R.id.readerThemeLight -> ReaderTheme.LIGHT
                R.id.readerThemeSepia -> ReaderTheme.SEPIA
                R.id.readerThemeDark -> ReaderTheme.DARK
                else -> ReaderTheme.FOLLOW_APP
            }
        }
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
