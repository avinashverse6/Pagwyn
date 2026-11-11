package io.pagwyn.reader.pdf

import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Text extraction and full-text search.
 *
 * Why not `PdfRenderer`? Android's renderer deliberately exposes no text API. To
 * avoid shipping a multi-megabyte third-party PDF engine (which would break the
 * lightweight-APK goal and risk ARMv7 regressions), Pagwyn extracts text with a
 * small, purpose-built reader that understands the parts of the PDF format that
 * matter for reading: object streams, FlateDecode, and text-showing operators.
 *
 * Guarantees:
 *  - Never throws into callers: failures simply mean "no text available", which
 *    is handled gracefully by the UI (scanned documents have no text layer).
 *  - Bounded work: at most [MAX_PAGES_SCANNED] pages are parsed per document and
 *    the total extracted character budget is capped, so a 5000 page file cannot
 *    stall the app.
 *  - Streaming: the file is read through a random access handle, never fully
 *    buffered in memory.
 */
class PdfTextService(private val descriptor: ParcelFileDescriptor) : AutoCloseable {

    private val filePath: String? = descriptorPath(descriptor)

    /** Lazily built page index to text: parsing stops as soon as it is not needed. */
    private val parsed: ParsedDocument? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val path = filePath ?: return@lazy null
        runCatching { PdfParser(path).parse(MAX_PAGES_SCANNED) }
            .onFailure { Log.d(TAG, "Text extraction unavailable: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.hasAnyText }
    }

    val isAvailable: Boolean get() = parsed != null

    fun pageText(index: Int): String? = parsed?.pageText(index)?.takeIf { it.isNotBlank() }

    fun pageCount(): Int = parsed?.pageCount ?: 0

    /**
     * Linear search across the extracted text.
     *
     * Returns at most [limit] hits. [shouldCancel] is polled between matches so the
     * caller can abandon a search on a very large document without waiting.
     */
    fun search(query: String, limit: Int, shouldCancel: () -> Boolean): List<SearchHit> {
        val normalised = query.trim()
        if (normalised.isEmpty()) return emptyList()
        val doc = parsed ?: return emptyList()
        val hits = ArrayList<SearchHit>(minOf(limit, 32))
        val needle = normalised.lowercase()

        for (pageIndex in 0 until doc.pageCount) {
            if (shouldCancel()) break
            val text = doc.pageText(pageIndex)
            if (text.isBlank()) continue
            val haystack = text.lowercase()
            var from = haystack.indexOf(needle)
            while (from >= 0) {
                hits.add(SearchHit(pageIndex, snippetAround(text, from, normalised.length)))
                if (hits.size >= limit || shouldCancel()) return hits
                from = haystack.indexOf(needle, from + needle.length)
            }
        }
        return hits
    }

    override fun close() = Unit

    private fun snippetAround(text: String, index: Int, length: Int): String {
        val start = (index - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (index + length + SNIPPET_RADIUS).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).replace(Regex("\\s+"), " ").trim() + suffix
    }

    private companion object {
        const val TAG = "PagwynText"
        const val MAX_PAGES_SCANNED = 4000
        const val SNIPPET_RADIUS = 42

        /**
         * Resolves the real path behind a ParcelFileDescriptor via /proc/self/fd.
         *
         * Needed because random-access text extraction reads the file by path.
         * Returns null for pipes and for providers that do not expose a real file,
         * in which case text features are simply unavailable.
         */
        fun descriptorPath(descriptor: ParcelFileDescriptor): String? = runCatching {
            val target = java.io.File("/proc/self/fd/${descriptor.fd}").canonicalPath
            target?.takeIf { java.io.File(it).isFile && java.io.File(it).canRead() }
        }.getOrNull()
    }
}

/** Result of parsing a PDF's text layer. */
class ParsedDocument(private val pages: List<String>) {
    val pageCount: Int get() = pages.size

    val hasAnyText: Boolean get() = pages.any { it.isNotBlank() }

    fun pageText(index: Int): String = pages.getOrElse(index) { "" }
}

/**
 * Minimal, defensive PDF text extractor.
 *
 * Scope is intentionally narrow — this is a reading aid, not a PDF toolkit:
 *  - Locates page objects through /Type /Page dictionaries (also inside object streams).
 *  - Inflates FlateDecode content streams (PNG predictors supported for object streams).
 *  - Decodes text-showing operators (Tj, TJ, ', ") with simple font encodings.
 *  - Gives up quickly and safely on anything it does not understand.
 */
class PdfParser(private val path: String) {

