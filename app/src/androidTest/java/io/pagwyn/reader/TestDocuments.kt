package io.pagwyn.reader

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.Deflater

/**
 * Creates real PDF files on the device for instrumented tests.
 *
 * Files go through a [FileProvider] so the reader receives a `content://` URI, which
 * is exactly how documents arrive in production (Storage Access Framework or an
 * external `VIEW` intent).
 */
object TestDocuments {

    fun writePdf(context: Context, name: String, pageTexts: List<String>): Uri {
        val dir = File(context.cacheDir, "test-documents").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(buildPdf(pageTexts))
        return FileProvider.getUriForFile(context, "${context.packageName}.testprovider", file)
    }

    fun writeRaw(context: Context, name: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "test-documents").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.testprovider", file)
    }

    /** Minimal, valid single/multi page PDF with the given text per page. */
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
            val content = "BT /F1 14 Tf 60 780 Td ($text) Tj ET\n0 0 595 842 re S"
            val deflated = deflate(content.toByteArray(Charsets.ISO_8859_1))
            offsets[contentNumber] = out.size()
            write("$contentNumber 0 obj\n<< /Length ${deflated.size} /Filter /FlateDecode >>\nstream\n")
            out.write(deflated)
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

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream(data.size)
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val read = deflater.deflate(buffer)
            out.write(buffer, 0, read)
        }
        deflater.end()
        return out.toByteArray()
    }
}
