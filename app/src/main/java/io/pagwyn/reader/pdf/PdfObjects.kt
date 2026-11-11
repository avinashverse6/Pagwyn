package io.pagwyn.reader.pdf

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Minimal PDF object model: only what text extraction needs. */
internal sealed class PdfObject {
    data class Name(val value: String) : PdfObject()
    data class Num(val value: Double) : PdfObject()
    class Str(val bytes: ByteArray) : PdfObject()
    class Array(val items: List<PdfObject>) : PdfObject()
    data class Reference(val number: Int) : PdfObject()
    class Dict(val entries: MutableMap<String, PdfObject>) : PdfObject() {
        operator fun get(key: String): PdfObject? = entries[key]
        val size: Int get() = entries.size
    }
    class Stream(
        val dict: Map<String, PdfObject>,
        val raw: ByteArray,
        val extras: Map<String, PdfObject>,
    ) : PdfObject() {
        operator fun get(key: String): PdfObject? = dict[key] ?: extras[key]
    }
    object Null : PdfObject()
}

/**
 * Reads the cross-reference-free "object stream aware" subset of a PDF file.
 *
 * Rather than trusting xref tables (which are frequently broken in the wild),
 * this reader scans the file for `N G obj … endobj` boundaries. That is more
 * forgiving, and fast enough because the scan is a single linear pass over the
 * raw bytes with a cheap pattern check.
 */
internal class PdfObjectReader(private val data: ByteArray) {

    /** Reads every top-level object it can find, keyed by object number. */
    fun readAllObjects(): Map<Int, PdfObject.Dict> {
        val result = HashMap<Int, PdfObject.Dict>()
        val text = data
        var i = 0
        while (i < text.size) {
            val objStart = findObjectStart(i) ?: break
            val (number, bodyStart) = objStart
            val end = findEndObject(bodyStart)
            val dict = runCatching { parseIndirectObject(bodyStart, end) }.getOrNull()
            if (dict != null) {
                if (result[number] == null) result[number] = dict
                // Object streams carry more dictionaries: unroll them.
                unrollObjectStream(dict)?.forEach { (n, d) ->
                    if (result[n] == null) result[n] = d
                }
            }
            i = if (end > bodyStart) end else i + 1
        }
        return result
    }

    private fun findObjectStart(from: Int): Pair<Int, Int>? {
        var i = from
        while (i < data.size - 7) {
            if (data[i] == 'o'.code.toByte() && data[i + 1] == 'b'.code.toByte() &&
                data[i + 2] == 'j'.code.toByte() &&
                (i == 0 || data[i - 1] <= 0x20) &&
                (i + 3 < data.size && (data[i + 3] < 0x30 || data[i + 3] > 0x39))
            ) {
                val number = readObjectNumberBackwards(i)
                if (number != null) return number to (i + 3)
            }
            i++
        }
        return null
    }

    /** Object numbers are 1-6 decimal digits immediately before " obj". */
    private fun readObjectNumberBackwards(objKeyword: Int): Int? {
        var i = objKeyword - 1
        // Skip whitespace between the number and the "obj" keyword.
        while (i >= 0 && (data[i].toInt() and 0xFF) <= 0x20) i--
        // Skip the generation number and read the object number before it.
        while (i >= 0 && (data[i].toInt() and 0xFF) in '0'.code..'9'.code) i--
        while (i >= 0 && (data[i].toInt() and 0xFF) <= 0x20) i--
        val end = i + 1
        var start = end
        while (start > 0 && (data[start - 1].toInt() and 0xFF) in '0'.code..'9'.code) start--
        if (start == end || end - start > 6) return null
        return String(data, start, end - start, StandardCharsets.ISO_8859_1).toIntOrNull()
    }

    private fun findEndObject(from: Int): Int {
        var i = from
        while (i < data.size - 6) {
            if (data[i] == 'e'.code.toByte() && data[i + 1] == 'n'.code.toByte() &&
                data[i + 2] == 'd'.code.toByte() && data[i + 3] == 'o'.code.toByte() &&
                data[i + 4] == 'b'.code.toByte() && data[i + 5] == 'j'.code.toByte()
            ) {
                return i + 6
            }
            if (data[i] == 's'.code.toByte() && data[i + 1] == 't'.code.toByte() &&
                data[i + 2] == 'r'.code.toByte() && data[i + 3] == 'e'.code.toByte() &&
                data[i + 4] == 'a'.code.toByte() && data[i + 5] == 'm'.code.toByte()
            ) {
                // Skip the binary stream payload entirely: scanning it blindly can
                // produce false positives and is slow.
                val length = streamLengthAfter(i)
                if (length != null && length > 0) {
                    val after = i + 6 + skipEol(i + 6) + length
                    if (after < data.size) {
                        i = after
                        continue
                    }
                }
            }
            i++
        }
        return i
    }

