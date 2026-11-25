package dev.roastbook.app.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The daily Tier 1 job. Periodic work is only ever "armed or not": WorkManager picks the moment
 * inside each day (a 4 h flex window), Doze permitting; the zip is small enough that no charging or
 * network constraint is worth the missed runs.
 */
@Singleton
class BackupScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun sync(state: BackupState) {
        val wm = WorkManager.getInstance(context)
        if (state.folderUri != null && state.scheduled) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
    }

    companion object { const val WORK_NAME = "daily-backup" }
}

/**
 * The daily backup run that [BackupScheduler] arms. Does nothing if no folder is set, forgets
 * a folder whose grant is gone, and retries a failed backup up to twice unless the failure already
 * made the app forget the folder.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupManager,
    private val prefs: BackupPrefs,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = prefs.current()
        val sink = backups.sinkFor(state) ?: return Result.success() // folder cleared since scheduling; the next sync() cancels us
        // A folder whose grant is gone is forgotten here, which turns daily backup off; nothing to retry.
        // Anything else is decided by the attempt itself (backupNow forgets a folder that then fails).
        if (state.folderUri != null && !backups.checkGrant(state.folderUri)) return Result.failure()
        return try {
            backups.backupNow(sink, "scheduled")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Backup", "scheduled backup failed (attempt ${runAttemptCount + 1})", e)
            // The failure made Roastbook forget the folder (refused, or gone): retrying can't help.
            val forgotten = prefs.current().folderUri != state.folderUri
            if (!forgotten && runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
