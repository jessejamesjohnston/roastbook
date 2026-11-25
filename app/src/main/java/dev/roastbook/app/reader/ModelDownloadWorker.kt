package dev.roastbook.app.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.roastbook.app.R
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Downloads the reader's model as a foreground job with a progress notification (its Pause
 * action stops the job; the partial file stays for a resume unless the user cancels from
 * Settings). A network failure asks WorkManager to retry with backoff; the next run resumes.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: LocalReaderManager,
) : CoroutineWorker(context, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        ensureChannel()
        // Promote to a foreground service so a 2.6 GB download is not cut off. Android refuses this
        // when the job starts while the app is in the background (for example after waiting for
        // Wi-Fi); then it continues as a plain job, which may be stopped and resumes later.
        try {
            setForeground(foregroundInfo(0, manager.spec.sizeBytes, verifying = false))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // canceled (Cancel in the notification or Settings): stop, don't carry on
        } catch (e: Exception) {
            Log.w(TAG, "foreground not allowed now; continuing as a plain job", e)
        }
        var shown = -1 to DownloadProgress.Phase.DOWNLOADING
        val result = ModelDownloader(http).download(manager.spec, manager.store) { p ->
            setProgress(workDataOf(KEY_PHASE to p.phase.name, KEY_BYTES to p.bytes, KEY_TOTAL to p.total))
            // The notification moves in whole percents; re-posting it every chunk is wasted work.
            val pct = if (p.total > 0) ((p.bytes * 100) / p.total).toInt() else 0
            if (pct to p.phase != shown) {
                shown = pct to p.phase
                dev.roastbook.app.util.runCatchingCancellable {
                    setForeground(
                        foregroundInfo(p.bytes, p.total, verifying = p.phase == DownloadProgress.Phase.VERIFYING),
                    )
                }
            }
        }
        manager.onDiskChanged()
        return when (result) {
            DownloadResult.Done -> { Log.i(TAG, "model ready"); Result.success() }
            is DownloadResult.Retry -> {
                Log.w(TAG, "download will retry: ${result.detail}")
                if (runAttemptCount >= MAX_RETRIES) Result.failure(workDataOf(KEY_FAILURE to "NETWORK")) else Result.retry()
            }
            is DownloadResult.Failed -> {
                Log.w(TAG, "download failed: ${result.reason} ${result.detail}")
                Result.failure(workDataOf(KEY_FAILURE to result.reason.name))
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, manager.spec.sizeBytes, verifying = false)

    private fun foregroundInfo(bytes: Long, total: Long, verifying: Boolean): ForegroundInfo {
        val ctx = applicationContext
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        val pct = if (total > 0) ((bytes * 100) / total).toInt() else 0
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(if (verifying) R.string.reader_notif_verifying else R.string.reader_notif_downloading))
            .setContentText(ctx.getString(R.string.reader_notif_progress,
                android.text.format.Formatter.formatShortFileSize(ctx, bytes), android.text.format.Formatter.formatShortFileSize(ctx, total)))
            .setProgress(100, pct, bytes == 0L && !verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, ctx.getString(R.string.reader_notif_pause), cancel)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    applicationContext.getString(R.string.reader_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "ReaderDownload"
        private const val CHANNEL = "reader-download"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_RETRIES = 8
        const val KEY_PHASE = "phase"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_FAILURE = "failure"
        const val KEY_METERED = "metered"
    }
}
