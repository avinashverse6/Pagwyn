package io.pagwyn.reader.core

import android.content.Context
import android.text.format.Formatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Human readable sizes, dates and page labels. All pure functions: easy to unit test. */
object Formatters {

    fun fileSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }

    /** Uses the platform formatter so the unit style matches the user's locale. */
    fun fileSizeLocalized(context: Context, bytes: Long): String =
        if (bytes <= 0) "—" else Formatter.formatShortFileSize(context, bytes)

    /**
     * Relative time for reading history: "Just now", "12 min ago", "Yesterday",
     * "3 days ago", then an absolute date.
     */
    fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
        if (then <= 0) return "—"
        val delta = now - then
        if (delta < 0) return absoluteDate(then)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
        val hours = TimeUnit.MILLISECONDS.toHours(delta)
        val days = TimeUnit.MILLISECONDS.toDays(delta)
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 && isSameDay(then, now) -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
            days <= 1 -> "Yesterday"
            days < 7 -> "$days days ago"
            days < 30 -> "${days / 7} weeks ago".replace("1 weeks", "1 week")
            else -> absoluteDate(then)
        }
    }

    fun absoluteDate(time: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(time))

    fun absoluteDateTime(time: Long): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(time))

    fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** Pages are 1-based in the UI. */
    fun pageLabel(pageIndex: Int, pageCount: Int): String = "${pageIndex + 1} / $pageCount"

    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f + 0.5f).toInt()

    /** "A4 · 595 × 842 pt" style page size caption. */
    fun pageSize(widthPt: Int, heightPt: Int): String {
        val known = knownPaper(widthPt, heightPt)
        val dims = "$widthPt × $heightPt pt"
        return if (known != null) "$known · $dims" else dims
    }

    private fun knownPaper(w: Int, h: Int): String? {
        val (short, long) = if (w <= h) w to h else h to w
        return when {
            near(short, 595) && near(long, 842) -> "A4"
            near(short, 420) && near(long, 595) -> "A5"
            near(short, 297) && near(long, 420) -> "A6"
            near(short, 612) && near(long, 792) -> "US Letter"
            near(short, 612) && near(long, 1008) -> "US Legal"
            near(short, 596) && near(long, 842) -> "A4"
            near(short, 842) && near(long, 1191) -> "A3"
            else -> null
        }
    }

    private fun near(value: Int, target: Int): Boolean = kotlin.math.abs(value - target) <= 6

    /** Shortens long document names while keeping the extension readable. */
    fun middleEllipsis(text: String, max: Int): String {
        if (text.length <= max || max < 8) return text
        val keepStart = (max - 3) / 2
        val keepEnd = max - 3 - keepStart
        return text.substring(0, keepStart) + "…" + text.substring(text.length - keepEnd)
    }

    /** Strips the ".pdf" suffix for headline display. */
    fun titleFromFileName(name: String?): String {
        if (name.isNullOrBlank()) return "Untitled document"
        val trimmed = name.substringAfterLast('/').substringAfterLast(':')
        return trimmed.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Untitled document" }
    }
}
