package io.pagwyn.reader.render

import android.graphics.Bitmap
import android.os.Build
import java.util.LinkedHashMap

/**
 * A byte-budgeted LRU cache of rendered pages.
 *
 * Design notes:
 *  - Bounded by *bytes*, not entry count, so a landscape A3 page and a small book
 *    page do not cost the same.
 *  - The budget is derived from the process memory class, so a 512 MB device keeps
 *    a small cache while a flagship can keep pages crisp.
 *  - `evictRemote` is called by the scroll coordinator to free pages that are far
 *    from the viewport, keeping RAM available for the pages the user is looking at.
 *  - Entry keys carry the render scale, so zooming never shows a stale, blurry
 *    bitmap scaled up.
 */
class PageBitmapCache(budgetBytes: Int) {

    /** Identifies a rendered page: page number + quantised render width. */
    data class Key(val page: Int, val widthPx: Int, val rotation: Int)

    private class Entry(val bitmap: Bitmap, val bytes: Int) {
        var lastUsed: Long = System.nanoTime()
    }

    private val maxBytes: Int = budgetBytes.coerceAtLeast(MIN_BUDGET)
    /** Total budget for sharp pages; exposed so the renderer can avoid over-allocating. */
    val budgetBytes: Int get() = maxBytes
    private val entries = object : LinkedHashMap<Key, Entry>(32, 0.75f, true) {}

    var usedBytes: Int = 0
        private set

    val size: Int get() = entries.size

    @Synchronized
    fun get(key: Key): Bitmap? {
        val entry = entries[key] ?: return null
        entry.lastUsed = System.nanoTime()
        return entry.bitmap
    }

    @Synchronized
    fun put(key: Key, bitmap: Bitmap) {
        val bytes = byteCount(bitmap)
        val previous = entries.put(key, Entry(bitmap, bytes))
        if (previous != null) usedBytes -= previous.bytes
        usedBytes += bytes
        trimToBudget(protect = key)
    }

    /**
     * Puts a bitmap while dropping another key in the same atomic step. Used when
     * swapping in a sharper (higher zoom) render of a page already on screen.
     */
    @Synchronized
    fun putReplacing(key: Key, bitmap: Bitmap, replacing: Key?) {
        if (replacing != null && replacing != key) removeLocked(replacing)
        put(key, bitmap)
    }

    @Synchronized
    fun remove(key: Key) {
        removeLocked(key)
    }

    /**
     * Frees every cached page outside the `keep` window. Called after scrolling so
     * memory tracks the visible region instead of everything ever rendered.
     */
    @Synchronized
    fun evictRemote(minPage: Int, maxPage: Int) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key.page < minPage || key.page > maxPage) {
                usedBytes -= entry.bytes
                entry.bitmap.recycleSafely()
                iterator.remove()
            }
        }
    }

    /** Drops all bitmaps of a page, whatever zoom level they were rendered at. */
    @Synchronized
    fun evictPage(page: Int) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key.page == page) {
                usedBytes -= entry.bytes
                entry.bitmap.recycleSafely()
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { it.bitmap.recycleSafely() }
        entries.clear()
        usedBytes = 0
    }

    /** Keeps every bitmap of the fixed set of pages, used when the viewport changes. */
    @Synchronized
    fun retainedKeys(): Set<Key> = entries.keys.toSet()

    @Synchronized
    fun trimToBudget(protect: Key? = null, extraReserve: Int = 0) {
        if (entries.isEmpty()) return
        val effectiveBudget = (maxBytes - extraReserve).coerceAtLeast(MIN_BUDGET / 2)
        if (usedBytes <= effectiveBudget) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && usedBytes > effectiveBudget) {
            val (key, entry) = iterator.next()
            if (key == protect) continue
            usedBytes -= entry.bytes
            entry.bitmap.recycleSafely()
            iterator.remove()
        }
    }

    /** True when one more bitmap of [bytes] still fits without evicting everything. */
    @Synchronized
    fun canAllocate(bytes: Int): Boolean {
        trimToBudget(extraReserve = bytes)
        return bytes <= maxBytes / 2 && usedBytes + bytes <= maxBytes
    }

    private fun removeLocked(key: Key) {
        val entry = entries.remove(key) ?: return
        usedBytes -= entry.bytes
        entry.bitmap.recycleSafely()
    }

    companion object {
        private const val MIN_BUDGET = 8 * 1024 * 1024

        /**
         * Cache budget for the current process.
         *
         * Large-memory devices (>=256 MB class) may use an eighth of the heap;
         * entry-level devices stay at a sixth so the rest of the app (and the
         * native renderer) always has headroom.
         */
        fun forMemoryClass(memoryClassMb: Int): PageBitmapCache {
            val heapBytes = memoryClassMb * 1024 * 1024
            val divisor = if (memoryClassMb >= 256) 8 else 6
            return PageBitmapCache(heapBytes / divisor)
        }

        fun forDevice(): PageBitmapCache {
            val activityManager = PagwynAppContext.get()?.getSystemService(android.app.ActivityManager::class.java)
            val memoryClass = activityManager?.memoryClass ?: 128
            return forMemoryClass(memoryClass)
        }

        fun byteCount(bitmap: Bitmap): Int = when {
            Build.VERSION.SDK_INT >= 26 -> bitmap.allocationByteCount
            else -> bitmap.byteCount
        }
    }
}

/** Recycles a bitmap, ignoring double-recycle and already-recycled bitmaps. */
fun Bitmap.recycleSafely() {
    if (!isRecycled) runCatching { recycle() }
}
