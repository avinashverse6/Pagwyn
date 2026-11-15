package io.pagwyn.reader

import io.pagwyn.reader.pdf.PageInfo
import io.pagwyn.reader.ui.widget.PageImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page geometry maths.
 *
 * Getting these wrong is immediately visible to users (stretched text, pages cut
 * off at the edge), so they are covered explicitly.
 */
class ZoomMathTest {

    @Test
    fun `a4 aspect ratio is portrait`() {
        val info = PageInfo(0, widthPoints = 595, heightPoints = 842)
        assertEquals(0.707f, info.aspectRatio, 0.002f)
    }

    @Test
    fun `landscape pages report a ratio above one`() {
        val info = PageInfo(0, widthPoints = 842, heightPoints = 595)
        assertEquals(1.415f, info.aspectRatio, 0.002f)
    }

    @Test
    fun `zero height never divides by zero`() {
        val info = PageInfo(0, widthPoints = 0, heightPoints = 0)
        assertEquals(0.75f, info.aspectRatio, 0.0001f)
    }

    @Test
    fun `aspect helper falls back for invalid dimensions`() {
        assertEquals(PageImageView.A4_ASPECT, PageImageView.aspectFor(0, 0), 0.0001f)
        assertEquals(0.5f, PageImageView.aspectFor(500, 1000), 0.0001f)
    }

    @Test
    fun `scaled height preserves the aspect ratio`() {
        // A4 at 1000px wide is 1414px tall.
        assertEquals(1414, PageImageView.scaledHeight(1000, 0.707f))
        // Degenerate ratios still yield a positive height, never zero or negative.
        assertTrue(PageImageView.scaledHeight(1, 0.0001f) >= 1)
        assertTrue(PageImageView.scaledHeight(100, 0f) >= 1)
    }
}
