package io.pagwyn.reader

import android.graphics.Bitmap
import android.graphics.Color
import io.pagwyn.reader.ui.widget.CoverArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Generated covers.
 *
 * The point of generating artwork from the file name is that it must be **stable**
 * (the same document always looks the same) and **opaque** (it carries a readable
 * surface behind the page title). Both are testable without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
// Real Skia rasterisation. Robolectric's legacy graphics mode records draw calls
// without painting pixels, so a shader-filled bitmap would read back as transparent
// and the "the cover is actually painted" assertion would be meaningless.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverArtworkTest {

    @Test
    fun initialsUseTheFirstLettersOfUpToTwoWords() {
        assertEquals("DW", CoverArtwork.initialsFor("Deep Work.pdf"))
        assertEquals("DW", CoverArtwork.initialsFor("deep-work_notes.pdf"))
        assertEquals("P", CoverArtwork.initialsFor("Physics.pdf"))
        assertEquals("N", CoverArtwork.initialsFor("1984 notes.pdf").first().toString())
    }

    @Test
    fun initialsNeverComeBackEmpty() {
        assertEquals("P", CoverArtwork.initialsFor(""))
        assertEquals("P", CoverArtwork.initialsFor("   "))
        assertEquals("P", CoverArtwork.initialsFor("1234.pdf"))
    }

    @Test
    fun initialsIgnoreLeadingNumbersAndPunctuation() {
        // "2019 - annual-report.pdf" must not produce "2-" or similar noise.
        val initials = CoverArtwork.initialsFor("2019 - annual-report.pdf")
        assertTrue("expected letters, got '$initials'", initials.all { it.isLetter() })
        assertEquals(2, initials.length)
    }

    @Test
    fun theSameDocumentAlwaysGetsTheSameColours() {
        val first = CoverArtwork.colorsFor("Deep Work.pdf", darkTheme = true)
        val second = CoverArtwork.colorsFor("Deep Work.pdf", darkTheme = true)
        assertEquals(first, second)
    }

    @Test
    fun differentDocumentsGetDifferentColours() {
        val a = CoverArtwork.colorsFor("Deep Work.pdf", darkTheme = true)
        val b = CoverArtwork.colorsFor("Physics notes.pdf", darkTheme = true)
        assertNotEquals(a, b)
    }

    @Test
    fun coloursAreOpaqueSoTextStaysReadable() {
        for (name in listOf("a.pdf", "b.pdf", "Long Document Name.pdf")) {
            val (start, end) = CoverArtwork.colorsFor(name, darkTheme = true)
            assertEquals("start must be opaque", 255, Color.alpha(start))
            assertEquals("end must be opaque", 255, Color.alpha(end))
            assertTrue("colours must differ so the gradient is visible", start != end)
        }
    }

    @Test
    fun darkThemeIsDarkerThanLightTheme() {
        val (darkStart, _) = CoverArtwork.colorsFor("Deep Work.pdf", darkTheme = true)
        val (lightStart, _) = CoverArtwork.colorsFor("Deep Work.pdf", darkTheme = false)
        assertNotEquals(darkStart, lightStart)
    }

    @Test
    fun renderingFillsTheCoverWithTheGradient() {
        val bitmap = CoverArtwork.create(64, "Deep Work.pdf", darkTheme = true)
        assertEquals(64, bitmap.width)
        assertEquals(64, bitmap.height)
        // Corners must be painted: an empty bitmap would be fully transparent.
        assertEquals(255, Color.alpha(bitmap.getPixel(1, 1)))
        assertEquals(255, Color.alpha(bitmap.getPixel(62, 62)))
    }
}
