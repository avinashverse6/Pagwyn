package io.pagwyn.reader.ui.reader

import io.pagwyn.reader.data.ReadingMode
import io.pagwyn.reader.data.ReaderTheme
import io.pagwyn.reader.data.ZoomMode
import io.pagwyn.reader.pdf.SearchHit

/** Immutable snapshot of everything the reader chrome renders. */
data class ReaderState(
    val loading: Boolean = true,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val documentTitle: String = "",
    val documentSubtitle: String = "",
    val isBookmarked: Boolean = false,
    val mode: ReadingMode = ReadingMode.CONTINUOUS,
    val zoomMode: ZoomMode = ZoomMode.FIT_WIDTH,
    val zoomPercent: Int = 100,
    val theme: ReaderTheme = ReaderTheme.FOLLOW_APP,
    val rotation: Int = 0,
    val controlsVisible: Boolean = true,
    val fullscreen: Boolean = false,
    val error: ReaderError? = null,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val searchRunning: Boolean = false,
    val textLayerAvailable: Boolean = true,
    /** True once the user scrolled away from the resume position. */
    val canJumpBack: Boolean = false,
    val jumpBackPage: Int = 0,
) {
    val progressFraction: Float
        get() = if (pageCount <= 1) 0f else currentPage.toFloat() / (pageCount - 1)

    val pageLabel: String get() = "${currentPage + 1} / ${pageCount.coerceAtLeast(0)}"
}

/** User-facing failure states with a clear next action. */
sealed class ReaderError(val titleRes: Int, val bodyRes: Int, val retryable: Boolean) {
    data object Password : ReaderError(
        io.pagwyn.reader.R.string.password_title,
        io.pagwyn.reader.R.string.password_message,
        retryable = true,
    )

    data object WrongPassword : ReaderError(
        io.pagwyn.reader.R.string.password_title,
        io.pagwyn.reader.R.string.password_wrong,
        retryable = true,
    )

    data object UnsupportedEncryption : ReaderError(
        io.pagwyn.reader.R.string.password_unsupported_title,
        io.pagwyn.reader.R.string.password_unsupported_message,
        retryable = false,
    )

    data object Corrupt : ReaderError(
        io.pagwyn.reader.R.string.reader_open_failed,
        io.pagwyn.reader.R.string.metadata_open_failed,
        retryable = false,
    )

    data object NotFound : ReaderError(
        io.pagwyn.reader.R.string.reader_open_failed,
        io.pagwyn.reader.R.string.metadata_open_failed,
        retryable = false,
    )

    data object TooLarge : ReaderError(
        io.pagwyn.reader.R.string.reader_open_failed,
        io.pagwyn.reader.R.string.error_generic,
        retryable = true,
    )
}
