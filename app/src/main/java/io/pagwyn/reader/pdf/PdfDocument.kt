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
     * `PdfRenderer` owns the rendering descriptor, and a descriptor number can be
     * reused as soon as it is closed, so resolving `/proc/self/fd/<n>` for text would
     * otherwise point at whichever document happened to take that number next. This
     * descriptor stays open for the whole session, so the path stays correct.
     */
    private val textDescriptor: ParcelFileDescriptor?,
    /** True when [PdfTextService] reads from a temporary cache copy. */
    private val textPathIsCopy: Boolean,
) : AutoCloseable {

    val pageCount: Int = renderer.pageCount

    private var closed = false
    private val dimensions = HashMap<Int, PageInfo>(pageCount.coerceAtMost(MAX_DIMENSION_CACHE))

    /**
     * Runs [block] with a fresh [PdfRenderer.Page]. The page is always closed so
     * the native layer never runs out of page handles.
     */
    private inline fun <T> withPage(index: Int, block: (PdfRenderer.Page) -> T): T {
        val page = renderer.openPage(index)
        try {
            return block(page)
        } finally {
            page.close()
        }
    }


    fun pageInfo(index: Int): PageInfo = dimensions.getOrPut(index) {
        withPage(index) { page ->
            PageInfo(
                index = index,
                widthPoints = page.width,
                heightPoints = page.height,
                rotation = 0,
            )
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
    ): Boolean {
        if (closed) return false
        return try {
            withPage(index) { page ->
                // PdfRenderer requires the bitmap to match the requested size closely.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, renderMode)
            }
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

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

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        textService?.close()
        runCatching { textDescriptor?.close() }
        // Delete the temporary copy made for text extraction, if one was needed.
        if (textPathIsCopy) runCatching { PdfDependencyLoader.releaseCopy(textService?.filePath) }
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
            val textDescriptor = openDescriptor(context, uri)
            val fdPath = textDescriptor?.let { PdfTextService.descriptorPath(it) }
            // `statSize` is the authoritative size of the open document; it is used to
            // validate any /proc path before text extraction trusts it.
            val descriptorSize = runCatching { textDescriptor?.statSize ?: -1L }.getOrDefault(-1L)
            val textPath = runCatching {
                PdfDependencyLoader.resolveReadablePath(context, uri, fdPath, descriptorSize)
            }.getOrNull()
            val textPathIsCopy = textPath != null && (fdPath == null || textPath != fdPath)
            if (!textPathIsCopy) textDescriptor?.closeSafely()

            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: SecurityException) {
                descriptor.closeSafely()
                throw PdfOpenException.NeedsPassword()
            } catch (e: IllegalStateException) {
                descriptor.closeSafely()
                throw PdfOpenException.Corrupt()
            } catch (e: IOException) {
                descriptor.closeSafely()
                throw PdfOpenException.Corrupt()
            } catch (e: OutOfMemoryError) {
                descriptor.closeSafely()
                throw PdfOpenException.TooLarge()
            }

            if (renderer.pageCount <= 0) {
                runCatching { renderer.close() }
                descriptor.closeSafely()
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
                textDescriptor = if (textPathIsCopy) null else textDescriptor,
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
