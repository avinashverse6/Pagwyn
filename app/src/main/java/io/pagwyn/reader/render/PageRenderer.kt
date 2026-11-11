package io.pagwyn.reader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.pagwyn.reader.core.DocumentSource
import io.pagwyn.reader.pdf.PageInfo
import io.pagwyn.reader.pdf.PdfDocument
import io.pagwyn.reader.pdf.PdfOpenException
import io.pagwyn.reader.render.PageBitmapCache.Companion.byteCount
import io.pagwyn.reader.render.PageBitmapCache.Key
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the PDF document and renders pages off the main thread.
 *
 * Rendering strategy (the heart of Pagwyn's performance):
 *
 *  1. **One render thread.** `PdfRenderer` is not thread safe; a single dedicated
 *     thread avoids lock contention and keeps native allocations predictable.
 *  2. **Viewport driven.** The view reports which pages are visible and in what
 *     order. Nothing else is rendered, so a 3000 page document costs the same as
 *     a 30 page one.
 *  3. **Progressive.** The current page is rendered twice: first a small preview
 *     that appears almost instantly, then a sharp pass at the target resolution.
 *     Neighbouring pages get previews so scrolling never shows a blank gap.
 *  4. **Zoom aware.** Cache keys include the render width, so zooming triggers a
 *     re-render instead of upscaling a blurry bitmap.
 *  5. **Cancellable.** A queued job that is no longer wanted is dropped before it
 *     costs any native work.
 */
class PageRenderer(
    private val context: Context,
    private val cache: PageBitmapCache,
) : AutoCloseable {

    /** Delivered on the main thread. */
    fun interface Listener {
        fun onPageRendered(page: Int, bitmap: Bitmap, sharp: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pagwyn-render").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    var document: PdfDocument? = null
        private set

    var listener: Listener? = null

    /** Bumped whenever the viewport changes so stale jobs can be discarded cheaply. */
    private var generation = 0

    private var desiredOrder: List<Int> = emptyList()
    private var desiredWidth = 0
    private var desiredRotation = 0
    private var previewOnly: Set<Int> = emptySet()

    private val queuedPreviews = HashSet<Int>()
    private val queuedSharp = HashSet<Int>()
    private val closing = AtomicBoolean(false)

    val pageCount: Int get() = document?.pageCount ?: 0

    val isOpen: Boolean get() = document != null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Opens a document. Safe to call from any thread; the renderer does the work on
     * its own thread so `PdfRenderer` is always touched from one place.
     */
    suspend fun open(
        uri: Uri,
        password: String? = null,
        source: DocumentSource? = null,
    ): PdfDocument = withContext(dispatcher) {
        closeInternal()
        val opened = PdfDocument.open(context, uri, password, source)
        document = opened
        opened
    }

    suspend fun pageInfo(index: Int): PageInfo? = withContext(dispatcher) {
        runCatching { document?.pageInfo(index) }.getOrNull()
    }

    /**
     * Explains why a document could not be opened, without throwing. Used by the
     * UI to distinguish "wrong password" from "corrupt file".
     */
    suspend fun tryOpen(uri: Uri, password: String?): Result<PdfDocument> = withContext(dispatcher) {
        runCatching {
            closeInternal()
            PdfDocument.open(context, uri, password).also { document = it }
        }
    }

    /** Cached bitmap for an exact key, if present. Never blocks. */
    fun cached(page: Int, widthPx: Int, rotation: Int): Bitmap? =
        cache.get(Key(page, widthPx, rotation))

    /** Any cached bitmap for the page, used to paint instantly while a sharp pass runs. */
    fun cachedAny(page: Int): Bitmap? {
        val keys = cache.retainedKeys().filter { it.page == page }
        return keys.maxByOrNull { it.widthPx }?.let { cache.get(it) }
    }

    // ------------------------------------------------------------------ viewport

    /**
     * Declares what should exist in memory right now.
     *
     * @param order pages to render, most important first (usually viewport order).
     * @param widthPx target bitmap width for sharp renders.
     * @param rotation page rotation applied by the reader (0/90/180/270).
     */
    fun setViewport(order: List<Int>, widthPx: Int, rotation: Int) {
        if (closing.get()) return
        val sameRequest = order == desiredOrder && widthPx == desiredWidth && rotation == desiredRotation
        if (sameRequest) return
        desiredOrder = order
        desiredWidth = widthPx
        desiredRotation = rotation
        generation++
        queuedPreviews.clear()
        queuedSharp.clear()
        previewOnly = emptySet()
        val gen = generation
        scope.launch { processViewport(gen) }
    }

    /** Warm up adjacent pages without marking them visible. */
    fun prefetch(pages: List<Int>, widthPx: Int, rotation: Int) {
        if (closing.get() || document == null) return
        val gen = generation
        scope.launch {
            for (page in pages) {
                if (gen != generation) return@launch
                if (cache.get(Key(page, widthPx, rotation)) != null) continue
                renderPreview(page, widthPx, rotation, gen)
            }
        }
    }

    private suspend fun processViewport(gen: Int) {
        val doc = document ?: return
        val width = desiredWidth
        if (width <= 0) return
        val rotation = desiredRotation

        // Phase 1: previews in viewport order. Cheap and fast, so text is legible
        // within a frame or two even on a slow device opening a huge document.
        for (page in desiredOrder) {
            if (gen != generation) return
            if (page !in 0 until doc.pageCount) continue
            if (cache.get(Key(page, width, rotation)) != null) {
                deliverCached(page, width, rotation)
                continue
            }
            renderPreview(page, width, rotation, gen)
        }

        // Phase 2: sharp renders, viewport order first.
        for (page in desiredOrder) {
            if (gen != generation) return
            if (page !in 0 until doc.pageCount) continue
            renderSharp(page, width, rotation, gen)
        }
    }

    private fun deliverCached(page: Int, width: Int, rotation: Int) {
        val bitmap = cache.get(Key(page, width, rotation)) ?: return
        mainHandler.post {
            if (!bitmap.isRecycled) listener?.onPageRendered(page, bitmap, true)
        }
    }

    private fun renderPreview(page: Int, width: Int, rotation: Int, gen: Int) {
        val doc = document ?: return
        val targetWidth = minOf(width, PREVIEW_MAX_WIDTH).coerceAtLeast(PREVIEW_MIN_WIDTH)
        val info = runCatching { doc.pageInfo(page) }.getOrNull() ?: return
        val key = Key(page, width, rotation)
        if (cache.get(key) != null) return
        val previewKey = Key(page, targetWidth, rotation)
        if (cache.get(previewKey) != null) {
            cache.get(previewKey)?.let { bitmap ->
                mainHandler.post { if (!bitmap.isRecycled) listener?.onPageRendered(page, bitmap, false) }
            }
            return
        }
        val (w, h) = dimensionsFor(info, targetWidth, rotation)
        if (!reserveBitmap(w, h)) return
        val bitmap = runCatching { PdfDocument.bitmapFor(w, h) }.getOrNull() ?: return
        val ok = runCatching {
            doc.renderPage(page, bitmap, w, h, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }.getOrDefault(false)
        if (!ok || gen != generation) {
            if (!ok) bitmap.recycleSafely()
            return
        }
        cache.put(previewKey, bitmap)
        mainHandler.post {
            if (!bitmap.isRecycled && gen == generation) {
                listener?.onPageRendered(page, bitmap, false)
            }
        }
    }

    private fun renderSharp(page: Int, width: Int, rotation: Int, gen: Int) {
        val doc = document ?: return
        val key = Key(page, width, rotation)
        if (cache.get(key) != null) {
            deliverCached(page, width, rotation)
            return
        }
        val info = runCatching { doc.pageInfo(page) }.getOrNull() ?: return
        val (w, h) = dimensionsFor(info, width, rotation)
        if (!reserveBitmap(w, h)) {
            // Not enough memory for a full-resolution page: keep the preview. The
            // user still sees readable content rather than a blank slot.
            return
        }
        val bitmap = runCatching { PdfDocument.bitmapFor(w, h) }.getOrNull() ?: return
        val ok = runCatching {
            doc.renderPage(page, bitmap, w, h, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }.getOrDefault(false)
        if (!ok || gen != generation) {
            if (!ok) bitmap.recycleSafely()
            return
        }
        cache.put(key, bitmap)
        mainHandler.post {
            if (!bitmap.isRecycled && gen == generation) {
                listener?.onPageRendered(page, bitmap, true)
            }
        }
    }

    /**
     * Ensures the cache has room for one more page bitmap. Returns false when the
     * allocation would be unsafe, in which case the caller keeps the preview.
     */
    private fun reserveBitmap(width: Int, height: Int): Boolean {
        val bytes = width * height * 4
        if (bytes > MAX_SINGLE_BITMAP_BYTES) return false
        return cache.canAllocate(bytes)
    }

    private fun dimensionsFor(info: PageInfo, width: Int, rotation: Int): Pair<Int, Int> {
        val safeWidth = minOf(width, MAX_SIDE)
        val ratio = if (info.widthPoints > 0) info.heightPoints.toFloat() / info.widthPoints else 1.4f
        var w = safeWidth
        var h = (safeWidth * ratio).toInt().coerceAtLeast(1)
        if (h > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / h
            h = MAX_SIDE
            w = (w * scale).toInt().coerceAtLeast(1)
        }
        return if (rotation % 180 != 0) h to w else w to h
    }

    /** Drops pages far outside the viewport so memory tracks the visible region. */
    fun trimAround(minPage: Int, maxPage: Int) {
        cache.evictRemote(minPage, maxPage)
    }

    // ------------------------------------------------------------------ text & search

    suspend fun pageText(page: Int): String? = withContext(dispatcher) {
        runCatching { document?.pageText(page) }.getOrNull()
    }

    suspend fun search(
        query: String,
        limit: Int = 200,
        shouldCancel: () -> Boolean = { false },
    ) = withContext(dispatcher) {
        runCatching { document?.search(query, limit, shouldCancel) }.getOrDefault(emptyList())
    }

    suspend fun hasTextLayer(): Boolean = withContext(dispatcher) {
        document?.hasTextLayer == true
    }

    // ------------------------------------------------------------------ teardown

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        runCatching {
            kotlinx.coroutines.runBlocking(dispatcher) { closeInternal() }
        }.onFailure { Log.w(TAG, "close failed", it) }
        cache.clear()
        runCatching { executor.shutdown() }
        runCatching { dispatcher.close() }
    }

    private fun closeInternal() {
        document?.let { runCatching { it.close() } }
        document = null
        cache.clear()
    }

    companion object {
        private const val TAG = "PagwynRender"

        /** Preview renders are cheap: enough to read a heading while the sharp pass runs. */
        private const val PREVIEW_MAX_WIDTH = 720
        private const val PREVIEW_MIN_WIDTH = 320

        /** Hard cap on any single bitmap side, keeping allocations sane on low RAM. */
        private const val MAX_SIDE = 3600
        private const val MAX_SINGLE_BITMAP_BYTES = 22 * 1024 * 1024

    }
