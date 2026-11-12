package io.pagwyn.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pagwyn.reader.pdf.PdfDocument
import io.pagwyn.reader.pdf.PdfOpenException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The renderer is the one component Pagwyn cannot unit test on the JVM, so it gets
 * the most instrumented attention: real files, real `PdfRenderer`, real bitmaps.
 */
@RunWith(AndroidJUnit4::class)
class PdfDocumentTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private inline fun <T> withDocument(uri: Uri, block: (PdfDocument) -> T): T {
        val document = PdfDocument.open(context, uri)
        return try {
            block(document)
        } finally {
            document.close()
        }
    }

    @Test
    fun pageCountMatchesTheSourceDocument() {
        val uri = TestDocuments.writePdf("count.pdf", (1..7).map { "Page $it" })
        withDocument(uri) { document ->
            assertEquals(7, document.pageCount)
        }
    }

    @Test
    fun pageGeometryIsReportedInPoints() {
        val uri = TestDocuments.writePdf("geometry.pdf", listOf("A4"))
        withDocument(uri) { document ->
            val info = document.pageInfo(0)
            // A4 at 72 dpi.
            assertEquals(595, info.widthPoints)
            assertEquals(842, info.heightPoints)
            assertTrue(info.aspectRatio in 0.7f..0.71f)
        }
    }

    @Test
    fun renderingProducesVisibleContent() {
        val uri = TestDocuments.writePdf("render.pdf", listOf("Render me"))
        withDocument(uri) { document ->
            val page = document.renderer.openPage(0)
            try {
                val width = 360
                val height = (width * page.height.toFloat() / page.width).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                assertEquals(width, bitmap.width)
                assertTrue(bitmap.height > 0)
                var darkPixels = 0
                for (y in 0 until bitmap.height step 4) {
                    for (x in 0 until bitmap.width step 4) {
                        if (Color.luminance(bitmap.getPixel(x, y)) < 0.5f) darkPixels++
                    }
                }
                assertTrue("rendered page looks blank", darkPixels > 0)
                bitmap.recycle()
            } finally {
                page.close()
            }
        }
    }

    @Test
    fun everyPageOfAMultiPageDocumentRenders() {
        val uri = TestDocuments.writePdf("all-pages.pdf", (1..9).map { "Page $it" })
        withDocument(uri) { document ->
            for (index in 0 until document.pageCount) {
                val page = document.renderer.openPage(index)
                try {
                    val bitmap = Bitmap.createBitmap(80, 113, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.recycle()
                } finally {
                    page.close()
                }
            }
        }
    }

    @Test
    fun closedDocumentsRejectFurtherUse() {
        val uri = TestDocuments.writePdf("closed.pdf", listOf("x"))
        val document = PdfDocument.open(context, uri)
        document.close()
        // Closing is idempotent: the reader may close on pause and again on destroy.
        document.close()
        // A render after close must fail softly instead of throwing into the UI.
        val bitmap = Bitmap.createBitmap(80, 113, Bitmap.Config.ARGB_8888)
        assertFalse(document.renderPage(0, bitmap, 80, 113))
        bitmap.recycle()
    }

    @Test
    fun corruptFileIsReportedAsAnOpenFailure() {
        val uri = TestDocuments.writeRaw("corrupt.pdf", "%PDF-1.4 garbage".toByteArray())
        try {
            PdfDocument.open(context, uri)
            fail("expected a PdfOpenException")
        } catch (expected: PdfOpenException.Corrupt) {
            assertTrue(expected.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun missingFileIsReportedAsNotFound() {
        val uri = Uri.parse("file:///nonexistent/path/document.pdf")
        try {
            PdfDocument.open(context, uri)
            fail("expected NotFound")
        } catch (expected: PdfOpenException.NotFound) {
            assertTrue(expected.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun textCanBeExtractedFromAGeneratedDocument() {
        val uri = TestDocuments.writePdf("text.pdf", listOf("Extractable sentence", "Second page"))
        withDocument(uri) { document ->
            val pageText = document.pageText(0)
            val hits = document.search("Extractable", 10) { false }
            if (hits.isEmpty()) {
                val path = document.textPathForTest()
                val file = path?.let { java.io.File(it) }
                val head = file?.let { f -> runCatching { f.inputStream().use { input ->
                    val buffer = ByteArray(16)
                    val read = input.read(buffer)
                    String(buffer, 0, read.coerceAtLeast(0), Charsets.ISO_8859_1)
                } }.getOrNull() }
                fail(
                    "no hits for a generated PDF. " +
                        "uri=${document.source.uri} name=${document.source.displayName} " +
                        "hasTextLayer=${document.hasTextLayer} textPath=$path " +
                        "pathLength=${file?.length()} sourceLength=${document.source.size} " +
                        "pathHead=$head pageText0=${pageText?.let { "'" + it.take(120) + "'" }} " +
                        "parserOutput=${document.parsePageCountForTest()}",
                )
            }
            assertEquals(1, hits.size)
            assertEquals(0, hits.first().pageIndex)
        }
    }
}