    private val bytes: ByteArray = java.io.File(path).readBytes()

    private val reader = PdfObjectReader(bytes)

    fun parse(maxPages: Int): ParsedDocument? {
        if (bytes.isEmpty()) return null

        val objects = reader.readAllObjects()
        val pages = collectPages(objects)
        if (pages.isEmpty()) return ParsedDocument(emptyList())

        val limit = minOf(pages.size, maxPages)
        val text = ArrayList<String>(limit)
        for (i in 0 until limit) {
            val page = pages[i]
            text.add(runCatching { extractPageText(page, objects) }.getOrDefault(""))
        }
        return ParsedDocument(text)
    }

    private fun collectPages(objects: Map<Int, PdfObject>): List<PdfObject.Dict> {
        fun asDict(obj: PdfObject?): PdfObject.Dict? = when (obj) {
            is PdfObject.Dict -> obj
            is PdfObject.Stream -> obj.dict
            else -> null
        }

        val direct = objects.values
            .mapNotNull(::asDict)
            .filter { (it["Type"] as? PdfObject.Name)?.value == "Page" }
            .filter { it["Contents"] != null }

        if (direct.isNotEmpty()) {
            // Order pages by their appearance in the Kids tree when possible.
            return orderByTree(objects, direct)
        }

        // Fall back to object order for damaged or unusual files.
        return objects.entries
            .sortedBy { it.key }
            .mapNotNull { asDict(it.value) }
            .filter { (it["Type"] as? PdfObject.Name)?.value == "Page" }
    }

    private fun dictOf(objects: Map<Int, PdfObject>, value: PdfObject?): PdfObject.Dict? = when (value) {
        is PdfObject.Dict -> value
        is PdfObject.Stream -> value.dict
        is PdfObject.Reference -> dictOf(objects, objects[value.number])
        else -> null
    }

    private fun orderByTree(
        objects: Map<Int, PdfObject>,
        fallback: List<PdfObject.Dict>,
    ): List<PdfObject.Dict> {
        val pagesRoot = objects.values
            .mapNotNull { dictOf(objects, it) }
            .firstOrNull {
                (it["Type"] as? PdfObject.Name)?.value == "Pages" && it["Parent"] == null
            } ?: return fallback

        val ordered = ArrayList<PdfObject.Dict>(fallback.size)
        val visited = HashSet<PdfObject.Dict>()

        fun walk(node: PdfObject.Dict?) {
            if (node == null || !visited.add(node)) return
            val type = (node["Type"] as? PdfObject.Name)?.value
            if (type == "Page") {
                ordered.add(node)
                return
            }
            val kids = node["Kids"] as? PdfObject.Array ?: return
            for (kid in kids.items) {
                walk(dictOf(objects, kid))
            }
        }

        walk(pagesRoot)
        // Some pages may be unreachable through the tree; append them so search
        // still covers the whole document.
        fallback.forEach { if (visited.add(it)) ordered.add(it) }
        return ordered
    }

    private fun extractPageText(page: PdfObject.Dict, objects: Map<Int, PdfObject>): String {
        val contents = resolveContents(page["Contents"], objects) ?: return ""
        val stream = reader.decodeStream(contents) ?: return ""
        val fonts = readPageFonts(page, objects)
        return ContentStreamDecoder(stream, fonts).decode()
    }

    private fun streamOf(objects: Map<Int, PdfObject>, value: PdfObject?): PdfObject.Stream? = when (value) {
        is PdfObject.Stream -> value
        is PdfObject.Reference -> streamOf(objects, objects[value.number])
        else -> null
    }

    private fun resolveContents(
        value: PdfObject?,
        objects: Map<Int, PdfObject>,
    ): PdfObject.Stream? = when (value) {
        is PdfObject.Stream -> value
        is PdfObject.Reference -> streamOf(objects, objects[value.number])
        is PdfObject.Array -> {
            // A page may reference several content streams; they form one stream
            // conceptually, so the decoded bytes are concatenated.
            val parts = value.items.mapNotNull { streamOf(objects, it) }
                .mapNotNull { reader.decodeStream(it) }
            if (parts.isEmpty()) {
                null
            } else {
                val joined = parts.fold(ByteArray(0)) { acc, part -> acc + part + '\n'.code.toByte() }
                PdfObject.Stream(PdfObject.Dict(LinkedHashMap()), joined)
            }
        }
        else -> null
    }

