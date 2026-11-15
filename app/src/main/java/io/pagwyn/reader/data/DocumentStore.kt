package io.pagwyn.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import io.pagwyn.reader.core.PagwynDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local document index and reading history.
 *
 * Stored in a single SQLite table so the home screen loads with one query and
 * no JSON parsing. Nothing here ever leaves the device.
 */
class DocumentStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    ${C.URI} TEXT PRIMARY KEY NOT NULL,
                    ${C.NAME} TEXT NOT NULL,
                    ${C.TITLE} TEXT NOT NULL,
                    ${C.SIZE} INTEGER NOT NULL DEFAULT -1,
                    ${C.PAGES} INTEGER NOT NULL DEFAULT 0,
                    ${C.LAST_PAGE} INTEGER NOT NULL DEFAULT 0,
                    ${C.PROGRESS} REAL NOT NULL DEFAULT 0,
                    ${C.LAST_OPENED} INTEGER NOT NULL DEFAULT 0,
                    ${C.ADDED} INTEGER NOT NULL DEFAULT 0,
                    ${C.STARRED} INTEGER NOT NULL DEFAULT 0,
                    ${C.LOCATION} TEXT,
                    ${C.OFFSET} REAL NOT NULL DEFAULT 0,
                    ${C.ZOOM} REAL NOT NULL DEFAULT 0,
                    ${C.ZOOM_MODE} INTEGER NOT NULL DEFAULT 0,
                    ${C.ROTATION} INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_last_opened ON $TABLE (${C.LAST_OPENED} DESC)")
            db.execSQL("CREATE INDEX idx_starred ON $TABLE (${C.STARRED}, ${C.LAST_OPENED} DESC)")
            db.execSQL(
                "CREATE TABLE $TABLE_BOOKMARKS (" +
                    "${B.URI} TEXT NOT NULL, ${B.PAGE} INTEGER NOT NULL, " +
                    "PRIMARY KEY (${B.URI}, ${B.PAGE}))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Reading history is a convenience cache: rebuilding is acceptable and
            // avoids fragile partial migrations. Documents are never touched.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_BOOKMARKS")
            onCreate(db)
        }
    }

    private object C {
        const val URI = "uri"
        const val NAME = "name"
        const val TITLE = "title"
        const val SIZE = "size"
        const val PAGES = "pages"
        const val LAST_PAGE = "last_page"
        const val PROGRESS = "progress"
        const val LAST_OPENED = "last_opened"
        const val ADDED = "added_at"
        const val STARRED = "starred"
        const val LOCATION = "location"
        const val OFFSET = "offset_pt"
        const val ZOOM = "zoom"
        const val ZOOM_MODE = "zoom_mode"
        const val ROTATION = "rotation"
    }

    private object B {
        const val URI = "uri"
        const val PAGE = "page"
    }

    fun documents(): List<PagwynDocument> = query(
        selection = null,
        args = null,
        order = "${C.LAST_OPENED} DESC",
    )

    fun recents(limit: Int): List<PagwynDocument> = query(
        selection = null,
        args = null,
        order = "${C.LAST_OPENED} DESC",
        limit = limit,
    )

    fun starred(): List<PagwynDocument> = query(
        selection = "${C.STARRED} = 1",
        args = null,
        order = "${C.LAST_OPENED} DESC",
    )

    fun history(limit: Int = 200): List<PagwynDocument> = query(
        selection = "${C.LAST_OPENED} > 0",
        args = null,
        order = "${C.LAST_OPENED} DESC",
        limit = limit,
    )

    fun find(uri: Uri): PagwynDocument? = query(
        selection = "${C.URI} = ?",
        args = arrayOf(uri.toString()),
        order = null,
    ).firstOrNull()

    fun search(query: String): List<PagwynDocument> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return this.query(
            selection = "(${C.TITLE} LIKE ? OR ${C.NAME} LIKE ?)",
            args = arrayOf("%$trimmed%", "%$trimmed%"),
            order = "${C.LAST_OPENED} DESC",
        )
    }

    /** Inserts or updates the document record. Existing history is preserved. */
    fun upsert(document: PagwynDocument) {
        val existing = find(Uri.parse(document.uriString))
        val merged = if (existing == null) {
            document
        } else {
            existing.copy(
                displayName = document.displayName,
                sizeBytes = if (document.sizeBytes > 0) document.sizeBytes else existing.sizeBytes,
                pageCount = if (document.pageCount > 0) document.pageCount else existing.pageCount,
                location = document.location ?: existing.location,
                // Keep user controlled state.
                isStarred = existing.isStarred,
                lastPage = if (document.lastPage > 0) document.lastPage else existing.lastPage,
                lastOpenedAt = maxOf(existing.lastOpenedAt, document.lastOpenedAt),
            )
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            merged.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun touch(uri: Uri, pageCount: Int? = null, displayName: String? = null) {
        val existing = find(uri) ?: return
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            lastOpenedAt = now,
            pageCount = pageCount ?: existing.pageCount,
            displayName = displayName ?: existing.displayName,
        )
        helper.writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            updated.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Saves the reading position. Cheap enough to call on every page change. */
    fun saveProgress(
        uri: Uri,
        page: Int,
        pageCount: Int,
        offsetPt: Float,
        zoom: Float,
        zoomMode: Int,
        rotation: Int,
    ) {
        val progress = if (pageCount > 0) ((page + offsetPt.coerceAtLeast(0f) / 1000f).coerceIn(0f, pageCount.toFloat()) / pageCount) else 0f
        val values = ContentValues().apply {
            put(C.LAST_PAGE, page.coerceAtLeast(0))
            put(C.PROGRESS, progress.coerceIn(0f, 1f))
            put(C.OFFSET, offsetPt)
            put(C.ZOOM, zoom)
            put(C.ZOOM_MODE, zoomMode)
            put(C.ROTATION, rotation)
            put(C.LAST_OPENED, System.currentTimeMillis())
        }
        helper.writableDatabase.update(TABLE, values, "${C.URI} = ?", arrayOf(uri.toString()))
    }

    fun setStarred(uri: Uri, starred: Boolean) {
        val values = ContentValues().apply { put(C.STARRED, if (starred) 1 else 0) }
        helper.writableDatabase.update(TABLE, values, "${C.URI} = ?", arrayOf(uri.toString()))
    }

    fun remove(uri: Uri) {
        val db = helper.writableDatabase
        db.delete(TABLE, "${C.URI} = ?", arrayOf(uri.toString()))
        db.delete(TABLE_BOOKMARKS, "${B.URI} = ?", arrayOf(uri.toString()))
    }

    fun clearHistory() {
        val db = helper.writableDatabase
        db.delete(TABLE, null, null)
        db.delete(TABLE_BOOKMARKS, null, null)
    }

    // ------------------------------------------------------------------ bookmarks

    fun bookmarks(uri: Uri): List<Int> {
        val result = mutableListOf<Int>()
        helper.readableDatabase.query(
            TABLE_BOOKMARKS,
            arrayOf(B.PAGE),
            "${B.URI} = ?",
            arrayOf(uri.toString()),
            null,
            null,
            "${B.PAGE} ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getInt(0))
        }
        return result
    }

    fun addBookmark(uri: Uri, page: Int) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE_BOOKMARKS,
            null,
            ContentValues().apply {
                put(B.URI, uri.toString())
                put(B.PAGE, page)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun removeBookmark(uri: Uri, page: Int) {
        helper.writableDatabase.delete(
            TABLE_BOOKMARKS,
            "${B.URI} = ? AND ${B.PAGE} = ?",
            arrayOf(uri.toString(), page.toString()),
        )
    }

    fun toggleBookmark(uri: Uri, page: Int): Boolean {
        val existing = bookmarks(uri).contains(page)
        if (existing) removeBookmark(uri, page) else addBookmark(uri, page)
        return !existing
    }

    /** Trim history so the database stays small on long-lived installs. */
    suspend fun trimHistory(limit: Int) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE ${C.STARRED} = 0 AND ${C.URI} NOT IN " +
                "(SELECT ${C.URI} FROM $TABLE ORDER BY ${C.LAST_OPENED} DESC LIMIT ?)",
            arrayOf(limit),
        )
    }

    fun close() = helper.close()

    // ------------------------------------------------------------------ internals

    private fun query(
        selection: String?,
        args: Array<String>?,
        order: String?,
        limit: Int? = null,
    ): List<PagwynDocument> {
        val out = ArrayList<PagwynDocument>()
        val db = runCatching { helper.readableDatabase }.getOrNull() ?: return out
        runCatching {
            db.query(TABLE, null, selection, args, null, null, order, limit?.toString())
                .use { cursor ->
                    while (cursor.moveToNext()) out.add(cursor.toDocument())
                }
        }
        return out
    }

    private fun Cursor.toDocument() = PagwynDocument(
        uriString = getString(getColumnIndexOrThrow(C.URI)),
        displayName = getString(getColumnIndexOrThrow(C.NAME)),
        title = getString(getColumnIndexOrThrow(C.TITLE)),
        sizeBytes = getLong(getColumnIndexOrThrow(C.SIZE)),
        pageCount = getInt(getColumnIndexOrThrow(C.PAGES)),
        lastPage = getInt(getColumnIndexOrThrow(C.LAST_PAGE)),
        progress = getFloat(getColumnIndexOrThrow(C.PROGRESS)),
        lastOpenedAt = getLong(getColumnIndexOrThrow(C.LAST_OPENED)),
        addedAt = getLong(getColumnIndexOrThrow(C.ADDED)),
        isStarred = getInt(getColumnIndexOrThrow(C.STARRED)) == 1,
        location = getString(getColumnIndexOrThrow(C.LOCATION)),
        lastOffsetPt = getFloat(getColumnIndexOrThrow(C.OFFSET)),
        lastZoom = getFloat(getColumnIndexOrThrow(C.ZOOM)),
        lastZoomMode = getInt(getColumnIndexOrThrow(C.ZOOM_MODE)),
        lastRotation = getInt(getColumnIndexOrThrow(C.ROTATION)),
    )

    private fun PagwynDocument.toValues() = ContentValues().apply {
        put(C.URI, uriString)
        put(C.NAME, displayName)
        put(C.TITLE, title)
        put(C.SIZE, sizeBytes)
        put(C.PAGES, pageCount)
        put(C.LAST_PAGE, lastPage)
        put(C.PROGRESS, progress)
        put(C.LAST_OPENED, lastOpenedAt)
        put(C.ADDED, addedAt)
        put(C.STARRED, if (isStarred) 1 else 0)
        put(C.LOCATION, location)
        put(C.OFFSET, lastOffsetPt)
        put(C.ZOOM, lastZoom)
        put(C.ZOOM_MODE, lastZoomMode)
        put(C.ROTATION, lastRotation)
    }

    companion object {
        private const val DB_NAME = "pagwyn.db"
        private const val DB_VERSION = 1
        private const val TABLE = "documents"
        private const val TABLE_BOOKMARKS = "bookmarks"
    }
}
