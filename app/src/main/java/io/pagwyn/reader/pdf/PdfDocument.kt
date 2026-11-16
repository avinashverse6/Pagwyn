package io.pagwyn.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.pagwyn.reader.core.DocumentSource
import io.pagwyn.reader.core.DocumentResolver
import java.io.File
import java.io.IOException

/** Errors surfaced to the UI with a specific, actionable message. */
sealed class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NeedsPassword(val passwordRequired: Boolean = true) :
        PdfOpenException("Document requires a password")

    class WrongPassword : PdfOpenException("Incorrect password")

    class UnsupportedEncryption : PdfOpenException("Unsupported encryption")

    class Corrupt : PdfOpenException("Document is unreadable")

    class NotFound : PdfOpenException("Document is no longer accessible")

    class TooLarge : PdfOpenException("Document is too large to open")
}

/**
 * Keeps closed `PdfRenderer` instances reachable on Android 7.
 *
 * On API 24 `PdfRenderer.doClose()` calls `nativeClose(mNativeDocument)` but never
 * clears `mNativeDocument`, and `finalize()` calls `doClose()` again whenever the
 * object is collected. The native document is therefore freed twice: a `SIGSEGV` in
 * the `FinalizerDaemon` inside `CPDF_Parser::~CPDF_Parser` /
 * `CPDF_Document::~CPDF_Document`, killing the app seconds after a document closes.
 * Holding a reference to the (now inert) object means the finalizer never runs.
 *
 * The list is capped, so this cannot grow without bound; the retained objects hold no
 * native memory of their own once closed.
 */
private object RetiredRenderers {
    private const val TAG = "PagwynPdf"

    /**
     * Kept forever, deliberately. Evicting an entry hands the object back to the
     * garbage collector, whose finalizer closes the native document a second time —
     * the very double free this object exists to prevent. Dropping old entries made
     * the crash *worse and later*: the process survived the eviction and then died in
     * `CPDF_Document::~CPDF_Document` inside a later, unrelated `close()`, because the
     * heap had already been corrupted.
     *
     * A retired renderer is an inert Java object with no native memory attached, so the
     * cost of keeping it is a few dozen bytes per document ever opened on Android 7.
     */
    private val retained = java.util.ArrayDeque<PdfRenderer>()

    @Volatile
    private var retired = 0

    fun retireIfNeeded(renderer: PdfRenderer) {
        if (android.os.Build.VERSION.SDK_INT > 24) return
        synchronized(retained) {
            retained.addLast(renderer)
            retired++
            if (retired == 1 || retired % 25 == 0) {
                android.util.Log.d(TAG, "retired $retired closed renderers (API 24)")
            }
        }
    }
}

/**
 * A single page handle.
 *
 * [widthPoints] and [heightPoints] are the intrinsic page dimensions in points
 * (1/72 inch). [rotation] is applied by PdfRenderer, so callers always receive a
 * page that is already oriented the way it will be drawn.
 */
data class PageInfo(
    val index: Int,
    val widthPoints: Int,
    val heightPoints: Int,
    val rotation: Int = 0,
) {
    val aspectRatio: Float get() = if (heightPoints > 0) widthPoints.toFloat() / heightPoints else 0.75f
}

/**
 * Owns the platform [PdfRenderer] together with its file descriptor.
 *
 * Threading contract:
 *  - All methods except [renderPage] must be called from a single thread (the
 *    render dispatcher).
 *  - [renderPage] must also be called on that same thread, because
 *    [PdfRenderer.openPage] is not thread safe.
 *
 * The class therefore does not synchronise per-page access; instead the whole
 * engine is confined to one rendering thread, which is both faster and simpler
 * than locking around a native renderer.
 */
