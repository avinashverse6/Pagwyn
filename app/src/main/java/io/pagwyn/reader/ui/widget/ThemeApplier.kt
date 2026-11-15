package io.pagwyn.reader.ui.widget

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.pagwyn.reader.data.AppTheme
import io.pagwyn.reader.data.Settings

/**
 * Applies the user's theme to the **whole app**, exactly once and as early as possible.
 *
 * Why this exists:
 *
 *  - Setting `AppCompatDelegate.setDefaultNightMode` only takes effect on the next
 *    configuration change, so every activity used to call it in `onCreate` — after the
 *    window background had already been resolved. Applying it in
 *    `Application.onCreate` means the very first frame of the very first activity is
 *    already in the right theme, with no flash of the previous one.
 *  - The reader used to paint its own colours from a *separate* `ReaderTheme`
 *    preference, which is why choosing a theme in Settings changed the Settings sheet
 *    and nothing else. There is now one preference ([AppTheme]) and one code path.
 *  - `Activity.recreate()` is required to re-resolve already-created views, because
 *    `windowBackground` and every `?attr` reference were resolved at inflation time.
 */
object ThemeApplier {

    /** Night mode for [theme], expressed as an `AppCompatDelegate` constant. */
    fun nightModeFor(theme: AppTheme): Int = when (theme) {
        AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        AppTheme.DARK, AppTheme.BLACK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    /**
     * Applies [theme] to the process. Safe to call from `Application.onCreate`, before
     * any activity exists.
     */
    fun apply(theme: AppTheme) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(theme))
        BlackTheme.enabled = theme == AppTheme.BLACK
    }

    /** Reads the stored theme and applies it. Used at startup. */
    fun applyStored(settings: Settings) = apply(settings.theme)

    /**
     * True when the app should currently draw dark surfaces.
     *
     * Used by the few places that need a concrete colour instead of an `?attr`
     * reference (the page background behind the bitmap, the reader chrome scrim).
     */
    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * True when the theme wants true-black surfaces (OLED).
     *
     * Resource qualifiers cannot express "dark, but blacker", so the black surface
     * colours are applied in code: the theme records the choice here, and the views
     * that own a large surface read it. Everything else keeps using the dark theme,
     * which is what makes this variant cheap.
     */
    fun isBlack(theme: AppTheme, context: Context): Boolean =
        theme == AppTheme.BLACK && isDark(context)

    /** Whether the user chose the OLED (true black) variant. Set by [apply]. */
    val blackSelected: Boolean get() = BlackTheme.enabled
}

/** Process-wide flag for the OLED variant; see [ThemeApplier.apply]. */
internal object BlackTheme {
    @Volatile
    var enabled: Boolean = false
}
