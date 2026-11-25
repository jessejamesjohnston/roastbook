package dev.roastbook.app.extraction

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.roastbook.app.data.db.CoffeeDao

/**
 * Continues reads after the user leaves the app. Nano cannot run here (AICore refuses background
 * callers), so a row that may still get a Nano read waits for the foreground; rows on a phone
 * without working Nano go on to the on-device reader, the cloud key, or the guess. Enqueued when
 * the app leaves the foreground with rows still being read. Like the foreground loop, it reads bags
 * only: the roaster lookup is never started from here ([ReadPass]).
 */
@HiltWorker
class ExtractionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: CoffeeDao,
    private val readPass: ReadPass,
    private val backups: dev.roastbook.app.data.backup.BackupManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ids = dao.extractingIdsNow()
        for (id in ids) readPass.readIfDue(id, RunMode.BACKGROUND)
        // The app left the foreground before these reads finished, so the Tier 0 snapshot taken then
        // holds them unread; refresh it now that the rows changed.
        if (ids.isNotEmpty()) dev.roastbook.app.util.runCatchingCancellable { backups.refreshAutoSnapshot() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "background-reads"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
