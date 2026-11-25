package dev.roastbook.app.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.os.BundleCompat
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * A Storage Access Framework tree over one plain directory, standing in for the folder a person
 * picks (a local folder or a cloud drive). It speaks the document URIs and the `call` methods that
 * `DocumentsContract` and `DocumentFile` use. (Robolectric's resolver only reaches a provider's
 * pre-Android-O query, which the platform's `DocumentsProvider` refuses, so this is a plain
 * provider.) The directory's document id is [ROOT_ID]; each file in it is a document whose id is its
 * name. [suffix] imitates providers that append their own extension to a new file's name;
 * [refuseRename] imitates one that will not rename; [nameless] one that cannot report some names;
 * [failWrites] one that is offline.
 */
class DirectoryDocumentsProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(id: String): File = if (id == ROOT_ID) dir else File(dir, id)

    private fun value(id: String, column: String): Any? {
        val f = fileOf(id)
        return when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> if (id == ROOT_ID) DISPLAY_NAME else f.name.takeIf { it !in nameless }
            Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_SIZE -> f.length()
            Document.COLUMN_LAST_MODIFIED -> f.lastModified()
            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val ids = if (uri.lastPathSegment == "children") dir.listFiles().orEmpty().map { it.name }.sorted()
        else listOf(DocumentsContract.getDocumentId(uri)).filter { fileOf(it).exists() }
        return MatrixCursor(columns).apply { ids.forEach { id -> addRow(columns.map { value(id, it) }) } }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failWrites && 'w' in mode) throw FileNotFoundException("offline")
        return ParcelFileDescriptor.open(fileOf(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val target = BundleCompat.getParcelable(extras!!, EXTRA_URI, Uri::class.java)!!
        val id = DocumentsContract.getDocumentId(target)
        return when (method) {
            METHOD_CREATE -> {
                val wanted = extras.getString(Document.COLUMN_DISPLAY_NAME) + suffix
                // Like real providers, a taken name gets a new document under a numbered name; the
                // existing document is never handed back as if it were new.
                var f = File(dir, wanted)
                var n = 1
                while (!f.createNewFile()) f = File(dir, "$wanted (${n++})")
                Bundle().apply { putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(target, f.name)) }
            }
            METHOD_RENAME -> {
                if (refuseRename) throw IllegalStateException("this provider does not rename")
                val renamed = File(dir, extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                check(fileOf(id).renameTo(renamed)) { "rename failed" }
                Bundle().apply { putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(target, renamed.name)) }
            }
            METHOD_DELETE -> {
                if (!fileOf(id).delete()) throw FileNotFoundException(id)
                Bundle()
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "dev.roastbook.test.documents"
        const val ROOT_ID = "root"
        const val DISPLAY_NAME = "Coffee backups"
        /** The platform's (hidden) names for the document operations `DocumentsContract` sends through `call`. */
        private const val EXTRA_URI = "uri"
        private const val METHOD_CREATE = "android:createDocument"
        private const val METHOD_RENAME = "android:renameDocument"
        private const val METHOD_DELETE = "android:deleteDocument"

        lateinit var dir: File
        var suffix = ""
        var refuseRename = false
        /** Fails every write while the folder itself stays readable, as a cloud folder does while offline. */
        var failWrites = false
        /** Documents whose display name this provider cannot report (it answers null). */
        val nameless = mutableSetOf<String>()

        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)

        /** Registers the provider over [directory] for the current test and returns the picked tree's URI. */
        fun install(directory: File): Uri {
            dir = directory.apply { mkdirs() }
            suffix = ""
            refuseRename = false
            failWrites = false
            nameless.clear()
            Robolectric.setupContentProvider(DirectoryDocumentsProvider::class.java, AUTHORITY)
            return treeUri
        }
    }
}
