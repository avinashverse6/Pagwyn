package io.pagwyn.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pagwyn.reader.ui.reader.ReaderActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check that a real PDF opens in the reader.
 *
 * This is the test that would catch a regression in the platform rendering path:
 * it goes through a real `content://` URI, a real `PdfRenderer`, and the real
 * activity lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class ReaderLaunchTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun opensASinglePageDocument() {
        val uri = TestDocuments.writePdf(context, "one-page.pdf", listOf("Hello from Pagwyn"))
        val intent = ReaderActivity.intent(context, uri)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val state = activity.viewModelSnapshot()
                assertTrue("expected 1 page, was ${state.pageCount}", state.pageCount == 1)
                assertTrue("expected no error, was ${state.error}", state.error == null)
                assertTrue("expected a title", state.documentTitle.isNotBlank())
            }
        }
    }

    @Test
    fun opensAMultiPageDocumentAndReportsPageCount() {
        val pages = (1..12).map { "Page number $it" }
        val uri = TestDocuments.writePdf(context, "multi-page.pdf", pages)

        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, uri)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val state = activity.viewModelSnapshot()
                assertTrue("expected 12 pages, was ${state.pageCount}", state.pageCount == 12)
                assertTrue(state.currentPage == 0)
            }
        }
    }

    @Test
    fun reportsAnErrorForAMalformedDocumentInsteadOfCrashing() {
        val uri = TestDocuments.writeRaw(context, "broken.pdf", "not a pdf at all".toByteArray())

        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, uri)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val state = activity.viewModelSnapshot()
                assertTrue("expected an error state", state.error != null)
                assertTrue("expected no pages", state.pageCount == 0)
            }
        }
    }

    @Test
    fun survivesRepeatedOpenAndClose() {
        val uri = TestDocuments.writePdf(context, "repeat.pdf", listOf("Again", "And again"))
        repeat(5) {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, uri)).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(activity.viewModelSnapshot().pageCount == 2)
                }
            }
        }
    }

    @Test
    fun handlesAViewIntentFromAnotherApp() {
        val uri = TestDocuments.writePdf(context, "external.pdf", listOf("Opened externally"))
        val intent = Intent(context, ReaderActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.viewModelSnapshot().pageCount == 1)
            }
        }
    }
}
