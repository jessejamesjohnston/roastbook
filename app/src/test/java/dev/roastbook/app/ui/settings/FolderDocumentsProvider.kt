package dev.roastbook.app.ui.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * A Storage Access Framework folder over one plain directory, standing in for the folder a person
 * picks in the system file picker (Drive, an SD card). It speaks the documents protocol that
 * `DocumentFile` and `DocumentsContract` use: queries on document and children URIs, `call` for
 * create, rename and delete, and `openFile`. (It is a plain ContentProvider because
 * DocumentsProvider's query entry point is final and refuses the form Robolectric's resolver uses.)
 * Flat: document ids are `folder` and `folder/<file name>`.
 */
class FolderDocumentsProvider : ContentProvider() {
    lateinit var dir: File
    /** Answers every rename with an error, as some storage apps do. */
    var refuseRename = false
    /** Fails every write while the folder itself stays readable, as a cloud folder does while offline. */
    var failWrites = false

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: COLUMNS)
        val documentId = DocumentsContract.getDocumentId(uri)
        if (uri.pathSegments.last() == "children") {
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { addRow(cursor, "$ROOT/${it.name}") }
        } else {
            addRow(cursor, documentId)
        }
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val target = requireNotNull(extras?.getParcelable(EXTRA_URI, Uri::class.java))
        val documentId = DocumentsContract.getDocumentId(target)
        val out = Bundle()
        when (method) {
            METHOD_CREATE -> {
                val wanted = requireNotNull(extras.getString(Document.COLUMN_DISPLAY_NAME))
                // Like real providers, a taken name gets a new document under a numbered name.
                var f = File(dir, wanted)
                var n = 1
                while (!f.createNewFile()) f = File(dir, "$wanted (${n++})")
                out.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(target, "$ROOT/${f.name}"))
            }
            METHOD_RENAME -> {
                val name = requireNotNull(extras.getString(Document.COLUMN_DISPLAY_NAME))
                if (refuseRename) throw UnsupportedOperationException("rename")
                if (!fileFor(documentId).renameTo(File(dir, name))) throw FileNotFoundException(documentId)
                out.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(target, "$ROOT/$name"))
            }
            METHOD_DELETE -> if (!fileFor(documentId).delete()) throw FileNotFoundException(documentId)
            else -> throw UnsupportedOperationException(method)
        }
        return out
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failWrites && 'w' in mode) throw FileNotFoundException("offline")
        return ParcelFileDescriptor.open(fileFor(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private fun fileFor(documentId: String): File = if (documentId == ROOT) dir else File(dir, documentId.removePrefix("$ROOT/"))

    private fun addRow(cursor: MatrixCursor, documentId: String) {
        val f = fileFor(documentId)
        if (!f.exists()) return
        val values = mapOf(
            Document.COLUMN_DOCUMENT_ID to documentId,
            Document.COLUMN_DISPLAY_NAME to f.name,
            Document.COLUMN_MIME_TYPE to if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.COLUMN_SIZE to f.length(),
            Document.COLUMN_LAST_MODIFIED to f.lastModified(),
            Document.COLUMN_FLAGS to (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0),
        )
        cursor.addRow(cursor.columnNames.map { values[it] })
    }

    companion object {
        const val AUTHORITY = "dev.roastbook.test.folder"
        const val ROOT = "folder"
        // The framework's (hidden) DocumentsContract method names and extra key.
        private const val METHOD_CREATE = "android:createDocument"
        private const val METHOD_RENAME = "android:renameDocument"
        private const val METHOD_DELETE = "android:deleteDocument"
        private const val EXTRA_URI = "uri"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** The tree URI the picker would return for this folder. */
        val TREE: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

        /** Installs the provider over [dir]; it answers until the test ends. */
        fun install(dir: File): FolderDocumentsProvider {
            dir.mkdirs()
            val info = ProviderInfo().apply { authority = AUTHORITY }
            return Robolectric.buildContentProvider(FolderDocumentsProvider::class.java).create(info).get().also { it.dir = dir }
        }
    }
}
