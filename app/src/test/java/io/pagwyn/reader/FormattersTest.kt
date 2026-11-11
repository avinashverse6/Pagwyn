package io.pagwyn.reader

import io.pagwyn.reader.core.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun `file sizes use sensible units`() {
        assertEquals("0 B", Formatters.fileSize(0))
        assertEquals("512 B", Formatters.fileSize(512))
        assertEquals("1 KB", Formatters.fileSize(1024))
        assertEquals("1.5 KB", Formatters.fileSize(1536))
        assertEquals("1.0 MB", Formatters.fileSize(1024L * 1024L))
        assertEquals("2.5 MB", Formatters.fileSize((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.00 GB", Formatters.fileSize(1024L * 1024L * 1024L))
        assertEquals("—", Formatters.fileSize(-1))
    }

    @Test
    fun `relative time covers the whole range`() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", Formatters.relativeTime(now, now))
        assertEquals("5 min ago", Formatters.relativeTime(now - 5 * 60_000, now))
        assertEquals("2 hours ago", Formatters.relativeTime(now - 2 * 3_600_000, now))
        assertEquals("1 hour ago", Formatters.relativeTime(now - 3_600_000, now))
        assertEquals("3 days ago", Formatters.relativeTime(now - 3 * 86_400_000L, now))
        assertEquals("2 weeks ago", Formatters.relativeTime(now - 14 * 86_400_000L, now))
        // Older than a month falls back to an absolute date, which is never empty.
        assertTrue(Formatters.relativeTime(now - 90 * 86_400_000L, now).isNotBlank())
        assertEquals("—", Formatters.relativeTime(0, now))
    }

    @Test
    fun `yesterday is detected for a timestamp within the previous day`() {
        val now = 1_700_000_000_000L
        val oneDay = 86_400_000L
        assertEquals("Yesterday", Formatters.relativeTime(now - oneDay - 60_000, now))
    }

    @Test
    fun `page labels are one based`() {
        assertEquals("1 / 10", Formatters.pageLabel(0, 10))
        assertEquals("10 / 10", Formatters.pageLabel(9, 10))
    }

    @Test
    fun `percent is clamped and rounded`() {
        assertEquals(0, Formatters.percent(-1f))
        assertEquals(50, Formatters.percent(0.5f))
        assertEquals(100, Formatters.percent(4f))
        assertEquals(33, Formatters.percent(0.333f))
    }

    @Test
    fun `known paper sizes are recognised`() {
        assertTrue(Formatters.pageSize(595, 842).startsWith("A4"))
        assertTrue(Formatters.pageSize(612, 792).startsWith("US Letter"))
        assertTrue(Formatters.pageSize(842, 595).startsWith("A4"))
        // Landscape and portrait must both be recognised.
        assertTrue(Formatters.pageSize(419, 595).startsWith("A5"))
        // Unknown sizes still produce useful dimensions.
        val custom = Formatters.pageSize(1000, 1000)
        assertTrue(custom.contains("1000"))
        assertTrue(custom.contains("pt"))
    }

    @Test
    fun `title strips the pdf extension and handles missing names`() {
        assertEquals("Deep Work", Formatters.titleFromFileName("Deep Work.pdf"))
        assertEquals("Deep Work", Formatters.titleFromFileName("Deep Work.PDF"))
        assertEquals("Report", Formatters.titleFromFileName("folder/Report.pdf"))
        assertEquals("Untitled document", Formatters.titleFromFileName(null))
        assertEquals("Untitled document", Formatters.titleFromFileName("   "))
    }

    @Test
    fun `middle ellipsis keeps both ends intact`() {
        val long = "a-very-long-document-name-that-needs-shortening.pdf"
        val result = Formatters.middleEllipsis(long, 20)
        assertTrue(result.length <= 20)
        assertTrue(result.contains("…"))
        assertTrue(result.endsWith(".pdf"))
        // Short strings are returned untouched.
        assertEquals("short", Formatters.middleEllipsis("short", 20))
    }
}
