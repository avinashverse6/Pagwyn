package io.pagwyn.reader.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs

/**
 * Deterministic cover art for a document, derived from its file name.
 *
 * Why generated rather than extracted: rendering page 1 as a thumbnail means touching
 * the PDF renderer from list binding, which is exactly the threading mistake that used
 * to abort the app. A deterministic gradient plus the document's initials is instant,
 * needs no bitmap cache, no image library and no native call — and it gives the
 * library a recognisable look, like Spotify's playlist covers.
 */
object CoverArtwork {

    /**
     * A stable hue pair for [seed] (usually the file name).
     *
     * The same document always gets the same cover, across sessions and devices,
     * because this is a pure function of the string.
     */
    fun colorsFor(seed: String, darkTheme: Boolean): Pair<Int, Int> {
        val hash = stableHash(seed)
        val hue = abs(hash) % 360
        val saturation = if (darkTheme) 0.62f else 0.55f
        val startValue = if (darkTheme) 0.72f else 0.66f
        val endValue = if (darkTheme) 0.42f else 0.40f
        val start = Color.HSVToColor(floatArrayOf(hue.toFloat(), saturation, startValue))
        // The second stop is 40 degrees away, which always reads as a gradient rather
        // than as a muddy blend.
        val end = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, saturation, endValue))
        return start to end
    }

    /**
     * Up to two initials for the cover.
     *
     * Skips punctuation and numbers-less noise so "deep-work_2019.pdf" becomes "DW",
     * and a single-word title becomes its first letter. Falls back to a book glyph
     * surrogate ("P" for Pagwyn) when nothing usable is left.
     */
    fun initialsFor(name: String): String {
        val words = name
            .substringBeforeLast('.')
            .split(' ', '-', '_', '.', ',', '(')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val letters = words.mapNotNull { word ->
            word.firstOrNull { ch -> ch.isLetter() }
        }
        return when {
            letters.isEmpty() -> "P"
            letters.size == 1 -> letters[0].uppercase()
            else -> (letters[0].uppercase() + letters[1].uppercase())
        }
    }

    /**
     * Draws the cover into [bitmap]. Kept as a bitmap so the card can be a plain
     * `ImageView` with no custom drawing in the scroll path.
     */
    fun render(bitmap: Bitmap, seed: String, initials: String, darkTheme: Boolean = true) {
        val canvas = Canvas(bitmap)
        val (start, end) = colorsFor(seed, darkTheme)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // A diagonal gradient, warm at the top-left and deeper at the bottom-right.
        paint.shader = LinearGradient(
            0f, 0f,
            bitmap.width.toFloat(), bitmap.height.toFloat(),
            start, end,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)

        // Soft corner glow so the shape does not read as a flat rectangle.
        paint.shader = LinearGradient(
            0f, 0f, 0f, bitmap.height.toFloat(),
            Color.argb(0, 0, 0, 0),
            Color.argb(70, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = bitmap.height * 0.42f
            textAlign = Paint.Align.CENTER
        }
        // Centre the initials optically: cap height, not font metrics.
        val metrics = text.fontMetrics
        val baseline = bitmap.height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(initials, bitmap.width / 2f, baseline, text)
    }

    /** Convenience for a square cover of [size] pixels. */
    fun create(size: Int, seed: String, darkTheme: Boolean = true): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        render(bitmap, seed, initialsFor(seed), darkTheme)
        return bitmap
    }

    /** Draws a rounded-rect version, used where a view clip is not available. */
    fun rounded(bitmap: Bitmap, radiusPx: Float): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = Color.BLACK
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    /**
     * A hash that does not depend on the JVM's string hashing, so a document's cover is
     * stable across processes, devices and app versions.
     */
    private fun stableHash(value: String): Int {
        var hash = 0
        for (ch in value) {
            hash = hash * 31 + ch.code
        }
        return hash
    }
}
