package dev.roastbook.app.data.backup

import android.content.Context
import android.util.Log
import dev.roastbook.app.data.photo.PhotoStore
import java.io.File

/**
 * Tier 0 landing: Android Auto Backup restored `files/autobackup/` (a consistent DB copy and the
 * thumbs) onto a fresh install. If there is no live database yet, the copy becomes it, and the thumbs
 * go back to their original relative paths so every row's `thumb` resolves. Originals and squares
 * are not in Tier 0; the UI says "thumbnail only" until a Tier 1 zip is restored.
 *
 * Adoption is two moves (the database, then the thumbs) and a kill between them used to leave a
 * library whose rows pointed at thumbs that were never copied. The `adopting` marker names that
 * window, so the next start finishes the job instead of skipping it forever.
 */
object AutoBackupAdopter {
    const val DIR = "autobackup"
    /**
     * Where the next generation is built and the previous one is parked. The dot matters: the Tier 0
     * include list in `data_extraction_rules.xml` is the prefix `autobackup/`, which these do not
     * match, so a half-built generation is never what Auto Backup uploads.
     */
    const val NEXT_DIR = "autobackup.next"
    const val OLD_DIR = "autobackup.old"
    const val DB_COPY = "roastbook.db"
    const val THUMBS = "thumbs"
    private const val TAG = "AutoBackup"
    private const val ADOPTED_MARKER = "adopted"
    private const val ADOPTING_MARKER = "adopting"
    /** Content of the `adopting` marker while the database copy has not landed yet. */
    private const val DB_PENDING = "db-pending"
    private const val PART_SUFFIX = ".part"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun snapshotDir(context: Context): File = File(context.filesDir, DIR)
    fun nextDir(context: Context): File = File(context.filesDir, NEXT_DIR)
    fun oldDir(context: Context): File = File(context.filesDir, OLD_DIR)

    /**
     * A kill between the two renames of a snapshot swap leaves only the previous generation, under
     * a name the include list skips: Auto Backup would upload no snapshot at all. Put it back.
     */
    fun recoverInterruptedSwap(context: Context) {
        val live = snapshotDir(context)
        val old = oldDir(context)
        if (!live.exists() && old.exists()) old.renameTo(live)
    }

