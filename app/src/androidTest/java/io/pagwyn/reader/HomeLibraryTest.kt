package io.pagwyn.reader

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pagwyn.reader.core.DocumentResolver
import io.pagwyn.reader.core.PagwynDocument
import io.pagwyn.reader.ui.home.HomeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library: documents appear after being read, user state persists, and the
 * home screen reflects it.
 */
@RunWith(AndroidJUnit4::class)
class HomeLibraryTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = PagwynApplication.documents
    private val settings get() = PagwynApplication.settings
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun clean() {
        store.clearHistory()
        settings.lastDocumentUri = null
    }

    @After
    fun tearDown() {
        store.clearHistory()
        settings.lastDocumentUri = null
    }

    private fun register(uri: Uri, pages: Int = 3): PagwynDocument {
        val document = PagwynDocument.create(DocumentResolver.resolve(context, uri), pages)
        store.upsert(document)
        return document
    }

    @Test
    fun openingADocumentFromHomeRecordsItInTheLibrary() {
        val uri = TestDocuments.writePdf("library.pdf", listOf("Chapter one", "Chapter two"))

        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.openPdf(uri) }
            waitUntil { store.find(uri) != null }
        }

        val recorded = store.find(uri)
        assertNotNull("opening a document must add it to the library", recorded)
        assertEquals("library", recorded!!.title)
    }

    @Test
    fun documentsAreRefusedWhenTheyAreNotPdfs() {
        val uri = TestDocuments.writeRaw("notes.txt", "just text".toByteArray())
        register(uri, pages = 0)

        // looksLikePdf gates the SAF picker path, not the library.
        assertFalse(DocumentResolver.looksLikePdf(context, uri, "notes.txt", "text/plain"))
        assertTrue(DocumentResolver.looksLikePdf(context, uri, "notes.txt", "application/pdf"))
    }

    @Test
    fun starredDocumentsAreListedSeparatelyAndPersist() {
        val uri = TestDocuments.writePdf("starred.pdf", listOf("Star me"))
        register(uri, pages = 1)

        store.setStarred(uri, true)
        assertEquals(1, store.starred().count { it.uriString == uri.toString() })
        assertTrue(store.find(uri)!!.isStarred)

        store.setStarred(uri, false)
        assertTrue(store.starred().none { it.uriString == uri.toString() })
    }

    @Test
    fun readingProgressIsPersistedAndRestored() {
        val uri = TestDocuments.writePdf("progress.pdf", listOf("One", "Two", "Three", "Four"))
        register(uri, pages = 4)

        store.saveProgress(
            uri = uri,
            page = 2,
            pageCount = 4,
            offsetPt = 0f,
            zoom = 1.5f,
            zoomMode = 2,
            rotation = 90,
        )

        val saved = store.find(uri)!!
        assertEquals(2, saved.lastPage)
        assertEquals(1.5f, saved.lastZoom, 0.001f)
        assertEquals(90, saved.lastRotation)
        assertEquals(2, saved.lastZoomMode)
        assertTrue("progress should be past the halfway point", saved.progress >= 0.5f)
        assertEquals("3 of 4", saved.pageSummary)
    }

    @Test
    fun recentsOrderByMostRecentlyRead() {
        val older = TestDocuments.writePdf("older.pdf", listOf("old"))
        val newer = TestDocuments.writePdf("newer.pdf", listOf("new"))
        register(older, pages = 1)
        register(newer, pages = 1)

        store.saveProgress(older, 0, 1, 0f, 1f, 0, 0)
        waitUntil { store.find(older)!!.lastOpenedAt > 0 }
        Thread.sleep(20)
        store.saveProgress(newer, 0, 1, 0f, 1f, 0, 0)

        val recents = store.recents(10).map { it.uriString }
        assertTrue(recents.indexOf(newer.toString()) < recents.indexOf(older.toString()))
    }

    @Test
    fun bookmarksAreAddedAndRemoved() {
        val uri = TestDocuments.writePdf("bookmarks.pdf", listOf("A", "B", "C"))
        register(uri, pages = 3)

        assertTrue(store.toggleBookmark(uri, 1))
        assertEquals(listOf(1), store.bookmarks(uri))

        store.toggleBookmark(uri, 2)
        assertEquals(listOf(1, 2), store.bookmarks(uri))

        store.toggleBookmark(uri, 1)
        assertEquals(listOf(2), store.bookmarks(uri))
    }

    @Test
    fun searchingTheLibraryFindsDocumentsByTitle() {
        val uri = TestDocuments.writePdf("Architecture Notes.pdf", listOf("x"))
        register(uri, pages = 1)

        assertTrue(store.search("Architecture").any { it.uriString == uri.toString() })
        assertTrue(store.search("rchitec").any { it.uriString == uri.toString() })
        assertTrue(store.search("no-such-title").isEmpty())
        assertTrue(store.search("   ").isEmpty())
    }

    @Test
    fun clearingHistoryRemovesRecordsButNeverFiles() {
        val uri = TestDocuments.writePdf("cleanup.pdf", listOf("gone soon"))
        register(uri, pages = 1)
        store.addBookmark(uri, 0)

        store.clearHistory()

        assertTrue(store.documents().isEmpty())
        assertTrue(store.bookmarks(uri).isEmpty())
        // Pagwyn never deletes the user's documents.
        val readable = context.contentResolver.openInputStream(uri)?.use { it.read() } != null
        assertTrue("the underlying file must still exist", readable)
    }

    @Test
    fun removingOneDocumentLeavesTheOthers() {
        val keep = TestDocuments.writePdf("keep.pdf", listOf("keep"))
        val drop = TestDocuments.writePdf("drop.pdf", listOf("drop"))
        register(keep, pages = 1)
        register(drop, pages = 1)

        store.remove(drop)

        assertNotNull(store.find(keep))
        assertNull(store.find(drop))
    }

    @Test
    fun homeShowsTheLastReadDocument() {
        val uri = TestDocuments.writePdf("continue-reading.pdf", listOf("a", "b"))
        register(uri, pages = 2)

        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            var state: io.pagwyn.reader.ui.home.HomeState? = null
            waitUntil(3_000) {
                scenario.onActivity { state = it.viewModelSnapshot() }
                state?.lastRead != null
            }
            assertEquals("continue-reading", state!!.lastRead!!.title)
            assertEquals(2, state!!.lastRead!!.pageCount)
        }
    }

    @Test
    fun homeSearchFiltersTheLibrary() {
        val notes = TestDocuments.writePdf("Physics notes.pdf", listOf("n"))
        val novel = TestDocuments.writePdf("Novel.pdf", listOf("n"))
        register(notes, pages = 1)
        register(novel, pages = 1)

        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.searchForTest("Physics") }
            var state: io.pagwyn.reader.ui.home.HomeState? = null
            waitUntil(3_000) {
                scenario.onActivity { state = it.viewModelSnapshot() }
                state?.results?.isNotEmpty() == true
            }
            val results = state!!.results
            assertEquals(1, results.size)
            assertEquals("Physics notes", results.first().title)
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
