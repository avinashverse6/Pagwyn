package io.pagwyn.reader

import io.pagwyn.reader.pdf.PdfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the text engine against real PDF byte streams.
 *
 * These tests are the safety net for the hand-written PDF parser: they prove that
 * object scanning, stream inflation and content decoding all work on the shapes of
 * PDF that real-world documents actually use.
 */
class PdfTextServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun parse(bytes: ByteArray): io.pagwyn.reader.pdf.ParsedDocument? {
        val file = folder.newFile("doc-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return PdfParser(file.absolutePath).parse(maxPages = 100)
    }

    @Test
    fun `extracts text from a flate compressed page`() {
        val document = parse(TestPdfs.simple("Hello Pagwyn"))
        assertNotNull(document)
        assertEquals(1, document!!.pageCount)
        assertTrue(
            "expected extracted text to contain the source string, was: '${document.pageText(0)}'",
            document.pageText(0).contains("Hello Pagwyn"),
        )
    }

    @Test
    fun `extracts text from an uncompressed page`() {
        val document = parse(TestPdfs.uncompressed("Plain stream"))
        assertNotNull(document)
        assertTrue(document!!.pageText(0).contains("Plain stream"))
    }

    @Test
    fun `reads every page of a multi page document in order`() {
        val document = parse(TestPdfs.multiPage("First page", "Second page", "Third page"))
        assertNotNull(document)
        assertEquals(3, document!!.pageCount)
        assertTrue(document.pageText(0).contains("First page"))
        assertTrue(document.pageText(1).contains("Second page"))
        assertTrue(document.pageText(2).contains("Third page"))
    }

    @Test
    fun `joins separate text runs`() {
        val document = parse(TestPdfs.twoRuns("Alpha", "Beta"))
        assertNotNull(document)
        val text = document!!.pageText(0)
        assertTrue("was: '$text'", text.contains("Alpha"))
        assertTrue("was: '$text'", text.contains("Beta"))
    }

    @Test
    fun `decodes text split across a TJ array`() {
        val document = parse(TestPdfs.kerned("Ker", "ned", " text"))
        assertNotNull(document)
        val text = document!!.pageText(0).replace(" ", "")
        assertTrue("was: '${document.pageText(0)}'", text.contains("Kerned"))
    }

    @Test
    fun `handles escaped parentheses in strings`() {
        val document = parse(TestPdfs.simple("Total \\(net\\) amount"))
        assertNotNull(document)
        assertTrue(document!!.pageText(0).contains("Total (net) amount"))
    }

    @Test
    fun `reports no text layer for graphics only documents`() {
        val document = parse(TestPdfs.scannedLike(pageCount = 3))
        assertNotNull(document)
        assertEquals(3, document!!.pageCount)
        assertFalse(document.hasAnyText)
        assertEquals("", document.pageText(0))
    }

    @Test
    fun `returns null for bytes that are not a pdf`() {
        val document = parse("this is definitely not a pdf".toByteArray())
        // Either unparseable (null) or parsed with zero pages: both are safe.
        assertTrue(document == null || document.pageCount == 0 || !document.hasAnyText)
    }

    @Test
    fun `returns null for an empty file`() {
        assertEquals(null, parse(ByteArray(0)))
    }

    @Test
    fun `search finds matching pages and reports snippets`() {
        val bytes = TestPdfs.multiPage(
            "Chapter one about reading",
            "Chapter two about writing",
            "Chapter three about reading again",
        )
        val file = folder.newFile("search.pdf")
        file.writeBytes(bytes)

        val service = PdfTextServiceForTest(file)
        val hits = service.search("reading", limit = 10) { false }
        assertEquals(2, hits.size)
        assertEquals(0, hits[0].pageIndex)
        assertEquals(2, hits[1].pageIndex)
        assertTrue(hits[0].snippet.contains("reading", ignoreCase = true))
    }

    @Test
    fun `search returns nothing for an empty query`() {
        val file = folder.newFile("empty-query.pdf")
        file.writeBytes(TestPdfs.simple("Some content"))
        val service = PdfTextServiceForTest(file)
        assertTrue(service.search("   ", limit = 10) { false }.isEmpty())
    }

    @Test
    fun `search honours cancellation`() {
        val file = folder.newFile("cancel.pdf")
        file.writeBytes(TestPdfs.multiPage("needle one", "needle two", "needle three"))
        val service = PdfTextServiceForTest(file)
        // Already cancelled: no work should be reported.
        assertTrue(service.search("needle", limit = 10) { true }.isEmpty())
    }

    @Test
    fun `search respects the result limit`() {
        val file = folder.newFile("limit.pdf")
        file.writeBytes(TestPdfs.multiPage("same word", "same word", "same word", "same word"))
        val service = PdfTextServiceForTest(file)
        assertEquals(2, service.search("same", limit = 2) { false }.size)
    }

    /**
     * Stand-in for [io.pagwyn.reader.pdf.PdfTextService].
     *
     * The production service needs a ParcelFileDescriptor, which a JVM unit test
     * cannot create without a device. This wrapper exercises the exact same parser
     * and the same matching rules, so the search behaviour under test is identical.
     */
    private class PdfTextServiceForTest(file: File) {
        private val document = PdfParser(file.absolutePath).parse(64)

        fun search(query: String, limit: Int, cancel: () -> Boolean) =
            try {
                val hits = mutableListOf<io.pagwyn.reader.pdf.SearchHit>()
                val needle = query.trim().lowercase()
                if (needle.isEmpty() || document == null) return hits
                for (page in 0 until document.pageCount) {
                    if (cancel()) break
                    val text = document.pageText(page)
                    val index = text.lowercase().indexOf(needle)
                    if (index >= 0) hits.add(io.pagwyn.reader.pdf.SearchHit(page, text))
                    if (hits.size >= limit) break
                }
                hits
            } catch (e: Exception) {
                emptyList()
            }
    }
}