    fun adoptIfNeeded(context: Context, dbName: String) {
        // At every start, before the first refresh, which waits for the app to leave the foreground.
        recoverInterruptedSwap(context)
        val live = context.getDatabasePath(dbName)
        val dir = snapshotDir(context)
        val copy = File(dir, DB_COPY)
        if (!copy.exists()) return
        if (live.exists()) {
            // A database copy that failed (a full disk) left Room to create an empty database at the
            // last start. While that database is still empty, adopting the snapshot is still right.
            if (!dbAdoptionPending(context)) { finishInterruptedAdoption(context, dir); return }
            when (isEmptyLibrary(live)) {
                false -> { finishInterruptedAdoption(context, dir); return }
                // Unreadable is not "has coffees": keep the snapshot protected and look again next start.
                null -> { Log.w(TAG, "could not read the live database; the adoption stays pending"); return }
                true -> Log.i(TAG, "retrying an Auto Backup adoption that did not land")
            }
        }
        if (!looksLikeADatabase(copy)) {
            Log.w(TAG, "the Auto Backup snapshot is not a database (${copy.length()} bytes); ignoring it")
            return
        }
        runCatching {
            File(dir, ADOPTING_MARKER).writeText(DB_PENDING)
            live.parentFile?.mkdirs()
            listOf("", "-wal", "-shm", "-journal").forEach { File(live.path + it).delete() }
            // Into a temporary name first: a kill mid-copy would otherwise leave a truncated file
            // sitting where Room expects a database, and the snapshot is skipped once one exists.
            val tmp = File(live.path + ".tmp")
            tmp.delete()
            copy.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(live)) { "could not put the adopted database in place" }
            File(dir, ADOPTING_MARKER).writeText(System.currentTimeMillis().toString()) // the database landed
            copyThumbs(context, dir)
            markAdopted(context, dir)
            Log.i(TAG, "adopted the Auto Backup snapshot (${copy.length()} bytes)")
        }.onFailure { Log.w(TAG, "adopting the Auto Backup snapshot failed", it) }
    }

    /**
     * True while a snapshot is waiting to become the database: the copy failed and the live database
     * (if any) is not the snapshot's. The snapshot must not be rebuilt from the live database then,
     * or the library it holds is gone, here and at the next Auto Backup.
     */
    fun dbAdoptionPending(context: Context): Boolean =
        runCatching { File(snapshotDir(context), ADOPTING_MARKER).readText() == DB_PENDING }.getOrDefault(false)

    /** True when the database holds no coffee (Room created it empty), false when it holds some, null when it can't be read. */
    private fun isEmptyLibrary(live: File): Boolean? = runCatching {
        android.database.sqlite.SQLiteDatabase.openDatabase(live.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT COUNT(*) FROM coffee", null).use { c -> c.moveToFirst() && c.getInt(0) == 0 }
        }
    }.getOrNull()

    /** A live database plus an unfinished adoption: copy the thumbs again (same bytes, same paths). */
    private fun finishInterruptedAdoption(context: Context, dir: File) {
        if (dbAdoptionPending(context)) {
            // The user has added coffees to the database Room made after the failed copy; that
            // journal wins, and the snapshot may be rebuilt from it again.
            File(dir, ADOPTING_MARKER).delete()
            Log.w(TAG, "gave up an Auto Backup adoption: the live database already has coffees")
            return
        }
        if (!File(dir, ADOPTING_MARKER).exists() || File(dir, ADOPTED_MARKER).exists()) return
        runCatching {
            copyThumbs(context, dir)
            markAdopted(context, dir)
            Log.i(TAG, "finished an interrupted Auto Backup adoption")
        }.onFailure { Log.w(TAG, "finishing the interrupted adoption failed", it) }
    }

    private fun markAdopted(context: Context, dir: File) {
        File(dir, ADOPTED_MARKER).writeText(System.currentTimeMillis().toString())
        File(dir, ADOPTING_MARKER).delete()
    }

    private fun copyThumbs(context: Context, dir: File) {
        val thumbs = File(dir, THUMBS)
        if (!thumbs.isDirectory) return
        // `.part` files are half-written copies from an interrupted refresh, never something to adopt.
        // Only what the snapshot writer puts there is copied: plain files at photos/<id>/<name>.jpg.
        // Restored bytes are not trusted to have that shape, and anything else (restore.inprogress,
        // photos.old-*, secrets/) would land outside the backup's include list.
        thumbs.walkTopDown().filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }.forEach { t ->
            val rel = t.relativeTo(thumbs).invariantSeparatorsPath
            if (!PhotoStore.isPhotoPath(rel) ||
                !java.nio.file.Files.isRegularFile(t.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            ) {
                Log.w(TAG, "skipped a restored file outside the thumbnail shape")
                return@forEach
            }
            val dest = File(context.filesDir, rel)
            dest.parentFile?.mkdirs()
            t.copyTo(dest, overwrite = true)
        }
    }

    /** The first sixteen bytes every SQLite file starts with; anything else is not a database. */
    private fun looksLikeADatabase(file: File): Boolean = runCatching {
        if (file.length() < SQLITE_HEADER.size) return false
        val head = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { it.read(head) }
        head.contentEquals(SQLITE_HEADER)
    }.getOrDefault(false)

    /** True once a snapshot was adopted and no full restore has happened since (photos are thumbs only). */
    fun adoptedWithoutOriginals(context: Context): Boolean = File(snapshotDir(context), ADOPTED_MARKER).exists()

    /** Drops the snapshot and both markers: it describes a library that is about to be replaced. */
    fun discardSnapshot(context: Context) {
        snapshotDir(context).deleteRecursively()
        nextDir(context).deleteRecursively()
        oldDir(context).deleteRecursively()
    }
}
