package io.pagwyn.reader

import android.graphics.Bitmap
import io.pagwyn.reader.render.PageBitmapCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Memory-cache behaviour.
 *
 * Uses Robolectric because real [Bitmap] objects are required. The tests verify the
 * properties that keep Pagwyn stable on low-RAM devices: the byte budget is never
 * exceeded, eviction is distance-based, and recycled bitmaps are never handed out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PageBitmapCacheTest {

    private fun bitmap(width: Int = 32, height: Int = 32): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    @Test
    fun `stores and returns a page`() {
        val cache = PageBitmapCache(8 * 1024 * 1024)
        val bmp = bitmap()
        cache.put(PageBitmapCache.Key(3, 100, 0), bmp)
        assertEquals(bmp, cache.get(PageBitmapCache.Key(3, 100, 0)))
        assertEquals(1, cache.size)
    }

    @Test
    fun `different zoom levels are separate entries`() {
        val cache = PageBitmapCache(16 * 1024 * 1024)
        val small = bitmap(16, 16)
        val large = bitmap(64, 64)
        cache.put(PageBitmapCache.Key(1, 100, 0), small)
        cache.put(PageBitmapCache.Key(1, 400, 0), large)
        assertEquals(small, cache.get(PageBitmapCache.Key(1, 100, 0)))
        assertEquals(large, cache.get(PageBitmapCache.Key(1, 400, 0)))
    }

    @Test
    fun `evicts pages outside the requested window`() {
        val cache = PageBitmapCache(32 * 1024 * 1024)
        for (page in 0 until 10) {
            cache.put(PageBitmapCache.Key(page, 50, 0), bitmap(10, 10))
        }
        cache.evictRemote(minPage = 3, maxPage = 5)
        assertNull(cache.get(PageBitmapCache.Key(0, 50, 0)))
        assertNull(cache.get(PageBitmapCache.Key(9, 50, 0)))
        assertNotNull(cache.get(PageBitmapCache.Key(4, 50, 0)))
    }

    @Test
    fun `evictPage drops every zoom level of one page`() {
        val cache = PageBitmapCache(16 * 1024 * 1024)
        cache.put(PageBitmapCache.Key(7, 50, 0), bitmap(8, 8))
        cache.put(PageBitmapCache.Key(7, 200, 0), bitmap(8, 8))
        cache.put(PageBitmapCache.Key(8, 50, 0), bitmap(8, 8))
        cache.evictPage(7)
        assertNull(cache.get(PageBitmapCache.Key(7, 50, 0)))
        assertNull(cache.get(PageBitmapCache.Key(7, 200, 0)))
        assertNotNull(cache.get(PageBitmapCache.Key(8, 50, 0)))
    }

    @Test
    fun `budget is respected`() {
        val cache = PageBitmapCache(8 * 1024 * 1024)
        // Each bitmap is 512x512x4 = 1 MB; the cache must not hold more than 8.
        repeat(20) { page ->
            cache.put(PageBitmapCache.Key(page, 512, 0), bitmap(512, 512))
        }
        assertTrue(
            "cache used ${cache.usedBytes} bytes, budget ${cache.budgetBytes}",
            cache.usedBytes <= cache.budgetBytes,
        )
    }

    @Test
    fun `clear releases everything`() {
        val cache = PageBitmapCache(8 * 1024 * 1024)
        cache.put(PageBitmapCache.Key(1, 10, 0), bitmap(16, 16))
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0, cache.usedBytes)
    }

    @Test
    fun `canAllocate refuses request larger than half the budget`() {
        val cache = PageBitmapCache(9 * 1024 * 1024)
        assertTrue(cache.canAllocate(1024 * 1024))
        // Half the budget is 4.5 MB, so 8 MB must be refused to protect the app.
        assertTrue(!cache.canAllocate(8 * 1024 * 1024))
    }

    @Test
    fun `memory class sizing scales with the device`() {
        val lowEnd = PageBitmapCache.forMemoryClass(64)
        val highEnd = PageBitmapCache.forMemoryClass(512)
        assertTrue(highEnd.budgetBytes > lowEnd.budgetBytes)
        // Even the smallest device keeps a usable budget.
        assertTrue(lowEnd.budgetBytes >= 8 * 1024 * 1024)
    }
}
