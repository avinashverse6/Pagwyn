package io.pagwyn.reader

import android.util.Log

/**
 * Failure context for device tests that has to survive the CI report.
 *
 * The JUnit XML for the API 24 run sometimes records a failure with an empty body, so
 * the assertion message never reaches the build report. Logcat is always captured, and
 * the report keeps every line mentioning "pagwyn", so a harness log line under this tag
 * is the one reliable channel.
 */
object TestDiagnostics {

    private const val TAG = "PagwynTest"

    fun log(message: String) {
        Log.e(TAG, message)
    }
}
