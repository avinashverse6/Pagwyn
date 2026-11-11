package io.pagwyn.reader

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Builds real, byte-valid PDF files for tests.
 *
 * Hand-rolling PDFs (instead of shipping binary fixtures) keeps the repository
 * small and makes every test case readable: the expected text is right there in
 * the test, so a failure points at the parser rather than a mystery blob.
 */
object TestPdfs {

    /** A one-page PDF with a single line of text using FlateDecode. */
    fun simple(text: String = "Hello Pagwyn"): ByteArray {
        val content = "BT /F1 24 Tf 72 720 Td ($text) Tj ET"
        return build(
            pages = listOf(content),
            compress = true,
        )
    }

    /** Multi-page PDF, one line of text per page. */
    fun multiPage(vararg pageTexts: String): ByteArray =
        build(pages = pageTexts.map { text ->
            "BT /F1 12 Tf 72 720 Td ($text) Tj ET\n" +
                "0 0 612 792 re S"
        }, compress = true)

    /** Same as [simple] but with uncompressed content streams (tests the fallback). */
    fun uncompressed(text: String): ByteArray =
        build(pages = listOf("BT /F1 18 Tf 72 700 Td ($text) Tj ET"), compress = false)

    /** Two text runs on one page, which must be joined with a space. */
    fun twoRuns(first: String, second: String): ByteArray = build(
        pages = listOf(
            "BT /F1 12 Tf 72 720 Td ($first) Tj 0 -20 Td ($second) Tj ET",
        ),
        compress = true,
    )

    /** Text split across a TJ array with kerning, as most real PDFs do. */
    fun kerned(vararg chunks: String): ByteArray {
        val array = chunks.joinToString(" ") { "($it) " } + "-200 "
        return build(
            pages = listOf("BT /F1 12 Tf 72 720 Td [$array] TJ ET"),
            compress = true,
        )
    }

    /** Nothing but vector graphics: behaves like a scanned page (no text layer). */
    fun scannedLike(pageCount: Int = 2): ByteArray = build(
        pages = List(pageCount) { "0 0 612 792 re f" },
        compress = true,
    )

    // ------------------------------------------------------------------ builder

    private fun build(pages: List<String>, compress: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = IntArray(pages.size * 2 + 3)
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

        write("%PDF-1.7\n")
        write("%\u00e2\u00e3\u00cf\u00d3\n")

        // 1: catalog, 2: pages, 3: font, then page/content pairs.
        val pageObjectNumbers = pages.indices.map { 4 + it * 2 }

        offsets[0] = out.size()
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        offsets[1] = out.size()
        val kids = pageObjectNumbers.joinToString(" ") { "$it 0 R" }
        write("2 0 obj\n<< /Type /Pages /Count ${pages.size} /Kids [$kids] >>\nendobj\n")

        offsets[2] = out.size()
        write("3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        pages.forEachIndexed { index, content ->
            val pageNumber = pageObjectNumbers[index]
            val contentNumber = pageNumber + 1
            val body = content.toByteArray(Charsets.ISO_8859_1)
            val stream = if (compress) deflate(body) else body
            val filter = if (compress) " /Filter /FlateDecode" else ""

            offsets[pageNumber - 1] = out.size()
            write("$pageNumber 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ")
            write("/Resources << /Font << /F1 3 0 R >> >> /Contents $contentNumber 0 R >>\nendobj\n")

            offsets[contentNumber - 1] = out.size()
            write("$contentNumber 0 obj\n<< /Length ${stream.size}$filter >>\nstream\n")
            out.write(stream)
            write("\nendstream\nendobj\n")
        }

        val xrefOffset = out.size()
        val total = offsets.size + 1
        write("xref\n0 $total\n")
        write("0000000000 65535 f \n")
        for (i in 0 until total - 1) {
            write(String.format("%010d 00000 n \n", offsets[i]))
        }
        write("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val read = deflater.deflate(buffer)
            out.write(buffer, 0, read)
        }
        deflater.end()
        return out.toByteArray()
    }
}
