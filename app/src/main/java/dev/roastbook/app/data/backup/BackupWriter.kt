package dev.roastbook.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.roastbook.app.data.backup.BackupManager.Companion.DB_ENTRY
import dev.roastbook.app.data.backup.BackupManager.Companion.MANIFEST_ENTRY
import dev.roastbook.app.data.backup.BackupManager.Companion.MAX_ENTRIES
import dev.roastbook.app.data.backup.BackupManager.Companion.MAX_MANIFEST_BYTES
import dev.roastbook.app.data.backup.BackupManager.Companion.OUR_ZIP
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import java.io.File
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.withLock

/**
 * Writes one Tier 1 archive into a folder and rotates the older ones out. The archive is written
 * under a temp name and renamed only once complete, so the folder never holds a partial backup
 * under a real name. Callers hold [BackupManager]'s lock.
 */
internal class BackupWriter(
    private val context: Context,
    private val db: RoastbookDatabase,
    private val photos: PhotoStore,
    private val snapshots: AutoSnapshot,
) {
    /** Writes a new archive into [sink], stamped [now], and returns what it holds. */
    suspend fun writeZip(sink: BackupSink, now: Long): BackupSummary {
        val target = File(context.cacheDir, "backup-${UUID.randomUUID()}.db")
        // The database copy and the photo listing are taken together, with no delete in between.
        val (dbCopy, photoFiles) = photos.captureLock.withLock {
            snapshots.snapshotDatabase(target) to photos.root.walkTopDown().filter { it.isFile }.toList()
        }
        try {
            // Counts come from the copy the zip actually carries, not from the live database: a write
            // landing between the VACUUM and the manifest would otherwise make the two disagree.
            val counts = countRows(dbCopy)
            val finalName = nextName(sink, now)
            val tempName = "$finalName.tmp"
            var written = 0L
            try {
                val counting = object : OutputStream() {
                    val inner = sink.openForWrite(tempName)
                    override fun write(b: Int) { inner.write(b); written++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); written += len }
                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
                ZipOutputStream(counting.buffered()).use { zip -> writeEntries(zip, dbCopy, photoFiles, counts, now) }
                // A refused rename leaves a file the user's cloud folder will never sync and the next
                // restore will never offer: that is a failed backup, not a quiet success.
                if (!sink.finish(tempName, finalName)) throw BackupFolderRefused(finalName)
            } catch (t: Throwable) {
                runCatching { sink.delete(tempName) }
                throw t
            }
            return BackupSummary(finalName, written, counts.first, photoFiles.size, now)
        } finally {
            dbCopy.delete()
        }
    }

    /**
     * Two backups in the same second must not overwrite each other, and a suffix the rotation freed
     * is never handed out again: reusing one would put the newest archive at the bottom of the
     * ordering that decides what gets deleted next.
     */
    private fun nextName(sink: BackupSink, now: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val highest = sink.list().mapNotNull { key(it.name) }.filter { it.first == stamp }.maxOfOrNull { it.second } ?: 0
        return if (highest == 0) "roastbook-$stamp.zip" else "roastbook-$stamp-${highest + 1}.zip"
    }

    /** The database copy, every photo, then the manifest that describes them. */
    private fun writeEntries(zip: ZipOutputStream, dbCopy: File, photoFiles: List<File>, counts: Pair<Int, Int>, now: Long) {
        zip.setLevel(Deflater.BEST_SPEED)
        val files = mutableListOf<ManifestFile>()
        files += putFile(zip, DB_ENTRY, dbCopy)
        for (f in photoFiles) files += putFile(zip, "photos/" + f.relativeTo(photos.root).path.replace(File.separatorChar, '/'), f)
        val manifest = BackupManifest(
            schemaVersion = db.openHelper.readableDatabase.version, appVersion = appVersion(), createdAt = now,
            coffees = counts.first, photos = counts.second, files = files,
        )
        val manifestBytes = manifestJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray()
        // A backup the restore would refuse is a failed backup: say so before rotation
        // deletes an older archive that still restores.
        if (files.size + 1 > MAX_ENTRIES || manifestBytes.size > MAX_MANIFEST_BYTES) {
            throw BackupError("the journal has outgrown what one backup file can hold (${files.size} files)")
        }
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        zip.write(manifestBytes)
        zip.closeEntry()
    }

    /** Coffee and photo rows as the captured copy has them. Opened read-only; nothing writes here. */
    private fun countRows(dbCopy: File): Pair<Int, Int> {
        val opened = SQLiteDatabase.openDatabase(dbCopy.path, null, SQLiteDatabase.OPEN_READONLY)
        return opened.use { d ->
            fun count(table: String): Int = d.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            count("coffee") to count("coffee_photo")
        }
    }

    private fun putFile(zip: ZipOutputStream, entryName: String, file: File): ManifestFile {
        zip.putNextEntry(ZipEntry(entryName))
        val digest = MessageDigest.getInstance("SHA-256")
        // The size is what was read, like the digest, not the file's length afterwards: a coffee
        // deleted mid-read still reads to the end from the open file but reports a length of zero.
        val size = file.inputStream().use { input -> DigestInputStream(input, digest).copyTo(zip) }
        zip.closeEntry()
        return ManifestFile(entryName, digest.digest().toHex(), size)
    }

    /**
     * Oldest beyond [keep] go; only our own zips are touched, and never [justWritten]. Ordering is by
     * the parsed stamp and collision suffix, because by name alone "…-10.zip" sorts under "…-2.zip"
     * and the newest archive of a busy second is the first one deleted.
     */
    fun rotate(sink: BackupSink, keep: Int, justWritten: String) {
        val others = sink.list().mapNotNull { f -> key(f.name)?.let { it to f.name } }
            .filter { it.second != justWritten }
            .sortedWith(compareByDescending<Pair<Pair<String, Int>, String>> { it.first.first }.thenByDescending { it.first.second })
        // One slot is spoken for by the archive we just wrote.
        others.drop((keep - 1).coerceAtLeast(0)).forEach { sink.delete(it.second) }
    }

    /** (stamp, collision suffix) for one of our zips, or null for a file we did not write. */
    private fun key(name: String): Pair<String, Int>? =
        OUR_ZIP.matchEntire(name)?.let { m -> m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 1) }

    private fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
