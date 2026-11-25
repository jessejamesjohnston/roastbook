package dev.roastbook.app.data.backup

import android.content.Context
import android.util.Log
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The database copy every backup starts from, and the Tier 0 snapshot (`files/autobackup/`) that
 * Android Auto Backup carries. Callers hold [BackupManager]'s lock; nothing here locks.
 */
internal class AutoSnapshot(
    private val context: Context,
    private val db: RoastbookDatabase,
    private val photos: PhotoStore,
    private val prefs: BackupPrefs,
) {
    /** A consistent copy of the live database into [target], WAL included, without stopping Room. */
    suspend fun snapshotDatabase(target: File): File = withContext(Dispatchers.IO) {
        target.delete()
        val tmp = File(target.path + BackupManager.PART_SUFFIX).apply { delete() }
        db.openHelper.writableDatabase.execSQL("VACUUM INTO '${tmp.path.replace("'", "''")}'")
        check(tmp.renameTo(target)) { "could not place the database copy" }
        target
    }

    /**
     * Rebuilds `files/autobackup/` as a new generation and swaps it in whole. Always rebuilt: the
     * cheap eligibility test once used here (MAX(updated_at) against the last snapshot) is blind to
     * deletions, so a deleted coffee stayed in the snapshot forever.
     */
    suspend fun refresh(force: Boolean) {
        // A snapshot still waiting to be adopted is the only copy of that library on the phone.
        if (AutoBackupAdopter.dbAdoptionPending(context)) { Log.w(TAG, "snapshot kept: its adoption is still pending"); return }
        val live = AutoBackupAdopter.snapshotDir(context)
        val next = AutoBackupAdopter.nextDir(context)
        val old = AutoBackupAdopter.oldDir(context)
        // A kill during the last swap can leave the previous generation set aside; take it back.
        AutoBackupAdopter.recoverInterruptedSwap(context)
        next.deleteRecursively()
        old.deleteRecursively()
        next.mkdirs()
        // The generation is built beside the live one, never inside it: a kill here leaves the last
        // good snapshot in place, which is the one a fresh install would adopt.
        snapshotDatabase(File(next, AutoBackupAdopter.DB_COPY))
        val thumbs = File(next, AutoBackupAdopter.THUMBS)
        db.coffeeDao().allPhotos().mapNotNull { it.thumb }.filter { PhotoStore.isPhotoPath(it) }.forEach { rel ->
            val src = photos.file(rel)
            if (src.exists()) src.copyTo(File(thumbs, rel), overwrite = true)
        }
        if (live.exists() && !live.renameTo(old)) throw BackupError("could not set the previous snapshot aside")
        if (!next.renameTo(live)) {
            if (old.exists()) old.renameTo(live)
            throw BackupError("could not put the new snapshot in place")
        }
        old.deleteRecursively()
        prefs.recordSnapshot(System.currentTimeMillis())
        Log.i(TAG, "auto-backup snapshot refreshed (force=$force)")
    }

    private companion object { const val TAG = "Backup" }
}
