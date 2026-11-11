package io.pagwyn.reader.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** What Pagwyn knows about a document's origin, before the PDF is parsed. */
data class DocumentSource(
    val uri: Uri,
    /** Display name, e.g. "Deep Work.pdf". */
    val displayName: String,
    /** Size in bytes, or -1 when the provider does not report one. */
    val size: Long,
    /** Human readable location, e.g. "Downloads" or "Google Drive" or a folder path. */
    val location: String?,
    val lastModified: Long,
    val mimeType: String?,
) {
    /** Stable identity for persistence: the URI string without volatile query parts. */
    val key: String get() = uri.toString()

    val title: String get() = Formatters.titleFromFileName(displayName)
}

/**
 * Resolves document metadata from a content:// or file:// URI.
 *
 * Everything is best-effort: content providers are allowed to withhold any of
 * this information, so every field has a safe fallback.
 */
object DocumentResolver {

    fun resolve(context: Context, uri: Uri): DocumentSource {
        var name: String? = null
        var size = -1L
        var modified = 0L

        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, OpenableColumns.LAST_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        val modIndex = cursor.getColumnIndex(OpenableColumns.LAST_MODIFIED)
                        if (modIndex >= 0 && !cursor.isNull(modIndex)) modified = cursor.getLong(modIndex)
                    }
                }
            }
        }

        val scheme = uri.scheme
        if (name.isNullOrBlank()) {
            name = when (scheme) {
                "file" -> uri.lastPathSegment?.let { File(it).name }
                else -> uri.lastPathSegment?.substringAfterLast('/')
            }
        }

        if (size < 0 && scheme == "file") {
            runCatching { size = File(uri.path ?: "").length() }.getOrNull()
        }

        val mime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                (name ?: "").substringAfterLast('.', "").lowercase(),
            )

        val displayName = name?.takeIf { it.isNotBlank() } ?: "Document.pdf"

        return DocumentSource(
            uri = uri,
            displayName = displayName,
            size = size,
            location = describeLocation(context, uri),
            lastModified = modified,
            mimeType = mime ?: Intents.MIME_PDF,
        )
    }

    /** A short, human friendly origin label. Never leaks a raw opaque ID. */
    fun describeLocation(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return File(path).parent
        }
        val authority = uri.authority ?: return null
        return when {
            authority.contains("externalstorage") || authority.contains("media") -> {
                // content://com.android.externalstorage.documents/document/primary%3ADownload%2Ffile.pdf
                val decoded = Uri.decode(uri.lastPathSegment ?: "")
                val afterColon = decoded.substringAfter(':', decoded)
                val folder = afterColon.substringBeforeLast('/', "")
                when {
                    folder.isEmpty() -> "Internal storage"
                    folder.equals("Download", true) || folder.equals("Downloads", true) -> "Downloads"
                    folder.equals("Documents", true) -> "Documents"
                    else -> folder
                }
            }
            authority.contains("downloads") -> "Downloads"
            authority.contains("drive") -> "Google Drive"
            authority.contains("onedrive") -> "OneDrive"
            authority.contains("dropbox") -> "Dropbox"
            authority.contains("box") -> "Box"
            else -> {
                // Fall back to the provider's label, which is usually the app name.
                runCatching {
                    val info = context.packageManager.resolveContentProvider(authority, 0)
                    info?.loadLabel(context.packageManager)?.toString()
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }

    /** True when the URI looks like something PdfRenderer can open. */
    fun looksLikePdf(context: Context, uri: Uri, name: String? = null, mime: String? = null): Boolean {
        val resolvedMime = mime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (resolvedMime == Intents.MIME_PDF) return true
        val candidate = name ?: uri.lastPathSegment ?: return false
        return candidate.endsWith(".pdf", ignoreCase = true)
    }
}
