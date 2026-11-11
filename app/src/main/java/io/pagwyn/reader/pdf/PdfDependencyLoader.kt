package io.pagwyn.reader.pdf

import android.content.Context
import android.util.Log

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
}
