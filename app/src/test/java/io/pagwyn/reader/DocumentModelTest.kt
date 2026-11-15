package io.pagwyn.reader

import android.net.Uri
import io.pagwyn.reader.core.DocumentSource
import io.pagwyn.reader.core.PagwynDocument
import io.pagwyn.reader.data.AppTheme
import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.ReaderTheme
import io.pagwyn.reader.data.ZoomMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentModelTest {

    private fun source(
        name: String = "Deep Work.pdf",
        size: Long = 4_400_000,
        location: String? = "Downloads",
    ) = DocumentSource(
        uri = Uri.parse("content://com.android.providers.downloads/document/42"),
        displayName = name,
        size = size,
        location = location,
        lastModified = 0,
        mimeType = "application/pdf",
    )

    @Test
    fun `create derives a readable title and identity`() {
        val doc = PagwynDocument.create(source(), pageCount = 300, now = 1000)
        assertEquals("Deep Work", doc.title)
        assertEquals(300, doc.pageCount)
        assertEquals(0, doc.lastPage)
        assertEquals(0f, doc.progress, 0.0001f)
        assertFalse(doc.isStarred)
        assertEquals("1 of 300", doc.pageSummary)
        assertEquals(1000, doc.addedAt)
    }

    @Test
    fun `page summary is safe when the page count is unknown`() {
        val doc = PagwynDocument.create(source(), pageCount = 0)
        assertEquals("—", doc.pageSummary)
    }

    @Test
    fun `uri round trips through the persisted string`() {
        val original = source().uri
        val doc = PagwynDocument.create(source(), pageCount = 10)
        assertEquals(original.toString(), doc.uri.toString())
    }

    @Test
    fun `enum conversions are total and default safely`() {
        assertEquals(ReadingMode.SINGLE_PAGE, ReadingMode.from(1))
        assertEquals(ReadingMode.CONTINUOUS, ReadingMode.from(99))
        assertEquals(ZoomMode.FIT_PAGE, ZoomMode.from(1))
        assertEquals(ZoomMode.FIT_WIDTH, ZoomMode.from(-3))
        assertEquals(ReaderTheme.SEPIA, ReaderTheme.from(1))
        assertEquals(ReaderTheme.FOLLOW_APP, ReaderTheme.from(42))
        assertEquals(AppTheme.DARK, AppTheme.from(2))
        assertEquals(AppTheme.SYSTEM, AppTheme.from(7))
    }

    @Test
    fun `stored enum values are stable`() {
        // These integers are persisted; changing them would silently reset
        // every existing user's preferences. Treated as a compatibility contract.
        assertEquals(0, ReadingMode.CONTINUOUS.storedValue)
        assertEquals(1, ReadingMode.SINGLE_PAGE.storedValue)
        assertEquals(0, ZoomMode.FIT_WIDTH.storedValue)
        assertEquals(1, ZoomMode.FIT_PAGE.storedValue)
        assertEquals(2, ZoomMode.ACTUAL_SIZE.storedValue)
        assertEquals(0, ReaderTheme.LIGHT.storedValue)
        assertEquals(1, ReaderTheme.SEPIA.storedValue)
        assertEquals(2, ReaderTheme.DARK.storedValue)
        assertEquals(3, ReaderTheme.FOLLOW_APP.storedValue)
    }

    @Test
    fun `document preserves reading position across copies`() {
        val doc = PagwynDocument.create(source(), pageCount = 100)
        val resumed = doc.copy(lastPage = 41, progress = 0.42f, isStarred = true)
        assertEquals(41, resumed.lastPage)
        assertTrue(resumed.isStarred)
        assertEquals("42 of 100", resumed.pageSummary)
    }
}