    private fun skipEol(at: Int): Int {
        var i = at
        if (i < data.size && data[i] == '\r'.code.toByte()) i++
        if (i < data.size && data[i] == '\n'.code.toByte()) i++
        return i - at
    }

    /** Reads /Length from the dictionary preceding a stream keyword. */
    private fun streamLengthAfter(streamKeyword: Int): Int? {
        val dictStart = findDictStartBefore(streamKeyword) ?: return null
        val dictText = String(data, dictStart, streamKeyword - dictStart, StandardCharsets.ISO_8859_1)
        val match = LENGTH_PATTERN.find(dictText) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** Finds the "<<" that opens the dictionary directly before a stream keyword. */
    private fun findDictStartBefore(at: Int): Int? {
        var i = at - 1
        while (i > 1) {
            if (data[i] == '<'.code.toByte() && data[i - 1] == '<'.code.toByte()) {
                return i - 1
            }
            i--
        }
        return null
    }

    private fun parseIndirectObject(start: Int, end: Int): PdfObject.Dict? {
        val parser = ValueParser(data, start, end)
        parser.skipWhitespace()
        val value = parser.parseValue()
        return when (value) {
            is PdfObject.Dict -> {
                parser.skipWhitespace()
                if (parser.consumeKeyword("stream")) {
                    val streamStart = parser.streamDataStart()
                    val length = (value["Length"] as? PdfObject.Num)?.value?.toInt()
                        ?: (end - streamStart)
                    val raw = data.copyOfRange(
                        streamStart,
                        (streamStart + length).coerceAtMost(end).coerceAtLeast(streamStart),
                    )
                    PdfObject.Stream(value.entries, raw, emptyMap())
                } else {
                    value
                }
            }
            is PdfObject.Stream -> value
            else -> null
        } as? PdfObject.Dict
    }

    /** Expands /Type /ObjStm containers into their member dictionaries. */
    private fun unrollObjectStream(stream: PdfObject.Dict): Map<Int, PdfObject.Dict>? {
        val s = stream as? PdfObject.Stream ?: return null
        val type = (s["Type"] as? PdfObject.Name)?.value
        if (type != "ObjStm") return null
        val decoded = decodeStream(s) ?: return null
        val count = (s["N"] as? PdfObject.Num)?.value?.toInt() ?: return null
        val first = (s["First"] as? PdfObject.Num)?.value?.toInt() ?: return null

        val header = String(decoded, 0, first.coerceAtMost(decoded.size), StandardCharsets.ISO_8859_1)
        val numbers = header.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
        if (numbers.size < count * 2) return null

        val out = HashMap<Int, PdfObject.Dict>(count)
        for (i in 0 until count) {
            val objNumber = numbers.getOrNull(i * 2) ?: continue
            val offset = numbers.getOrNull(i * 2 + 1) ?: continue
            val start = first + offset
            if (start >= decoded.size) continue
            val nextOffset = numbers.getOrNull(i * 2 + 3)
            val end = if (nextOffset != null) first + nextOffset else decoded.size
            val parser = ValueParser(decoded, start, end.coerceAtMost(decoded.size))
            val value = runCatching { parser.parseValue() }.getOrNull()
            if (value is PdfObject.Dict) out[objNumber] = value
        }
        return out
    }

    /** Decodes FlateDecode streams, with PNG/TIFF predictor support. */
    fun decodeStream(stream: PdfObject.Stream): ByteArray? {
        val filters = when (val f = stream["Filter"]) {
            is PdfObject.Name -> listOf(f.value)
            is PdfObject.Array -> f.items.mapNotNull { (it as? PdfObject.Name)?.value }
            else -> emptyList()
        }
        var current: ByteArray = stream.raw
        for (filter in filters) {
            current = when (filter) {
                "FlateDecode", "Fl" -> inflate(current) ?: return null
                "ASCIIHexDecode", "AHx" -> asciiHex(current)
                "ASCII85Decode", "A85" -> ascii85(current) ?: return null
                else -> return null // Unknown filter: give up rather than emit garbage.
            }
        }
        val predictor = (stream["DecodeParms"] as? PdfObject.Dict)
            ?: ((stream["DecodeParms"] as? PdfObject.Array)?.items?.firstOrNull() as? PdfObject.Dict)
        return if (predictor != null) {
            applyPredictor(current, predictor) ?: current
        } else {
            current
        }
    }

    private fun asciiHex(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size / 2 + 1)
        var high = -1
        for (byte in input) {
            val b = byte.toInt() and 0xFF
            if (b == '>'.code) break
            val nibble = when (b) {
                in '0'.code..'9'.code -> b - '0'.code
                in 'a'.code..'f'.code -> b - 'a'.code + 10
                in 'A'.code..'F'.code -> b - 'A'.code + 10
                else -> continue
            }
            if (high < 0) high = nibble else {
                out.write((high shl 4) or nibble)
                high = -1
            }
        }
        return out.toByteArray()
    }

