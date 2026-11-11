package io.pagwyn.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pagwyn.reader.core.PagwynDocument
import io.pagwyn.reader.ui.home.HomeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library behaviour: documents are recorded when opened and their user state
 * (star, reading position) persists in the local database.
 */
@RunWith(AndroidJUnit4::class)
class HomeLibraryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store get() = PagwynApplication.documents
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun clean() {
        store.clearHistory()
    }

    @After
    fun tearDown() {
        store.clearHistory()
    }

    @Test
    fun openedDocumentIsRecordedInTheLibrary() {
        val uri = TestDocuments.writePdf(context, "library.pdf", listOf("Chapter one", "Chapter two"))

        ActivityScenario.launch<HomeActivity>(HomeActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> activity.openPdf(uri) }
        }

        // The reader records the document; give the IO work a moment to land.
        Thread.sleep(1200)
        val recorded = store.find(uri)
        assertNotNull("document should be recorded after opening", recorded)
        assertEquals("library", (recorded as PagwynDocument).title)
    }

    @Test
    fun starStatePersists() {
        val uri = TestDocuments.writePdf(context, "starred.pdf", listOf("Star me"))
        store.upsert(PagwynDocument.create(resolve(uri), pageCount = 1))

        store.setStarred(uri, true)
        assertTrue("star should be saved", store.find(uri)?.isStarred == true)

        store.setStarred(uri, false)
        assertFalse("unstar should be saved", store.find(uri)?.isStarred == true)
    }

    @Test
    fun readingProgressIsPersistedAndRestored() {
        val uri = TestDocuments.writePdf(context, "progress.pdf", listOf("One", "Two", "Three"))
        store.upsert(PagwynDocument.create(resolve(uri), pageCount = 3))

        store.saveProgress(
            uri = uri,
            page = 2,
            pageCount = 3,
            offsetPt = 0f,
            zoom = 1.5f,
            zoomMode = 2,
            rotation = 90,
        )

        val saved = store.find(uri)
        assertNotNull(saved)
        assertEquals(2, saved!!.lastPage)
        assertEquals(1.5f, saved.lastZoom, 0.001f)
        assertEquals(90, saved.lastRotation)
        assertTrue("progress should be recorded", saved.progress > 0.5f)
    }

    @Test
    fun bookmarksAreAddedAndRemoved() {
        val uri = TestDocuments.writePdf(context, "bookmarks.pdf", listOf("A", "B", "C"))
        store.upsert(PagwynDocument.create(resolve(uri), pageCount = 3))

        assertTrue(store.toggleBookmark(uri, 1))
        assertEquals(listOf(1), store.bookmarks(uri))

        store.toggleBookmark(uri, 2)
        assertEquals(listOf(1, 2), store.bookmarks(uri))

        store.toggleBookmark(uri, 1)
        assertEquals(listOf(2), store.bookmarks(uri))
    }

    @Test
    fun searchingTheLibraryFindsDocumentsByTitle() {
        val uri = TestDocuments.writePdf(context, "Architecture Notes.pdf", listOf("x"))
        store.upsert(PagwynDocument.create(resolve(uri), pageCount = 1))

        assertTrue(store.search("Architecture").any { it.uriString == uri.toString() })
        assertTrue(store.search("rchitec").any { it.uriString == uri.toString() })
        assertTrue(store.search("no-such-title").isEmpty())
    }

    @Test
    fun clearingHistoryRemovesDocumentsButNotFiles() {
        val uri = TestDocuments.writePdf(context, "cleanup.pdf", listOf("gone soon"))
        store.upsert(PagwynDocument.create(resolve(uri), pageCount = 1))
        store.addBookmark(uri, 0)

        store.clearHistory()

        assertTrue(store.documents().isEmpty())
        assertTrue(store.bookmarks(uri).isEmpty())
        // The file itself must still exist: Pagwyn never deletes user documents.
        assertTrue(
            context.contentResolver.openInputStream(uri)?.use { it.read() } != null,
        )
    }

    @Test
    fun removingOneDocumentLeavesOthers() {
        val keep = TestDocuments.writePdf(context, "keep.pdf", listOf("keep"))
        val drop = TestDocuments.writePdf(context, "drop.pdf", listOf("drop"))
        store.upsert(PagwynDocument.create(resolve(keep), pageCount = 1))
        store.upsert(PagwynDocument.create(resolve(drop), pageCount = 1))

        store.remove(drop)

        assertNotNull(store.find(keep))
        assertEquals(null, store.find(drop))
    }

    private fun resolve(uri: android.net.Uri) =
        io.pagwyn.reader.core.DocumentResolver.resolve(context, uri)
}
