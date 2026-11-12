package io.pagwyn.reader

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.ui.reader.ReaderActivity
import io.pagwyn.reader.ui.reader.ReaderState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading through the real platform renderer.
 *
 * These tests exist because the parts Pagwyn depends on most — `PdfRenderer`, URI
 * grants, the activity lifecycle — cannot be exercised on the JVM. A regression
 * here would ship a reader that cannot open a document.
 */
@RunWith(AndroidJUnit4::class)
class ReaderInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = PagwynApplication.documents
    private val settings get() = PagwynApplication.settings

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

    /** Opens [uri] in the reader and runs [block] once the document is ready. */
    private fun openPdf(uri: Uri, block: (ActivityScenario<ReaderActivity>) -> Unit = {}) {
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, uri)).use { scenario ->
            instrumentation.waitForIdleSync()
            waitUntil { stateOf(scenario).let { it.pageCount > 0 || it.error != null } }
            block(scenario)
        }
    }

    private fun stateOf(scenario: ActivityScenario<ReaderActivity>): ReaderState {
        var snapshot: ReaderState? = null
        scenario.onActivity { snapshot = it.viewModelSnapshot() }
        return snapshot!!
    }

    /** Polls the UI thread until [condition] holds, so tests are not timing-fragile. */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    // ------------------------------------------------------------------ opening

    @Test
    fun opensARealPdfAndReportsItsPageCount() {
        val uri = TestDocuments.writePdf("twelve.pdf", (1..12).map { "Page $it" })
        openPdf(uri) { scenario ->
            val state = stateOf(scenario)
            assertEquals(12, state.pageCount)
            assertNull(state.error)
            assertEquals(0, state.currentPage)
            assertTrue(state.documentTitle.isNotBlank())
            assertTrue("subtitle should mention the page count", state.documentSubtitle.contains("12"))
        }
    }

    @Test
    fun recordsTheDocumentInTheLibrary() {
        val uri = TestDocuments.writePdf("recorded.pdf", listOf("first", "second"))
        openPdf(uri) {
            // The record is written on a background thread once the page count is known.
            waitUntil(10_000) { store.find(uri)?.pageCount == 2 }
            val document = store.find(uri)
            assertNotNull("opened document must be recorded", document)
            assertEquals(2, document!!.pageCount)
            assertEquals(uri.toString(), settings.lastDocumentUri)
        }
    }

    @Test
    fun navigatesToAPageAndPersistsThePosition() {
        val uri = TestDocuments.writePdf("nav.pdf", (1..20).map { "Page $it" })
        openPdf(uri) { scenario ->
            scenario.onActivity { it.jumpToPage(13) }
            waitUntil { stateOf(scenario).currentPage == 13 }
            scenario.onActivity { it.viewModelForTest().onLeaving() }

            waitUntil { store.find(uri)?.lastPage == 13 }
            assertEquals(13, store.find(uri)!!.lastPage)
        }
    }

    @Test
    fun resumesAtTheSavedPositionWhenReopened() {
        val uri = TestDocuments.writePdf("resume.pdf", (1..30).map { "Page $it" })
        openPdf(uri) { scenario ->
            scenario.onActivity { it.jumpToPage(21) }
            waitUntil { stateOf(scenario).currentPage == 21 }
            scenario.onActivity { it.viewModelForTest().onLeaving() }
            waitUntil { store.find(uri)?.lastPage == 21 }
        }

        openPdf(uri) { scenario ->
            assertEquals(21, stateOf(scenario).currentPage)
        }
    }

    @Test
    fun openingAtASpecificPageStartsThere() {
        val uri = TestDocuments.writePdf("jump.pdf", (1..10).map { "Page $it" })
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, uri, startPage = 7))
            .use { scenario ->
                instrumentation.waitForIdleSync()
                waitUntil { stateOf(scenario).pageCount > 0 }
                assertEquals(7, stateOf(scenario).currentPage)
            }
    }

    // ------------------------------------------------------------------ failure paths

    @Test
    fun showsAnErrorInsteadOfCrashingForANonPdf() {
        val uri = TestDocuments.writeRaw("broken.pdf", "definitely not a pdf".toByteArray())
        openPdf(uri) { scenario ->
            val state = stateOf(scenario)
            assertEquals(0, state.pageCount)
            assertNotNull("a corrupt file must produce an error state", state.error)
        }
    }

    @Test
    fun handlesAViewIntentFromAnotherApp() {
        val uri = TestDocuments.writePdf("external.pdf", listOf("Opened externally"))
        val intent = Intent(context, ReaderActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            waitUntil { stateOf(scenario).pageCount > 0 }
            assertEquals(1, stateOf(scenario).pageCount)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun reopeningManyDocumentsDoesNotLeakTheRenderer() {
        val uri = TestDocuments.writePdf("repeat.pdf", listOf("Again", "And again"))
        repeat(6) {
            openPdf(uri) { scenario ->
                assertEquals(2, stateOf(scenario).pageCount)
            }
        }
        // Every close must release its file descriptor and render thread; if not,
        // later documents would fail once descriptors ran out.
        val again = TestDocuments.writePdf("after-repeat.pdf", listOf("still works"))
        openPdf(again) { scenario -> assertEquals(1, stateOf(scenario).pageCount) }
    }

    @Test
    fun survivesConfigurationChangeWhileOpen() {
        val uri = TestDocuments.writePdf("rotate.pdf", (1..5).map { "Page $it" })
        openPdf(uri) { scenario ->
            scenario.onActivity { it.jumpToPage(3) }
            waitUntil { stateOf(scenario).currentPage == 3 }

            scenario.recreate()
            instrumentation.waitForIdleSync()
            waitUntil { stateOf(scenario).pageCount == 5 }

            val state = stateOf(scenario)
            assertNull(state.error)
            assertEquals(5, state.pageCount)
        }
    }

    // ------------------------------------------------------------------ features

    @Test
    fun searchFindsTextOnALaterPage() {
        val uri = TestDocuments.writePdf(
            "search.pdf",
            listOf("Introduction", "Chapter", "The quick brown fox jumps"),
        )
        openPdf(uri) { scenario ->
            scenario.onActivity { it.viewModelForTest().search("quick brown") }
            waitUntil { stateOf(scenario).searchResults.isNotEmpty() }
            assertEquals(2, stateOf(scenario).searchResults.first().pageIndex)
        }
    }

    @Test
    fun bookmarksToggleFromTheReader() {
        val uri = TestDocuments.writePdf("bookmark.pdf", listOf("a", "b"))
        openPdf(uri) { scenario ->
            scenario.onActivity { it.viewModelForTest().toggleBookmark() }
            waitUntil { store.bookmarks(uri).isNotEmpty() }
            assertEquals(listOf(0), store.bookmarks(uri))

            scenario.onActivity { it.viewModelForTest().toggleBookmark() }
            waitUntil { store.bookmarks(uri).isEmpty() }
            assertTrue(store.bookmarks(uri).isEmpty())
        }
    }

    @Test
    fun extractedTextMatchesThePageContent() {
        val uri = TestDocuments.writePdf("text.pdf", listOf("Selectable words", "Second page"))
        openPdf(uri) { scenario ->
            // Search uses the same text extractor as selection and copy.
            scenario.onActivity { it.viewModelForTest().search("Selectable") }
            waitUntil { stateOf(scenario).searchResults.isNotEmpty() }
            val hit = stateOf(scenario).searchResults.first()
            assertEquals(0, hit.pageIndex)
            assertTrue(hit.snippet.contains("Selectable", ignoreCase = true))
        }
    }
}
