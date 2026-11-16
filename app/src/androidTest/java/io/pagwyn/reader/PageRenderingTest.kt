package io.pagwyn.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pagwyn.reader.pdf.PdfDocument
import io.pagwyn.reader.pdf.PdfOpenException
import io.pagwyn.reader.ui.reader.PageSlotView
import io.pagwyn.reader.ui.reader.ReaderActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that pages are actually *drawn*, not merely counted.
 *
 * The reader could open a document, report the right page count and still show
 * "Rendering page…" forever — which is exactly what shipped when page handles were
 * kept open (the platform allows one open page per renderer, so every render threw
 * and the failure was swallowed). These tests assert on pixels reaching a view.
 */
@RunWith(AndroidJUnit4::class)
class PageRenderingTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    /**
     * The whole pipeline for one document, through a real `content://` URI:
     * open → render on the render thread → bitmap handed to the bound slot view.
     */
    @Test
    fun firstPageBitmapIsDrawnThroughAContentUri() {
        val uri = TestDocuments.writePdf("rendered.pdf", (1..4).map { "Page $it" })

        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, uri)).use { scenario ->
            var drawn: Bitmap? = null
            var state = "not sampled"
            // The API 24 emulator image renders the first page an order of magnitude
            // slower than API 34; a short timeout there reports "never drawn" for a
            // page that is merely still rendering.
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline && drawn == null) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.pageList)
                    val slot = list.findViewHolderForAdapterPosition(0)?.itemView as? PageSlotView
                    val snapshot = activity.viewModelSnapshot()
                    state = "list=${list.width}x${list.height} items=${list.adapter?.itemCount} " +
                        "pages=${snapshot.pageCount} stateError=${snapshot.error} " +
                        "slot=${slot?.javaClass?.simpleName} renderedWidth=${slot?.renderedWidthPx()} " +
                        "content=${slot?.hasContent()} error=${slot?.isShowingError()}"
                    val bitmap = slot?.currentBitmap()
                    if (bitmap != null && !bitmap.isRecycled && hasInk(bitmap)) {
                        drawn = bitmap
                    }
                }
                if (drawn == null) Thread.sleep(50)
            }
            if (drawn == null) {
                val message = "first page never drawn within 45s; last state: $state"
                TestDiagnostics.log(message)
                throw AssertionError(message)
            }
            // The placeholder must be gone; otherwise the user still sees "Rendering…".
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.pageList)
                val slot = list.findViewHolderForAdapterPosition(0)?.itemView as? PageSlotView
                assertNotNull(slot)
                assertTrue("a rendered page must report content", slot!!.hasContent())
                assertFalse("a rendered page must not show the error state", slot.isShowingError())
            }
            TestDiagnostics.log("first page drawn; state: $state")
            val bitmap = drawn
            assertNotNull("the first page must be drawn, not left on a placeholder", bitmap)
            assertTrue("the drawn page must contain actual content", hasInk(bitmap!!))
        }
    }

    /**
     * Every page of a multi-page document must render. This is the regression guard
     * for the "only the first page ever draws" bug: with a handle cache, page 2
     * onwards failed because `openPage` refuses a second page while one is open.
     */
    @Test
    fun everyPageRendersNotJustTheFirst() {
        val pageCount = 6
        val uri = TestDocuments.writePdf("all-pages.pdf", (1..pageCount).map { "Page $it" })

        PdfDocument.open(context, uri).use { document ->
            assertEquals(pageCount, document.pageCount)
            for (page in 0 until pageCount) {
                val width = 240
                val info = document.pageInfo(page)
                val height = (width * info.heightPoints.toFloat() / info.widthPoints).toInt()
                    .coerceAtLeast(1)
                val bitmap = PdfDocument.bitmapFor(width, height)
                val ok = document.renderPage(
                    page,
                    bitmap,
                    width,
                    height,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                assertTrue("page ${page + 1} of $pageCount failed to render", ok)
                assertTrue("page ${page + 1} rendered blank", hasInk(bitmap))
            }
        }
    }

    /** Mixed reads (geometry + render + text) must not leave the renderer wedged. */
    @Test
    fun interleavedGeometryAndRenderCallsKeepWorking() {
        val uri = TestDocuments.writePdf("interleaved.pdf", (1..5).map { "Page $it" })
        PdfDocument.open(context, uri).use { document ->
            repeat(3) { round ->
                for (page in 0 until document.pageCount) {
                    val info = document.pageInfo(page)
                    assertTrue("round $round page $page has no width", info.widthPoints > 0)
                    val bitmap = PdfDocument.bitmapFor(120, 160)
                    assertTrue(
                        "round $round page $page failed after a geometry read",
                        document.renderPage(page, bitmap, 120, 160),
                    )
                }
            }
        }
    }

    /** A page that cannot be drawn must report it, never hang silently. */
    @Test
    fun closedDocumentReportsFailureInsteadOfReturningSilently() {
        val uri = TestDocuments.writePdf("closed.pdf", listOf("one"))
        val document = PdfDocument.open(context, uri)
        document.close()
        val bitmap = PdfDocument.bitmapFor(100, 140)
        assertFalse(
            "a closed document must refuse the page",
            document.renderPage(0, bitmap, 100, 140),
        )
    }

    @Test
    fun unreadableUriThrowsTheSpecificOpenFailure() {
        val uri = Uri.parse("content://io.pagwyn.reader.testprovider/missing.pdf")
        try {
            PdfDocument.open(context, uri)
            throw AssertionError("opening a missing document must fail")
        } catch (expected: PdfOpenException.NotFound) {
            // Expected: the UI maps this to a clear message, not a blank screen.
        }
    }

    /**
     * True when the page has actual ink on it.
     *
     * The page is erased to white before rendering, so any dark pixel means text
     * reached the bitmap. A blank render is uniformly white and fails this.
     */
    private fun hasInk(bitmap: Bitmap): Boolean {
        var dark = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (Color.luminance(bitmap.getPixel(x, y)) < 0.75f) dark++
            }
        }
        return dark > 0
    }
}