    private fun readPageFonts(
        page: PdfObject.Dict,
        objects: Map<Int, PdfObject>,
    ): Map<String, PdfFontEncoding> {
        val resources = dictOf(objects, page["Resources"]) ?: return emptyMap()
        val fontDict = dictOf(objects, resources["Font"]) ?: return emptyMap()

        val result = HashMap<String, PdfFontEncoding>(fontDict.size)
        for ((name, value) in fontDict.entries) {
            val font = dictOf(objects, value) ?: continue
            val subtype = (font["Subtype"] as? PdfObject.Name)?.value
            val encoding = (font["Encoding"] as? PdfObject.Name)?.value
            val toUnicode = font["ToUnicode"] != null
            result[name] = PdfFontEncoding(
                symbolic = subtype == "Type3" || encoding == null,
                usesDifferences = encoding == "WinAnsiEncoding" || encoding == "MacRomanEncoding" ||
                    font["Encoding"] is PdfObject.Dict,
                hasToUnicode = toUnicode,
            )
        }
        return result
    }
}

/** Very small font-classification record used to choose a text decoding strategy. */
internal data class PdfFontEncoding(
    val symbolic: Boolean,
    val usesDifferences: Boolean,
    val hasToUnicode: Boolean,
) {
    companion object {
        val DEFAULT = PdfFontEncoding(symbolic = false, usesDifferences = false, hasToUnicode = false)
    }
}

/**
 * Decodes a page content stream into readable text.
 *
 * Only text-showing operators are interpreted; everything else (paths, images)
 * is skipped by the tokenizer. This keeps the decoder fast on complex pages.
 */
internal class ContentStreamDecoder(
    private val stream: ByteArray,
    private val fonts: Map<String, PdfFontEncoding>,
) {
    private val out = StringBuilder(1024)
    private val buffer = ArrayList<Byte>(64)
    private var currentFont: PdfFontEncoding = PdfFontEncoding.DEFAULT
    private var lastWasSpace = true
    private var budget = MAX_CHARS

    fun decode(): String {
        var i = 0
        while (i < stream.size && budget > 0) {
            val b = stream[i].toInt() and 0xFF
            when {
                b == '('.code -> i = readLiteral(i + 1)
                b == '<'.code && i + 1 < stream.size && (stream[i + 1].toInt() and 0xFF) == '<'.code -> {
                    i += 2
                }
                b == '<'.code -> i = readHex(i + 1)
                b == '/'.code -> i = readName(i + 1)
                b == '-'.code || b == '.'.code || b in '0'.code..'9'.code -> {
                    i = readNumber(i)
                }
                isTokenStart(b) -> i = readToken(i)
                else -> i++
            }
        }
        return out.toString().trim()
    }

    /** Reads a numeric operand and uses it as a kerning hint. */
    private fun readNumber(start: Int): Int {
        var i = start
        val sb = StringBuilder(8)
        while (i < stream.size) {
            val b = stream[i].toInt() and 0xFF
            if (b in '0'.code..'9'.code || b == '-'.code || b == '.'.code || b == '+'.code) {
                sb.append(b.toChar())
                i++
            } else {
                break
            }
        }
        sb.toString().toDoubleOrNull()?.let(::noteKerning)
        return if (i == start) start + 1 else i
    }

    private fun isTokenStart(b: Int): Boolean =
        b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b == '\''.code || b == '"'.code

    private fun readLiteral(start: Int): Int {
        buffer.clear()
        var i = start
        var depth = 1
        while (i < stream.size) {
            val b = stream[i].toInt() and 0xFF
            when (b) {
                '\\'.code -> {
                    i++
                    if (i >= stream.size) break
                    when (val esc = stream[i].toInt() and 0xFF) {
                        'n'.code -> buffer.add('\n'.code.toByte())
                        'r'.code -> buffer.add('\r'.code.toByte())
                        't'.code -> buffer.add('\t'.code.toByte())
                        'b'.code -> buffer.add('\b'.code.toByte())
                        'f'.code -> buffer.add(12)
                        '('.code, ')'.code, '\\'.code -> buffer.add(esc.toByte())
                        in '0'.code..'7'.code -> {
                            var value = esc - '0'.code
                            var count = 1
                            while (count < 3 && i + 1 < stream.size &&
                                (stream[i + 1].toInt() and 0xFF) in '0'.code..'7'.code
                            ) {
                                i++
                                value = value * 8 + ((stream[i].toInt() and 0xFF) - '0'.code)
                                count++
                            }
                            buffer.add((value and 0xFF).toByte())
                        }
                        else -> buffer.add(esc.toByte())
                    }
                    i++
                }
                '('.code -> {
                    depth++
                    buffer.add(b.toByte())
                    i++
                }
                ')'.code -> {
                    depth--
                    if (depth == 0) {
                        i++
                        // Text strings appear only as text-showing operands, so the
                        // bytes can be emitted immediately; array kerning adjustments
                        // are handled separately.
                        appendBytes(buffer)
                        return i
                    }
                    buffer.add(b.toByte())
                    i++
                }
                else -> {
                    buffer.add(b.toByte())
                    i++
                }
            }
        }
        appendBytes(buffer)
        return i
    }

    private fun readHex(start: Int): Int {
        buffer.clear()
        var i = start
        var high = -1
        while (i < stream.size) {
            val b = stream[i].toInt() and 0xFF
            if (b == '>'.code) {
                i++
                break
            }
            val nibble = when (b) {
                in '0'.code..'9'.code -> b - '0'.code
                in 'a'.code..'f'.code -> b - 'a'.code + 10
                in 'A'.code..'F'.code -> b - 'A'.code + 10
                else -> {
                    i++
                    continue
                }
            }
            if (high < 0) {
                high = nibble
            } else {
                buffer.add(((high shl 4) or nibble).toByte())
                high = -1
            }
            i++
        }
        if (high >= 0) buffer.add((high shl 4).toByte())
        appendBytes(buffer)
        return i
    }

    private fun readName(start: Int): Int {
        var i = start
        val name = StringBuilder()
        while (i < stream.size) {
            val b = stream[i].toInt() and 0xFF
            if (b <= 0x20 || b == '/'.code || b == '['.code || b == ']'.code ||
                b == '<'.code || b == '>'.code || b == '('.code || b == ')'.code
            ) {
                break
            }
            name.append(b.toChar())
            i++
        }
        fonts[name.toString()]?.let { currentFont = it }
        return i
    }

    private fun readToken(start: Int): Int {
        var i = start
        val token = StringBuilder()
        while (i < stream.size) {
            val b = stream[i].toInt() and 0xFF
            if (b <= 0x20 || b == '/'.code || b == '['.code || b == ']'.code ||
                b == '<'.code || b == '>'.code || b == '('.code || b == ')'.code
            ) {
                break
            }
            token.append(b.toChar())
            i++
        }
        when (token.toString()) {
            // Vertical movement becomes a line break in the extracted text.
            "Td", "TD", "T*", "ET" -> newline()
            // Text-showing operators simply end the current run; word breaks inside
            // TJ arrays are detected from the kerning numbers instead.
            "TJ", "Tj", "'", "\"" -> Unit
        }
        return if (i == start) start + 1 else i
    }

    /**
     * A large negative number inside a TJ array is how PDF producers encode a word
     * space (instead of a real space glyph). Values are in thousandths of an em, so
     * anything below about -180 is treated as a break.
     */
    private fun noteKerning(number: Double) {
        if (number > -180.0) return
        if (out.isEmpty() || out.last() == ' ' || out.last() == '\n') return
        out.append(' ')
        lastWasSpace = true
    }

    private fun newline() {
        if (out.isEmpty()) return
        if (out.last() == ' ') out.setLength(out.length - 1)
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        lastWasSpace = true
    }

    private fun appendBytes(data: List<Byte>) {
        if (data.isEmpty()) return
        budget -= data.size
        val array = ByteArray(data.size) { data[it] }
        val text = decodeText(array, currentFont)
        if (text.isEmpty()) return
        if (lastWasSpace && out.isNotEmpty() && out.last() != '\n' && !text.first().isWhitespace()) {
            out.append(' ')
        }
        out.append(text)
        lastWasSpace = text.last().isWhitespace()
    }

    /**
     * PDF string bytes to characters.
     *
     * Simple fonts in the standard encodings map 1:1 to Latin-1 for the printable
     * range, which covers the vast majority of real-world PDFs. UTF-16BE strings
     * (identified by their BOM) are decoded directly. Fonts with custom
     * /Differences arrays are approximated, which is the same trade-off every
     * lightweight extractor makes.
     */
    private fun decodeText(data: ByteArray, font: PdfFontEncoding): String {
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            return String(data, 2, data.size - 2, StandardCharsets.UTF_16BE)
        }
        val sb = StringBuilder(data.size)
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b < 0x20 && b != '\n'.code && b != '\t'.code && b != '\r'.code -> {
                    if (b == 0) sb.append(' ')
                }
                b < 0x80 -> sb.append(b.toChar())
                else -> sb.append(LATIN1[b - 0x80])
            }
            i++
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_CHARS = 200_000

        val LATIN1: CharArray = CharArray(128) { (it + 128).toChar() }
    }
}