    private fun ascii85(input: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(input.size)
        var tuple = 0
        var count = 0
        var i = 0
        // Skip leading "<~" if present.
        if (input.size > 1 && input[0] == '<'.code.toByte() && input[1] == '~'.code.toByte()) i = 2
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            if (b == '~'.code) break
            if (b == 'z'.code && count == 0) {
                repeat(4) { out.write(0) }
                i++
                continue
            }
            if (b in '!'.code..'u'.code) {
                tuple = tuple * 85 + (b - '!'.code)
                if (++count == 5) {
                    out.write((tuple shr 24) and 0xFF)
                    out.write((tuple shr 16) and 0xFF)
                    out.write((tuple shr 8) and 0xFF)
                    out.write(tuple and 0xFF)
                    tuple = 0
                    count = 0
                }
            }
            i++
        }
        if (count > 0) {
            repeat(5 - count) { tuple = tuple * 85 + 84 }
            repeat(count - 1) { shift ->
                out.write((tuple shr (24 - shift * 8)) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun inflate(input: ByteArray): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(input.size * 3)
            val buffer = ByteArray(16 * 1024)
            var stalled = 0
            while (!inflater.finished()) {
                val read = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    return out.toByteArray().takeIf { it.isNotEmpty() }
                }
                if (read > 0) {
                    out.write(buffer, 0, read)
                    stalled = 0
                    if (out.size() > MAX_DECODED) return out.toByteArray()
                } else {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    if (++stalled > 4) break
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun applyPredictor(input: ByteArray, parms: PdfObject.Dict): ByteArray? {
        val predictor = (parms["Predictor"] as? PdfObject.Num)?.value?.toInt() ?: 1
        if (predictor < 10) return input
        val colors = (parms["Colors"] as? PdfObject.Num)?.value?.toInt() ?: 1
        val bits = (parms["BitsPerComponent"] as? PdfObject.Num)?.value?.toInt() ?: 8
        val columns = (parms["Columns"] as? PdfObject.Num)?.value?.toInt() ?: 1
        val bytesPerPixel = (colors * bits + 7) / 8
        val rowLength = (columns * colors * bits + 7) / 8
        if (rowLength <= 0) return input

        val out = ByteArrayOutputStream(input.size)
        var previous = ByteArray(rowLength)
        var pos = 0
        while (pos + 1 + rowLength <= input.size) {
            val filterType = input[pos].toInt() and 0xFF
            val row = input.copyOfRange(pos + 1, pos + 1 + rowLength)
            when (filterType) {
                0 -> Unit
                1 -> for (i in bytesPerPixel until rowLength) {
                    row[i] = (row[i] + row[i - bytesPerPixel]).toByte()
                }
                2 -> for (i in 0 until rowLength) {
                    row[i] = (row[i] + previous[i]).toByte()
                }
                3 -> for (i in 0 until rowLength) {
                    val left = if (i >= bytesPerPixel) row[i - bytesPerPixel].toInt() and 0xFF else 0
                    row[i] = (row[i] + ((left + (previous[i].toInt() and 0xFF)) / 2)).toByte()
                }
                4 -> for (i in 0 until rowLength) {
                    val a = if (i >= bytesPerPixel) row[i - bytesPerPixel].toInt() and 0xFF else 0
                    val b = previous[i].toInt() and 0xFF
                    val c = if (i >= bytesPerPixel) previous[i - bytesPerPixel].toInt() and 0xFF else 0
                    val p = a + b - c
                    val pa = kotlin.math.abs(p - a)
                    val pb = kotlin.math.abs(p - b)
                    val pc = kotlin.math.abs(p - c)
                    val predicted = when {
                        pa <= pb && pa <= pc -> a
                        pb <= pc -> b
                        else -> c
                    }
                    row[i] = (row[i] + predicted).toByte()
                }
                else -> return null
            }
            out.write(row)
            previous = row
            pos += 1 + rowLength
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_DECODED = 24 * 1024 * 1024

        /** Compiled once: dictionaries are inspected for every stream in the file. */
        val LENGTH_PATTERN = Regex("/Length\\s+(\\d+)")
    }
}

/** Recursive descent parser for PDF values inside an object body. */
internal class ValueParser(
    private val data: ByteArray,
    private var pos: Int,
    private val limit: Int,
) {
    fun skipWhitespace() {
        while (pos < limit) {
            val b = data[pos].toInt() and 0xFF
            if (b == '%'.code) {
                while (pos < limit && data[pos] != '\n'.code.toByte() && data[pos] != '\r'.code.toByte()) pos++
            } else if (b <= 0x20) {
                pos++
            } else {
                return
            }
        }
    }

    fun consumeKeyword(keyword: String): Boolean {
        skipWhitespace()
        if (pos + keyword.length > limit) return false
        for (i in keyword.indices) {
            if (data[pos + i] != keyword[i].code.toByte()) return false
        }
        pos += keyword.length
        return true
    }

    /** Position just after the stream keyword's EOL, where binary data begins. */
    fun streamDataStart(): Int {
        if (pos < limit && data[pos] == '\r'.code.toByte()) pos++
        if (pos < limit && data[pos] == '\n'.code.toByte()) pos++
        return pos.coerceAtMost(limit)
    }

    fun parseValue(depth: Int = 0): PdfObject {
        if (depth > MAX_DEPTH) return PdfObject.Null
        skipWhitespace()
        if (pos >= limit) return PdfObject.Null
        return when (val b = data[pos].toInt() and 0xFF) {
            '<'.code -> if (pos + 1 < limit && data[pos + 1] == '<'.code.toByte()) {
                parseDict(depth)
            } else {
                parseHexString()
            }
            '['.code -> parseArray(depth)
            '/'.code -> parseName()
            '('.code -> parseLiteralString()
            't'.code -> if (consumeKeyword("true")) PdfObject.Num(1.0) else skipToken()
            'f'.code -> if (consumeKeyword("false")) PdfObject.Num(0.0) else skipToken()
            'n'.code -> if (consumeKeyword("null")) PdfObject.Null else skipToken()
            in '0'.code..'9'.code, '-'.code, '+'.code, '.'.code -> parseNumberOrReference()
            else -> skipToken()
        }
    }

    private fun parseDict(depth: Int): PdfObject.Dict {
        pos += 2
        val entries = LinkedHashMap<String, PdfObject>()
        while (pos < limit) {
            skipWhitespace()
            if (pos >= limit) break
            if (data[pos] == '>'.code.toByte() && pos + 1 < limit && data[pos + 1] == '>'.code.toByte()) {
                pos += 2
                break
            }
            if (data[pos] != '/'.code.toByte()) {
                // Malformed dict: skip a token to make progress.
                val before = pos
                skipToken()
                if (pos == before) pos++
                continue
            }
            val name = parseName()
            val key = (name as? PdfObject.Name)?.value ?: continue
            val value = parseValue(depth + 1)
            entries[key] = value
        }
        return PdfObject.Dict(entries)
    }

    private fun parseArray(depth: Int): PdfObject.Array {
        pos++
        val items = ArrayList<PdfObject>(8)
        while (pos < limit) {
            skipWhitespace()
            if (pos >= limit) break
            if (data[pos] == ']'.code.toByte()) {
                pos++
                break
            }
            val before = pos
            items.add(parseValue(depth + 1))
            if (pos == before) pos++
        }
        return PdfObject.Array(items)
    }

    private fun parseName(): PdfObject {
        pos++
        val sb = StringBuilder(16)
        while (pos < limit) {
            val b = data[pos].toInt() and 0xFF
            if (b <= 0x20 || b == '/'.code || b == '['.code || b == ']'.code ||
                b == '<'.code || b == '>'.code || b == '('.code || b == ')'.code || b == '{'.code || b == '}'.code
            ) {
                break
            }
            if (b == '#'.code && pos + 2 < limit) {
                val hex = String(data, pos + 1, 2, StandardCharsets.ISO_8859_1).toIntOrNull(16)
                if (hex != null) {
                    sb.append(hex.toChar())
                    pos += 3
                    continue
                }
            }
            sb.append(b.toChar())
            pos++
        }
        return PdfObject.Name(sb.toString())
    }

    private fun parseHexString(): PdfObject {
        pos++
        val out = ByteArrayOutputStream()
        var high = -1
        while (pos < limit) {
            val b = data[pos].toInt() and 0xFF
            if (b == '>'.code) {
                pos++
                break
            }
            val nibble = when (b) {
                in '0'.code..'9'.code -> b - '0'.code
                in 'a'.code..'f'.code -> b - 'a'.code + 10
                in 'A'.code..'F'.code -> b - 'A'.code + 10
                else -> {
                    pos++
                    continue
                }
            }
            if (high < 0) high = nibble else {
                out.write((high shl 4) or nibble)
                high = -1
            }
            pos++
        }
        return PdfObject.Str(out.toByteArray())
    }

    private fun parseLiteralString(): PdfObject {
        pos++
        val out = ByteArrayOutputStream()
        var depth = 1
        while (pos < limit) {
            val b = data[pos].toInt() and 0xFF
            when (b) {
                '\\'.code -> {
                    pos++
                    if (pos >= limit) break
                    when (val esc = data[pos].toInt() and 0xFF) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        in '0'.code..'7'.code -> {
                            var value = esc - '0'.code
                            var count = 1
                            while (count < 3 && pos + 1 < limit &&
                                (data[pos + 1].toInt() and 0xFF) in '0'.code..'7'.code
                            ) {
                                pos++
                                value = value * 8 + ((data[pos].toInt() and 0xFF) - '0'.code)
                                count++
                            }
                            out.write(value and 0xFF)
                        }
                        else -> out.write(esc)
                    }
                    pos++
                }
                '('.code -> {
                    depth++
                    out.write(b)
                    pos++
                }
                ')'.code -> {
                    depth--
                    if (depth == 0) {
                        pos++
                        break
                    }
                    out.write(b)
                    pos++
                }
                else -> {
                    out.write(b)
                    pos++
                }
            }
        }
        return PdfObject.Str(out.toByteArray())
    }

    /** Distinguishes `12 0 R` references from plain numbers. */
    private fun parseNumberOrReference(): PdfObject {
        val numberStart = pos
        val first = readNumberToken()
        val afterFirst = pos
        skipWhitespace()
        if (pos < limit && (data[pos].toInt() and 0xFF) in '0'.code..'9'.code) {
            readNumberToken()
            skipWhitespace()
            if (pos < limit && data[pos] == 'R'.code.toByte() &&
                (pos + 1 >= limit || (data[pos + 1].toInt() and 0xFF) <= 0x20 ||
                    data[pos + 1] == '>'.code.toByte() || data[pos + 1] == ']'.code.toByte())
            ) {
                pos++
                return PdfObject.Reference(first.toIntOrNull() ?: 0)
            }
        }
        pos = afterFirst
        return PdfObject.Num(first.toDoubleOrNull() ?: 0.0).also { if (pos <= numberStart) pos = numberStart + 1 }
    }

    private fun readNumberToken(): String {
        val start = pos
        while (pos < limit) {
            val b = data[pos].toInt() and 0xFF
            if (b in '0'.code..'9'.code || b == '-'.code || b == '+'.code || b == '.'.code) {
                pos++
            } else {
                break
            }
        }
        return String(data, start, pos - start, StandardCharsets.ISO_8859_1)
    }

    private fun skipToken(): PdfObject {
        val start = pos
        while (pos < limit) {
            val b = data[pos].toInt() and 0xFF
            if (b <= 0x20 || b == '/'.code || b == '['.code || b == ']'.code ||
                b == '<'.code || b == '>'.code || b == '('.code || b == ')'.code
            ) {
                break
            }
            pos++
        }
        if (pos == start) pos++
        return PdfObject.Null
    }

    private companion object {
        const val MAX_DEPTH = 24
    }
}
