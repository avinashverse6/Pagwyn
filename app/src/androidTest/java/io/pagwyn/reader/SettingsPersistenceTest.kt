package io.pagwyn.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.pagwyn.reader.data.AppTheme
import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.ReaderTheme
import io.pagwyn.reader.data.Settings
import io.pagwyn.reader.data.ZoomMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preferences must survive a real process restart.
 *
 * A fresh [Settings] instance reads the same SharedPreferences file, which is exactly
 * what happens when the user reopens the app.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        settings = Settings(context)
        resetDefaults()
    }

    @After
    fun tearDown() {
        resetDefaults()
    }

    private fun resetDefaults() {
        settings.theme = AppTheme.SYSTEM
        settings.readerTheme = ReaderTheme.FOLLOW_APP
        settings.defaultMode = ReadingMode.CONTINUOUS
        settings.defaultZoom = ZoomMode.FIT_WIDTH
        settings.keepScreenOn = false
        settings.hideControls = true
        settings.pageSpacingDp = 10
        settings.readingBrightnessPercent = 100
        settings.historyLimit = 25
    }

    @Test
    fun appearanceSettingsSurviveReload() {
        settings.theme = AppTheme.DARK
        settings.readerTheme = ReaderTheme.SEPIA
        settings.pageSpacingDp = 18

        val reloaded = Settings(context)
        assertEquals(AppTheme.DARK, reloaded.theme)
        assertEquals(ReaderTheme.SEPIA, reloaded.readerTheme)
        assertEquals(18, reloaded.pageSpacingDp)
    }

    @Test
    fun readingDefaultsSurviveReload() {
        settings.defaultMode = ReadingMode.SINGLE_PAGE
        settings.defaultZoom = ZoomMode.FIT_PAGE

        val reloaded = Settings(context)
        assertEquals(ReadingMode.SINGLE_PAGE, reloaded.defaultMode)
        assertEquals(ZoomMode.FIT_PAGE, reloaded.defaultZoom)
    }

    @Test
    fun booleanPreferencesSurviveReload() {
        settings.keepScreenOn = true
        settings.hideControls = false

        val reloaded = Settings(context)
        assertTrue(reloaded.keepScreenOn)
        assertFalse(reloaded.hideControls)
    }

    @Test
    fun brightnessClampsToTheUsableRange() {
        settings.readingBrightnessPercent = 0
        // Fully dark would make the app unusable; the lower bound is enforced.
        assertEquals(5, settings.readingBrightnessPercent)

        settings.readingBrightnessPercent = 250
        assertEquals(100, settings.readingBrightnessPercent)

        assertEquals(100, Settings(context).readingBrightnessPercent)
    }

    @Test
    fun invalidStoredValuesFallBackToDefaults() {
        // Values written by a newer version must never crash an older build.
        context.getSharedPreferences("pagwyn_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("theme", 99)
            .putInt("default_mode", -5)
            .putInt("default_zoom", 42)
            .putInt("brightness", -10)
            .commit()

        val reloaded = Settings(context)
        assertEquals(AppTheme.SYSTEM, reloaded.theme)
        assertEquals(ReadingMode.CONTINUOUS, reloaded.defaultMode)
        assertEquals(ZoomMode.FIT_WIDTH, reloaded.defaultZoom)
        assertEquals(5, reloaded.readingBrightnessPercent)
    }

    @Test
    fun clearingHistoryPreferencesForgetsTheLastDocument() {
        settings.lastDocumentUri = "content://example/doc"
        settings.clearHistoryPreferences()
        assertEquals(null, settings.lastDocumentUri)
    }
}