class PdfDocument private constructor(
    val source: DocumentSource,
    private val descriptor: ParcelFileDescriptor,
    val renderer: PdfRenderer,
    /** Plain-text extraction service, may be unavailable for some documents. */
    private val textService: PdfTextService?,
    /**
     * Descriptor kept open for the text engine.
     *
     * A descriptor number can be reused as soon as it is closed, so resolving
     * `/proc/self/fd/<n>` for text through the renderer's descriptor would eventually
     * point at whichever document took that number next (observed in practice: text was
     * extracted from an unrelated file). This descriptor is owned by the document and
     * stays open until [close], so the path always identifies this document.
     */
    private val textDescriptor: ParcelFileDescriptor?,
    /** True when [PdfTextService] reads from a temporary cache copy. */
    private val textPathIsCopy: Boolean,
) : AutoCloseable {

    val pageCount: Int = renderer.pageCount

    /**
     * Serialises every native call. `PdfRenderer` aborts the process (SIGSEGV, not an
     * exception) if it is closed while another thread is inside `page.render()`, which
     * is exactly what happens when the reader leaves mid-render. Every entry point
     * therefore takes this lock, and [close] waits for the in-flight page.
     */
    private val lock = Any()

    @Volatile
    private var closed = false

    private val dimensions = HashMap<Int, PageInfo>(pageCount.coerceAtMost(MAX_DIMENSION_CACHE))

    /**
     * Runs [block] with the document's single open page, then closes it.
     *
     * `PdfRenderer` allows **exactly one page to be open at a time**: `openPage`
     * throws `IllegalStateException("Current page not closed")` otherwise, and
     * `PdfRenderer.close()` requires the current page to be closed first. Keeping
     * several handles alive (an earlier attempt to dodge an Android 7 crash) broke
     * every page except the first: each `openPage` threw, `renderPage` swallowed the
     * exception and returned false, and the reader showed "Rendering page…" forever.
     *
     * So the handle is opened and closed inside this call, under [lock], and never
     * stored. That is the platform's own contract.
     */
    private inline fun <T> withPage(index: Int, block: (PdfRenderer.Page) -> T): T {
        check(!closed) { "Document is closed" }
        val page = renderer.openPage(index)
        try {
            return block(page)
        } finally {
            // Closed in `finally` so a throwing render can never leave the renderer
            // wedged with a page stuck open. `Page.close()` is idempotent-safe on
            // every API level (it checks its own native handle first).
            runCatching { page.close() }
        }
    }

    fun pageInfo(index: Int): PageInfo = synchronized(lock) {
        dimensions.getOrPut(index) {
            withPage(index) { page ->
                PageInfo(
                    index = index,
                    widthPoints = page.width,
                    heightPoints = page.height,
                    rotation = 0,
                )
            }
        }
    }

    /**
     * Renders [index] into [bitmap].
     *
     * `widthPx`/`heightPx` describe the output bitmap. If only one dimension is
     * known the other is derived from the page aspect ratio, so callers never
     * distort content. Returns null when the document has been closed underneath.
     */
    fun renderPage(
        index: Int,
        bitmap: Bitmap,
        widthPx: Int,
        heightPx: Int,
        renderMode: Int = PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
    ): Boolean = synchronized(lock) {
        if (closed) return false
        if (index !in 0 until pageCount) return false
        try {
            withPage(index) { page ->
                // PdfRenderer requires the bitmap to match the requested size closely.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, renderMode)
            }
            true
        } catch (e: IllegalArgumentException) {
            // Malformed page or a bitmap size the renderer rejects.
            onRenderFailure?.invoke(index, e)
            false
        } catch (e: IllegalStateException) {
            // The renderer is closed, or a page is already open. This used to be
            // swallowed silently, which is exactly how "Rendering page…" became an
            // infinite spinner: the failure never reached the UI.
            onRenderFailure?.invoke(index, e)
            false
        } catch (e: IndexOutOfBoundsException) {
            onRenderFailure?.invoke(index, e)
            false
        } catch (e: RuntimeException) {
            // Any other unexpected platform failure is reported, never hidden.
            onRenderFailure?.invoke(index, e)
            false
        }
    }

    /**
     * Notified when a page could not be rendered, with the exception behind it.
     *
     * The reader turns this into a visible "Page N could not be rendered — Retry"
     * state, so a failure can never look like a page that is still loading.
     */
    var onRenderFailure: ((page: Int, cause: Throwable) -> Unit)? = null

    fun pageText(index: Int): String? = textService?.pageText(index)

    /** Filesystem path used for text extraction; diagnostics only. */
    @androidx.annotation.VisibleForTesting
    internal fun textPathForTest(): String? = textService?.filePath

    /** Number of pages the text parser recovered; diagnostics only. */
    @androidx.annotation.VisibleForTesting
    internal fun parsePageCountForTest(): Int = textService?.pageCount() ?: -1

    /** Case-insensitive search across the document, executed on the calling thread. */
    fun search(query: String, limit: Int, shouldCancel: () -> Boolean): List<SearchHit> {
        val service = textService ?: return emptyList()
        return service.search(query, limit, shouldCancel)
    }

    val hasTextLayer: Boolean get() = textService != null

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        android.util.Log.d(
            "PagwynPdf",
            "closing ${renderer.hashCode()} on ${Thread.currentThread().name}",
        )
        runCatching { renderer.close() }
        RetiredRenderers.retireIfNeeded(renderer)
        runCatching { descriptor.close() }
        textService?.close()
        runCatching { textDescriptor?.close() }
        // Delete the temporary copy made for text extraction, if one was needed.
        if (textPathIsCopy) runCatching { PdfDependencyLoader.releaseCopy(textService?.filePath) }
        Unit
    }

    companion object {
        private const val MAX_DIMENSION_CACHE = 2048

        /**
         * Opens [uri] through the platform renderer.
         *
         * @param password optional password for encrypted documents.
         * @throws PdfOpenException with a specific subclass for user-facing cases.
         */
        fun open(
            context: Context,
            uri: Uri,
            password: String? = null,
            source: DocumentSource? = DocumentResolver.resolve(context, uri),
        ): PdfDocument {
            val descriptor = openDescriptor(context, uri)
                ?: throw PdfOpenException.NotFound()

            // A second, independent descriptor whose only job is to keep the raw bytes
            // readable for the text engine for as long as the document is open.
            PdfDependencyLoader.ensureLoaded(context)
            var textDescriptor = openDescriptor(context, uri)
            val fdPath = textDescriptor?.let { PdfTextService.descriptorPath(it) }
            // `statSize` is the authoritative size of the open document; it is used to
            // validate any /proc path before text extraction trusts it.
            val descriptorSize = runCatching { textDescriptor?.statSize ?: -1L }.getOrDefault(-1L)
            val textPath = runCatching {
                PdfDependencyLoader.resolveReadablePath(context, uri, fdPath, descriptorSize)
            }.getOrNull()
            val textPathIsCopy = textPath != null && (fdPath == null || textPath != fdPath)
            if (textPathIsCopy) {
                textDescriptor?.closeSafely()
                textDescriptor = null
            }

            // The renderer takes over the descriptor and closes it on `close()`, so every
            // failure path below must close the renderer first and must never close the
            // descriptor itself: a double close desynchronises native code and can abort
            // the process on the render thread.
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: SecurityException) {
                textDescriptor?.closeSafely()
                // The renderer never took ownership, so this descriptor is still ours.
                descriptor.closeSafely()
                throw PdfOpenException.NeedsPassword()
            } catch (e: IllegalStateException) {
                textDescriptor?.closeSafely()
                descriptor.closeSafely()
                throw PdfOpenException.Corrupt()
            } catch (e: IOException) {
                textDescriptor?.closeSafely()
                descriptor.closeSafely()
                throw PdfOpenException.Corrupt()
            } catch (e: OutOfMemoryError) {
                textDescriptor?.closeSafely()
                descriptor.closeSafely()
                throw PdfOpenException.TooLarge()
            }

            if (renderer.pageCount <= 0) {
                runCatching { renderer.close() }
                RetiredRenderers.retireIfNeeded(renderer)
                textDescriptor?.closeSafely()
                throw PdfOpenException.Corrupt()
            }

            // Text extraction is best effort. A failure here must never block opening:
            // reading a scanned PDF is still perfectly valid.
            val textService = runCatching { PdfTextService(textPath) }.getOrNull()

            return PdfDocument(
                source = source ?: DocumentResolver.resolve(context, uri),
                descriptor = descriptor,
                renderer = renderer,
                textService = textService,
                textDescriptor = textDescriptor,
                textPathIsCopy = textPathIsCopy,
            )
        }

        private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
            return if (uri.scheme == "file") {
                val path = uri.path ?: return null
                val file = File(path)
                if (!file.exists()) return null
                runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
                    .getOrNull()
            } else {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")
                }.getOrNull()
            }
        }

        private fun ParcelFileDescriptor.closeSafely() = runCatching { close() }

        fun bitmapFor(widthPx: Int, heightPx: Int): Bitmap =
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)

        /** Height in pixels for a page scaled to [targetWidth], preserving aspect ratio. */
        fun scaledDimensions(info: PageInfo, targetWidth: Int): Point {
            val w = targetWidth.coerceAtLeast(1)
            val ratio = if (info.widthPoints > 0) {
                info.heightPoints.toFloat() / info.widthPoints
            } else {
                1.4f
            }
            return Point(w, (w * ratio).toInt().coerceAtLeast(1))
        }
    }
}

/** A single search hit inside the document. */
data class SearchHit(
    val pageIndex: Int,
    /** Snippet with the matched text, already whitespace-normalised. */
    val snippet: String,
)
