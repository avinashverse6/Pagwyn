package io.pagwyn.reader.ui.metadata

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.pagwyn.reader.PagwynApplication
import io.pagwyn.reader.R
import io.pagwyn.reader.core.DocumentResolver
import io.pagwyn.reader.core.Formatters
import io.pagwyn.reader.core.Intents
import io.pagwyn.reader.pdf.PdfDocument
import io.pagwyn.reader.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Document information: file facts (name, size, pages, page size) plus the reading
 * state Pagwyn keeps. Everything is read locally from the document.
 *
 * This screen is deliberately the only place PDF-level detail is surfaced, keeping
 * the reading experience free of metadata clutter.
 */
class MetadataActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stored = PagwynApplication.settings.theme
        AppCompatDelegate.setDefaultNightMode(
            when (stored) {
                io.pagwyn.reader.data.AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                io.pagwyn.reader.data.AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                io.pagwyn.reader.data.AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
        setContentView(R.layout.activity_metadata)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        rows = findViewById(R.id.metadataRows)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }

        findViewById<Button>(R.id.btnOpenDocument).setOnClickListener {
            startActivity(ReaderActivity.intent(this, uri))
        }
        findViewById<Button>(R.id.btnShareDocument).setOnClickListener {
            Intents.shareDocument(this, uri, findViewById<TextView>(R.id.metadataTitleText).text?.toString())
        }
        findViewById<ImageButton>(R.id.btnCopy).setOnClickListener {
            copySummary(uri)
        }

        load(uri)
    }

    private fun load(uri: Uri) {
        lifecycleScope.launch {
            val app = application
            val source = withContext(Dispatchers.IO) { DocumentResolver.resolve(app, uri) }
            findViewById<TextView>(R.id.metadataTitleText).text = source.title

            val record = withContext(Dispatchers.IO) { PagwynApplication.documents.find(uri) }
            val info = withContext(Dispatchers.IO) { readDocumentInfo(app, uri) }

            val pageCount = info.pageCount.takeIf { it > 0 } ?: record?.pageCount ?: 0
            val size = info.sizeBytes.takeIf { it > 0 } ?: source.size
            findViewById<TextView>(R.id.metadataSubtitle).text = buildString {
                if (pageCount > 0) append("$pageCount pages")
                if (size > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(Formatters.fileSize(size))
                }
            }

            rows.removeAllViews()
            addRow(R.string.metadata_name, source.displayName)
            if (size > 0) addRow(R.string.metadata_size, Formatters.fileSize(size))
            if (pageCount > 0) addRow(R.string.metadata_pages, pageCount.toString())
            if (info.pageSize != null) addRow(R.string.metadata_page_size, info.pageSize)
            addRow(
                R.string.metadata_encrypted,
                getString(if (info.encrypted) R.string.metadata_encrypted_yes else R.string.metadata_encrypted_no),
            )
            source.location?.let { addRow(R.string.metadata_location, it) }
            addRow(
                R.string.metadata_producer,
                "Android PdfRenderer · ${android.os.Build.VERSION.RELEASE}",
            )
            record?.let { doc ->
                addRow(R.string.metadata_added, Formatters.absoluteDate(doc.addedAt))
                if (doc.lastOpenedAt > 0) {
                    addRow(R.string.metadata_last_read, Formatters.absoluteDateTime(doc.lastOpenedAt))
                }
                if (doc.pageCount > 0) {
                    addRow(
                        R.string.metadata_progress,
                        "${doc.lastPage + 1} / ${doc.pageCount} · ${Formatters.percent(doc.progress)}%",
                    )
                }
            }
            addRow(R.string.metadata_location, uri.toString())
        }
    }

    private data class DocumentInfo(
        val pageCount: Int,
        val sizeBytes: Long,
        val pageSize: String?,
        val encrypted: Boolean,
    )

    private fun readDocumentInfo(app: android.app.Application, uri: Uri): DocumentInfo {
        var size = if (uri.scheme == "file") File(uri.path.orEmpty()).length() else -1L
        return runCatching {
            val descriptor = if (uri.scheme == "file") {
                android.os.ParcelFileDescriptor.open(
                    File(uri.path.orEmpty()),
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
            } else {
                app.contentResolver.openFileDescriptor(uri, "r")
            } ?: return DocumentInfo(0, size, null, false)
            if (size < 0) size = runCatching { descriptor.statSize }.getOrDefault(-1L)
            descriptor.use { pfd ->
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    val count = renderer.pageCount
                    val pageSize = if (count > 0) {
                        renderer.openPage(0).use { page ->
                            Formatters.pageSize(page.width, page.height)
                        }
                    } else {
                        null
                    }
                    DocumentInfo(count, size, pageSize, encrypted = false)
                } finally {
                    runCatching { renderer.close() }
                }
            }
        }.getOrElse {
            DocumentInfo(0, size, null, it is SecurityException)
        }
    }

    private fun addRow(labelRes: Int, value: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_metadata_row, rows, false)
        row.findViewById<TextView>(R.id.rowLabel).setText(labelRes)
        row.findViewById<TextView>(R.id.rowValue).text = value
        rows.addView(row)
    }

    private fun copySummary(uri: Uri) {
        val title = findViewById<TextView>(R.id.metadataTitleText).text
        val subtitle = findViewById<TextView>(R.id.metadataSubtitle).text
        val summary = buildString {
            append(title).append('\n').append(subtitle).append('\n').append(uri)
            for (i in 0 until rows.childCount) {
                val row = rows.getChildAt(i)
                val label = row.findViewById<TextView>(R.id.rowLabel).text
                val value = row.findViewById<TextView>(R.id.rowValue).text
                append('\n').append(label).append(": ").append(value)
            }
        }
        val clipboard = getSystemService<ClipboardManager>()
        clipboard?.setPrimaryClip(ClipData.newPlainText(title, summary))
        Toast.makeText(this, R.string.metadata_copy_path, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, MetadataActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}
