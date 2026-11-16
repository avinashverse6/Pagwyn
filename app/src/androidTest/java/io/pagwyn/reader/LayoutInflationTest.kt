package io.pagwyn.reader

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inflates every layout on the device.
 *
 * Android 7 aborts inflation of a `TextView` whose attribute set it cannot resolve
 * from the resource pool — a platform bug that only appears on device, with nothing
 * for a JVM test to catch. Inflating each layout directly pins the failure to one
 * file instead of leaving a stack trace that points at an arbitrary line.
 */
@RunWith(AndroidJUnit4::class)
class LayoutInflationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inflater get() = LayoutInflater.from(context)

    private fun inflate(id: Int, name: String) {
        val view = inflater.inflate(id, null, false)
        assertTrue("$name inflated to nothing", view != null)
    }

    private fun report(id: Int, name: String, failures: MutableList<String>) {
        try {
            inflate(id, name)
        } catch (failure: Throwable) {
            failures += "$name: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    // ------------------------------------------------------------------ probes

    @Test
    fun probePlainTextViewInflates() = inflate(R.layout.probe_text_plain, "probe_text_plain")

    @Test
    fun probeKickerTextViewInflates() = inflate(R.layout.probe_text_kicker, "probe_text_kicker")

    @Test
    fun probeWithoutLetterSpacingInflates() =
        inflate(R.layout.probe_text_spacing, "probe_text_spacing")

    @Test
    fun probeWithoutAllCapsInflates() = inflate(R.layout.probe_text_caps, "probe_text_caps")

    // ------------------------------------------------------------------ screens

    /**
     * Every screen layout must inflate on every supported API level.
     *
     * Collects all failures and reports them together: one run names every broken
     * layout, instead of one run per broken file.
     */
    @Test
    fun everyScreenLayoutInflates() {
        val failures = mutableListOf<String>()
        val layouts = mapOf(
            "activity_home" to R.layout.activity_home,
            "activity_reader" to R.layout.activity_reader,
            "activity_metadata" to R.layout.activity_metadata,
            "sheet_settings" to R.layout.sheet_settings,
            "sheet_theme" to R.layout.sheet_theme,
            "sheet_appearance" to R.layout.sheet_appearance,
            "sheet_bookmarks" to R.layout.sheet_bookmarks,
            "sheet_toc" to R.layout.sheet_toc,
            "sheet_search" to R.layout.sheet_search,
            "sheet_goto_page" to R.layout.sheet_goto_page,
            "item_document_card" to R.layout.item_document_card,
            "item_document_row" to R.layout.item_document_row,
            "item_simple_row" to R.layout.item_simple_row,
            "item_toc_entry" to R.layout.item_toc_entry,
            "item_search_result" to R.layout.item_search_result,
            "item_metadata_row" to R.layout.item_metadata_row,
            "item_page" to R.layout.item_page,
        )
        for ((name, id) in layouts) report(id, name, failures)
        assertTrue("layouts failed to inflate:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** The generated cover must produce a usable bitmap without a layout at all. */
    @Test
    fun coverArtworkRendersOnDevice() {
        val bitmap = io.pagwyn.reader.ui.widget.CoverArtwork.create(
            size = 128,
            seed = "Deep Work.pdf",
            darkTheme = true,
        )
        assertTrue("cover has no pixels", bitmap.width == 128 && bitmap.height == 128)
        assertTrue("cover was already recycled", !bitmap.isRecycled)
    }
}
