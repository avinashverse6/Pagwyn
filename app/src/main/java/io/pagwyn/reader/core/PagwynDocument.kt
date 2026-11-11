package io.pagwyn.reader.core

import android.net.Uri

/**
 * A document Pagwyn has opened before.
 *
 * [uri] is persisted with read permission through the Storage Access Framework,
 * so no storage permission is required to reopen it later. If the grant is ever
 * revoked the entry is marked inaccessible instead of being silently dropped.
 */
data class PagwynDocument(
    val uriString: String,
    val displayName: String,
    val title: String,
    val sizeBytes: Long,
    val pageCount: Int,
    /** Last read page, 0-based. */
    val lastPage: Int,
    /** Fractional reading progress, 0..1. */
    val progress: Float,
    val lastOpenedAt: Long,
    val addedAt: Long,
    val isStarred: Boolean,
    val location: String?,
    /** Reading offset in points from the top of [lastPage], for position restore. */
    val lastOffsetPt: Float = 0f,
    /** Zoom scale that was in use, 0 when the default should apply. */
    val lastZoom: Float = 0f,
    /** 0 = fit width, 1 = fit page, 2 = actual size. */
    val lastZoomMode: Int = 0,
    val lastRotation: Int = 0,
) {
    val uri: Uri get() = Uri.parse(uriString)

    /** Human readable label such as "42 of 380". */
    val pageSummary: String
        get() = if (pageCount > 0) "${lastPage + 1} of $pageCount" else "—"

    companion object {
        fun create(
            source: DocumentSource,
            pageCount: Int,
            now: Long = System.currentTimeMillis(),
        ): PagwynDocument = PagwynDocument(
            uriString = source.uri.toString(),
            displayName = source.displayName,
            title = source.title,
            sizeBytes = source.size,
            pageCount = pageCount,
            lastPage = 0,
            progress = 0f,
            lastOpenedAt = now,
            addedAt = now,
            isStarred = false,
            location = source.location,
        )
    }
}
