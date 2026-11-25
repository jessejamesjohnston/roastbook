package dev.roastbook.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One existing backup in the destination folder. */
data class BackupFile(val name: String, val size: Long, val lastModified: Long, val open: () -> InputStream?)

/**
 * Where zips go. Production is a Storage Access Framework tree the user picked once; tests use a
 * plain directory. Writes go to a temporary name and are renamed when complete, so a cloud
 * provider watching the folder only ever uploads finished files.
 */
interface BackupSink {
    fun list(): List<BackupFile>
    fun openForWrite(tempName: String): OutputStream
    /** Renames the completed temp file; returns false if the provider refused (the temp name then stays). */
    fun finish(tempName: String, finalName: String): Boolean
    fun delete(name: String): Boolean
    fun displayName(): String?
    /** The picked folder this sink writes to, when it is one (the stored backup folder). */
    val folderUri: String? get() = null
}

/**
 * [BackupSink] over a Storage Access Framework tree the user picked. [finish] reports success only
 * when this run's own document now carries the final name, not merely when some file has it.
 */
class SafSink(private val context: Context, private val tree: Uri) : BackupSink {
    private val dir: DocumentFile get() = DocumentFile.fromTreeUri(context, tree) ?: error("Backup folder is not reachable")
    /** The document each temp name was created as: providers may alter the display name (e.g. append an extension). */
    private val inFlight = mutableMapOf<String, DocumentFile>()
    override val folderUri: String get() = tree.toString()

    override fun displayName(): String? = runCatching { dir.name }.getOrNull()

    override fun list(): List<BackupFile> = dir.listFiles().filter { it.isFile }.map { f ->
        BackupFile(f.name ?: "", f.length(), f.lastModified()) { context.contentResolver.openInputStream(f.uri) }
    }

    override fun openForWrite(tempName: String): OutputStream {
        dir.findFile(tempName)?.delete()
        // octet-stream, not application/zip: the local-storage provider appends the MIME type's
        // extension to a name that lacks it, so "x.zip.tmp" would become "x.zip.tmp.zip".
        val doc = dir.createFile("application/octet-stream", tempName) ?: error("Could not create a file in the backup folder")
        inFlight[tempName] = doc
        return context.contentResolver.openOutputStream(doc.uri, "w") ?: error("Could not open the backup file for writing")
    }

    override fun finish(tempName: String, finalName: String): Boolean {
        val tmp = inFlight[tempName] ?: dir.findFile(tempName) ?: return false
        // A rename keeps the document URI (confirmed on a device), and a cloud provider uploads the
        // finished file once rather than the partial one.
        val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, tmp.uri, finalName) }.getOrNull()
        // Success means OUR document now carries the final name. Another file that happens to have
        // that name (a synced copy from another device) is not proof, and a refused rename leaves
        // ours under the temp name.
        // A provider that can't report the name falls back to identity: the provider said it renamed,
        // or the file under the final name is our document (rename kept its URI).
        val name = runCatching { DocumentFile.fromSingleUri(context, renamed ?: tmp.uri)?.name }.getOrNull()
        val landed = if (name != null) name == finalName else renamed != null || dir.findFile(finalName)?.uri == tmp.uri
        if (landed) inFlight.remove(tempName)
        return landed
    }

    /** Deletes the document created for [name] when we made it in this run (its display name may differ), else by name. */
    override fun delete(name: String): Boolean = (inFlight.remove(name) ?: dir.findFile(name))?.delete() ?: false
}

/** [BackupSink] over a plain directory, created if missing; used by tests. */
class DirectorySink(private val dir: File) : BackupSink {
    init { dir.mkdirs() }
    override fun displayName(): String = dir.name
    override fun list(): List<BackupFile> = dir.listFiles()
        ?.filter { it.isFile }
        ?.map { f -> BackupFile(f.name, f.length(), f.lastModified()) { f.inputStream() } } ?: emptyList()
    override fun openForWrite(tempName: String): OutputStream = File(dir, tempName).outputStream()
    override fun finish(tempName: String, finalName: String): Boolean = File(dir, tempName).renameTo(File(dir, finalName))
    override fun delete(name: String): Boolean = File(dir, name).delete()
}
