package io.pagwyn.reader.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Placeholder for optional native text-extraction libraries.
 *
 * Pagwyn's text extraction is written in Kotlin (`PdfParser`) and therefore needs
 * no native library at all. Keeping this hook means a future JNI-based extractor
 * can be added behind the same call site without touching the reader UI, and it
 * documents the decision explicitly.
 */
internal object PdfDependencyLoader {
    private const val TAG = "PagwynNative"

    @Volatile
    private var checked = false

    fun ensureLoaded(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (checked) return
        checked = true
        Log.d(TAG, "Pure Kotlin PDF text engine in use (no native dependency).")
    }

    /**
     * Returns a filesystem path for [uri], or null when the document only exists
     * behind a content provider.
     *
     * Text extraction needs random access to the raw PDF bytes. Documents from the
     * Storage Access Framework are usually real files and resolve directly through
     * `/proc/self/fd`. A few providers (for example a test FileProvider that serves
     * from its own cache, or a cloud provider streaming bytes) expose no path at
     * all; for those the bytes are copied into Pagwyn's own cache first. That copy
     * is capped at [COPY_LIMIT_BYTES] so a hostile or huge document cannot fill the
     * disk, and it is deleted when the reader closes the document.
     */
    fun resolveReadablePath(context: Context, uri: Uri, fdPath: String?): String? {
        if (fdPath != null && File(fdPath).let { it.isFile && it.canRead() }) return fdPath
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return path.takeIf { File(it).isFile && File(it).canRead() }
        }
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, "doc-" + Integer.toHexString(uri.toString().hashCode()) + ".pdf")
        // Always copy afresh: a partially written file from an interrupted copy would
        // otherwise be reused and silently disable text extraction.
        runCatching { target.delete() }
        val copied = runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > COPY_LIMIT_BYTES) break
                        sink.write(buffer, 0, read)
                    }
                    total in 1..COPY_LIMIT_BYTES
                }
            }
        }.getOrDefault(false)

        if (!copied) {
            runCatching { target.delete() }
            Log.d(TAG, "Document is not available for text extraction.")
            return null
        }
        return target.absolutePath
    }

    /**
     * Removes a cache copy created by [resolveReadablePath]; a no-op for real
     * documents. Only files inside a `text-cache` directory are ever deleted, so a
     * user's PDF can never be removed by mistake.
     */
    fun releaseCopy(path: String?) {
        if (path == null) return
        val file = File(path)
        if (file.parentFile?.name != CACHE_DIR) return
        runCatching { file.delete() }
    }

    private const val CACHE_DIR = "text-cache"
    private const val COPY_LIMIT_BYTES = 64L * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 64 * 1024
}
