package io.pagwyn.reader

import android.app.Application
import io.pagwyn.reader.data.DocumentStore
import io.pagwyn.reader.data.Settings
import io.pagwyn.reader.render.PageBitmapCache

/**
 * Process-wide singletons.
 *
 * Pagwyn deliberately avoids a dependency-injection framework: with two stores
 * and one cache, a plain holder is smaller, faster to start and easier to audit.
 * Everything here is created lazily so cold start stays under a few frames.
 */
class PagwynApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        private var instance: PagwynApplication? = null

        val settings: Settings by lazy { Settings(current()) }

        val documents: DocumentStore by lazy { DocumentStore(current()) }

        /**
         * Shared bitmap cache across reader sessions, so reopening the last
         * document is instant. Sized from the device's memory class.
         */
        val pageCache: PageBitmapCache by lazy { PageBitmapCache.forDevice() }

        fun current(): Application = instance ?: error("PagwynApplication not created")
    }
}

/** Convenience accessor used where an Application reference is inconvenient. */
internal object PagwynAppContext {
    fun get(): Application? = runCatching { PagwynApplication.current() }.getOrNull()
}
