package io.pagwyn.reader.ui.reader.sheets

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import io.pagwyn.reader.R
import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.ReaderTheme
import io.pagwyn.reader.ui.widget.PagwynSheet
import io.pagwyn.reader.ui.widget.colorFromAttr

/** Reading theme, mode, page spacing and brightness. */
class AppearanceSheet : PagwynSheet() {

    override val layoutRes: Int = R.layout.sheet_appearance
    override val heightFraction: Float = 0.72f

    var theme: ReaderTheme = ReaderTheme.FOLLOW_APP
    var mode: ReadingMode = ReadingMode.CONTINUOUS
    var spacingDp: Int = 10
    var brightness: Int = 100

    var onThemeChanged: ((ReaderTheme) -> Unit)? = null
    var onModeChanged: ((ReadingMode) -> Unit)? = null
    var onSpacingChanged: ((Int) -> Unit)? = null
    var onBrightnessChanged: ((Int) -> Unit)? = null

    override fun onViewCreated(view: View) {
        view.findViewById<TextView>(R.id.sheetTitle).text = getString(R.string.reader_theme)

        val light = view.findViewById<Button>(R.id.themeLight)
        val sepia = view.findViewById<Button>(R.id.themeSepia)
        val dark = view.findViewById<Button>(R.id.themeDark)
        val scroll = view.findViewById<Button>(R.id.modeScroll)
        val single = view.findViewById<Button>(R.id.modeSingle)

        fun renderTheme() {
            style(light, theme == ReaderTheme.LIGHT)
            style(sepia, theme == ReaderTheme.SEPIA)
            style(dark, theme == ReaderTheme.DARK)
        }

        fun renderMode() {
            style(scroll, mode == ReadingMode.CONTINUOUS)
            style(single, mode == ReadingMode.SINGLE_PAGE)
        }

        light.setOnClickListener { theme = ReaderTheme.LIGHT; renderTheme(); onThemeChanged?.invoke(theme) }
        sepia.setOnClickListener { theme = ReaderTheme.SEPIA; renderTheme(); onThemeChanged?.invoke(theme) }
        dark.setOnClickListener { theme = ReaderTheme.DARK; renderTheme(); onThemeChanged?.invoke(theme) }
        scroll.setOnClickListener {
            mode = ReadingMode.CONTINUOUS
            renderMode()
            onModeChanged?.invoke(mode)
        }
        single.setOnClickListener {
            mode = ReadingMode.SINGLE_PAGE
            renderMode()
            onModeChanged?.invoke(mode)
        }

        renderTheme()
        renderMode()

        view.findViewById<SeekBar>(R.id.spacingSeek).apply {
            max = 24
            progress = spacingDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onSpacingChanged?.invoke(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }

        view.findViewById<SeekBar>(R.id.brightnessSeek).apply {
            max = 100
            progress = brightness
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    onBrightnessChanged?.invoke(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
    }

    /** Selected chips use the accent fill; unselected use the outlined surface. */
    private fun style(button: Button, selected: Boolean) {
        button.setBackgroundResource(
            if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_secondary,
        )
        val attr = if (selected) R.attr.pwOnPrimary else R.attr.pwOnSurface
        button.setTextColor(requireContext().colorFromAttr(attr))
    }
}
