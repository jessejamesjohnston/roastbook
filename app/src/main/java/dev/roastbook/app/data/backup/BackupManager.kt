package dev.roastbook.app.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tier 1 (zip to a user-chosen folder) and the Tier 0 snapshot. Backups never touch the live
 * database file: the copy comes from `VACUUM INTO`, which is consistent under WAL. Restore stages
 * everything under cache, verifies it, then swaps the photo directory and replaces rows in one
 * transaction, with a marker on disk so a kill between those two steps rolls back at the next start.
 *
 * This class is the entry point and holds the lock; the work itself lives in [BackupWriter],
 * [AutoSnapshot] and [BackupRestorer], with the archive format in BackupFormat.kt.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: RoastbookDatabase,
    private val photos: PhotoStore,
    private val prefs: BackupPrefs,
) {
    /** One backup, snapshot or restore at a time: each reads or replaces the same files. */
    private val lock = Mutex()
    private val snapshots = AutoSnapshot(context, db, photos, prefs)
    private val writer = BackupWriter(context, db, photos, snapshots)
    private val restorer = BackupRestorer(context, db, photos, snapshots)

    /** The folder backups go to, or null when none is chosen. */
    fun sinkFor(state: BackupState): BackupSink? = state.folderUri?.let { SafSink(context, it.toUri()) }

    /** True when the picked folder still grants us read+write (the user can revoke it in Files). */
    fun folderStillGranted(uriString: String): Boolean {
        val uri = uriString.toUri()
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    /** True when the picked folder can still take backups: granted, and its name can be read (a deleted folder's can't). */
    fun folderUsable(uriString: String): Boolean =
        folderStillGranted(uriString) && SafSink(context, uriString.toUri()).displayName() != null

    /**
     * Forgets the picked folder once its grant is gone, which turns backups off and records why. Used
     * where no backup has failed (Settings opening, the daily run starting): a storage app that can't
     * name the folder right now (mid-update, say) is not proof the folder is gone, so only a backup
     * that then fails ([checkFolder], from [backupNow]) turns backups off for that.
     */
    suspend fun checkGrant(uriString: String): Boolean {
        if (folderStillGranted(uriString)) return true
        prefs.folderUnusable(uriString, BackupOff.FOLDER_GONE)
        return false
    }

    /**
     * After a failed backup: forgets the picked folder when it can no longer take backups, which turns
     * them off and records why (see [BackupPrefs.folderUnusable]). Returns whether it is still usable.
     */
    suspend fun checkFolder(uriString: String): Boolean {
        if (folderUsable(uriString)) return true
        prefs.folderUnusable(uriString, BackupOff.FOLDER_GONE)
        return false
    }


    // ---------- backup ----------

    /**
     * Writes a new archive into [sink] and rotates older ones out. On failure, a folder that can no
     * longer take backups is forgotten; any other error is recorded for Settings and rethrown.
     */
    suspend fun backupNow(sink: BackupSink, reason: String = "manual", now: Long = System.currentTimeMillis()): BackupSummary = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                val summary = writer.writeZip(sink, now)
                writer.rotate(sink, prefs.current().keepCount, summary.name)
                prefs.recordSuccess(summary.at, summary.name, summary.bytes, summary.coffees)
                Log.i(TAG, "backup ($reason): ${summary.name}, ${summary.bytes} bytes, ${summary.coffees} coffees")
                summary
            } catch (e: Exception) {
                // A folder that can't take backups at all is forgotten, so nothing claims they still run.
                // Any other failure (a cloud folder offline, a full disk) may pass: keep the folder and
                // record the error, which Settings shows until the next success.
                val folder = sink.folderUri
                when {
                    folder != null && e is BackupFolderRefused -> prefs.folderUnusable(folder, BackupOff.FOLDER_REFUSED)
                    folder != null && !checkFolder(folder) -> Unit
                    else -> prefs.recordError((if (isOutOfSpace(e)) NO_SPACE_ERROR else "") + (e.message ?: e::class.simpleName ?: "failed"))
                }
                Log.w(TAG, "backup ($reason) failed", e)
                throw e
            }
        }
    }

    /** A consistent copy of the live database into [target], WAL included, without stopping Room. */
    suspend fun snapshotDatabase(target: File): File = snapshots.snapshotDatabase(target)

    // ---------- Tier 0 snapshot ----------

    /**
     * Rebuild `files/autobackup/`. Called when the app leaves the foreground. [force] says the caller
     * cares about this one landing; either way the snapshot is rebuilt (see [AutoSnapshot.refresh]).
     */
    suspend fun refreshAutoSnapshot(force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) { snapshots.refresh(force) }
    }

    // ---------- restore ----------

    /** Reads the manifest and digests the whole file, for the confirmation dialog. */
    suspend fun inspect(open: () -> InputStream?): BackupInspection = restorer.inspect(open)

    /**
     * Replace everything on this phone with the archive. Verifies before it touches anything, and
     * restoring the same archive twice ends in the same state. [expectedSha] is the digest from
     * [inspect]: a file that changed in between is refused before a single row moves.
     */
    suspend fun restore(open: () -> InputStream?, expectedSha: String? = null): BackupManifest = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) { restorer.restore(open, expectedSha) }
    }

    companion object {
        private const val TAG = "Backup"
        const val DB_ENTRY = "roastbook.db"
        const val MANIFEST_ENTRY = "manifest.json"
        const val PART_SUFFIX = ".part"
        val OUR_ZIP = Regex("""roastbook-(\d{8}-\d{6})(?:-(\d+))?\.zip""")
        /** The only shapes a restored file may have: the DB copy, or photos/<uuid>/<uuid>[.square|.thumb].jpg. */
        internal const val UUID_RE = """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
        val SAFE_ENTRY = Regex("""(?:roastbook\.db|photos/$UUID_RE/$UUID_RE(?:\.square|\.thumb)?\.jpg)""")
        /**
         * Bounds a hostile archive has to live inside, and that every backup is checked against
         * before it lands. A manifest line is about 190 bytes, so 32 MiB holds some 170,000 files:
         * tens of thousands of coffees.
         */
        const val MAX_ENTRIES = 200_000
        const val MAX_MANIFEST_BYTES = 32 shl 20
        /** Inspection stops an entry that inflates past this multiple of the bytes read from the file. */
        const val MAX_EXPANSION = 64L
        const val EXPANSION_SLACK = 64L shl 20
        const val SPACE_RESERVE = 32L shl 20
    }
}
