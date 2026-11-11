package io.pagwyn.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Reading mode used when a document is opened. */
enum class ReadingMode(val storedValue: Int) {
    CONTINUOUS(0),
    SINGLE_PAGE(1);

    companion object {
        fun from(value: Int): ReadingMode = entries.firstOrNull { it.storedValue == value } ?: CONTINUOUS
    }
}

/** How a page is scaled inside the viewport. */
enum class ZoomMode(val storedValue: Int) {
    FIT_WIDTH(0),
    FIT_PAGE(1),
    ACTUAL_SIZE(2);

    companion object {
        fun from(value: Int): ZoomMode = entries.firstOrNull { it.storedValue == value } ?: FIT_WIDTH
    }
}

/** Reading tint that surrounds (never alters) the PDF page. */
enum class ReaderTheme(val storedValue: Int) {
    LIGHT(0),
    SEPIA(1),
    DARK(2),
    FOLLOW_APP(3);

    companion object {
        fun from(value: Int): ReaderTheme = entries.firstOrNull { it.storedValue == value } ?: FOLLOW_APP
    }
}

enum class AppTheme(val storedValue: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun from(value: Int): AppTheme = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/**
 * All user preferences in one small SharedPreferences file.
 *
 * No preference is synced or uploaded; the file stays in app-private storage and
 * is excluded from cloud backup except for the preferences themselves.
 */
class Settings(private val context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var theme: AppTheme
        get() = AppTheme.from(prefs.getInt(KEY_THEME, AppTheme.SYSTEM.storedValue))
        set(value) = prefs.edit { putInt(KEY_THEME, value.storedValue) }

    var readerTheme: ReaderTheme
        get() = ReaderTheme.from(prefs.getInt(KEY_READER_THEME, ReaderTheme.FOLLOW_APP.storedValue))
        set(value) = prefs.edit { putInt(KEY_READER_THEME, value.storedValue) }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    var hideControls: Boolean
        get() = prefs.getBoolean(KEY_HIDE_CONTROLS, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_CONTROLS, value) }

    /** Page gap in dp. */
    var pageSpacingDp: Int
        get() = prefs.getInt(KEY_PAGE_SPACING, 10)
        set(value) = prefs.edit { putInt(KEY_PAGE_SPACING, value) }

    var defaultMode: ReadingMode
        get() = ReadingMode.from(prefs.getInt(KEY_DEFAULT_MODE, ReadingMode.CONTINUOUS.storedValue))
        set(value) = prefs.edit { putInt(KEY_DEFAULT_MODE, value.storedValue) }

    var defaultZoom: ZoomMode
        get() = ZoomMode.from(prefs.getInt(KEY_DEFAULT_ZOOM, ZoomMode.FIT_WIDTH.storedValue))
        set(value) = prefs.edit { putInt(KEY_DEFAULT_ZOOM, value.storedValue) }

    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, 25)
        set(value) = prefs.edit { putInt(KEY_HISTORY_LIMIT, value) }

    /** 0..1 screen brightness override while reading; <= 0 means "follow system". */
    var readingBrightness: Float
        get() = prefs.getFloat(KEY_BRIGHTNESS, 0f)
        set(value) = prefs.edit { putFloat(KEY_BRIGHTNESS, value) }

    var lastDocumentUri: String?
        get() = prefs.getString(KEY_LAST_DOC, null)
        set(value) = prefs.edit { putString(KEY_LAST_DOC, value) }

    /** Page-turn animation preference for single-page mode. */
    var showProgressBar: Boolean
        get() = prefs.getBoolean(KEY_PROGRESS_BAR, true)
        set(value) = prefs.edit { putBoolean(KEY_PROGRESS_BAR, value) }

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

    fun clearHistoryPreferences() {
        prefs.edit {
            remove(KEY_LAST_DOC)
        }
    }

    private companion object {
        const val FILE_NAME = "pagwyn_prefs"
        const val KEY_THEME = "theme"
        const val KEY_READER_THEME = "reader_theme"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_HIDE_CONTROLS = "hide_controls"
        const val KEY_PAGE_SPACING = "page_spacing"
        const val KEY_DEFAULT_MODE = "default_mode"
        const val KEY_DEFAULT_ZOOM = "default_zoom"
        const val KEY_HISTORY_LIMIT = "history_limit"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_LAST_DOC = "last_document"
        const val KEY_PROGRESS_BAR = "progress_bar"
        const val KEY_ONBOARDED = "onboarded"
    }
}
