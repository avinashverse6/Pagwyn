package io.pagwyn.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Creates real PDF files on the device for instrumented tests.
 *
 * Files are shared through a test-only [FileProvider], so the reader receives a
 * real `content://` URI exactly as it does in production (Storage Access
 * Framework or an external `VIEW` intent).
 */
object TestDocuments {

    private const val AUTHORITY_SUFFIX = ".testprovider"

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun writePdf(name: String, pageTexts: List<String>): Uri =
        writeRaw(name, buildPdf(pageTexts))

    fun writeRaw(name: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "test-documents").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        // A persistable grant makes the URI behave like a SAF pick: the reader may
        // store it and reopen it later, and revoking it is testable.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        return uri
    }

    fun revokeAccess(uri: Uri): Boolean = runCatching {
        context.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Minimal, valid multi-page PDF with one line of text per page. */
    fun buildPdf(pageTexts: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

        write("%PDF-1.4\n")
        val pageObjects = pageTexts.indices.map { 4 + it * 2 }
        val offsets = HashMap<Int, Int>()

        fun objectAt(number: Int, body: String) {
            offsets[number] = out.size()
            write("$number 0 obj\n$body\nendobj\n")
        }

        objectAt(1, "<< /Type /Catalog /Pages 2 0 R >>")
        objectAt(
            2,
            "<< /Type /Pages /Count ${pageTexts.size} /Kids [" +
                pageObjects.joinToString(" ") { "$it 0 R" } + "] >>",
        )
        objectAt(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        pageTexts.forEachIndexed { index, text ->
            val pageNumber = pageObjects[index]
            val contentNumber = pageNumber + 1
            objectAt(
                pageNumber,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Resources << /Font << /F1 3 0 R >> >> /Contents $contentNumber 0 R >>",
            )
            // Uncompressed content stream: the text extractor is the subject of some
            // of these tests, so it must not depend on a filter implementation.
            val content = "BT /F1 14 Tf 60 780 Td ($text) Tj ET\n0 0 595 842 re S\n"
            val raw = content.toByteArray(Charsets.ISO_8859_1)
            offsets[contentNumber] = out.size()
            write("$contentNumber 0 obj\n<< /Length ${raw.size} >>\nstream\n")
            out.write(raw)
            write("\nendstream\nendobj\n")
        }

        val xref = out.size()
        val total = 3 + pageTexts.size * 2 + 1
        write("xref\n0 $total\n0000000000 65535 f \n")
        for (i in 1 until total) {
            write(String.format("%010d 00000 n \n", offsets[i] ?: 0))
        }
        write("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

}
