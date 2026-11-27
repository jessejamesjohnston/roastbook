package dev.roastbook.app.update

import dev.roastbook.app.util.ExternalLinks
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.R
import dev.roastbook.app.data.prefs.UserPrefs
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The opt-in weekly check (Settings > About, off by default). Armed only while the switch is on;
 * turning it off cancels the job, so with the switch off the app never contacts GitHub on its own.
 */
@Singleton
class WeeklyUpdateCheck @Inject constructor(@ApplicationContext private val context: Context) {
    /** Arms the weekly job when [enabled], cancels it otherwise. */
    fun sync(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
    }

    companion object { const val WORK_NAME = "weekly-update-check" }
}

/**
 * One weekly check. A newer version posts one quiet notification that opens the release page;
 * each version is announced once. A failed check waits for next week rather than retrying.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: UpdateChecker,
    private val prefs: UserPrefs,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!prefs.current().weeklyUpdateCheck) return Result.success()
        val installed = runCatching {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
        }.getOrNull() ?: return Result.success()
        val found = checker.check(installed)
        // Without the notification permission nothing can be shown; the version stays unannounced,
        // so a later week tells the person once they allow notifications.
        if (found is UpdateCheckResult.Available && prefs.current().lastAnnouncedVersion != found.version && canNotify()) {
            notify(found.version)
            prefs.setLastAnnouncedVersion(found.version)
        }
        return Result.success()
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(version: String) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    ctx.getString(R.string.update_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val open = ExternalLinks.browserIntent(UpdateChecker.RELEASES_PAGE.toUri())
        val tap = PendingIntent.getActivity(ctx, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_roastbook)
            .setContentTitle(ctx.getString(R.string.update_available, version))
            .setContentText(ctx.getString(R.string.update_notif_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        // canNotify() checked the permission; it can still be revoked in between.
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission withdrawn", e)
        }
    }

    companion object {
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 4301
        private const val TAG = "UpdateCheck"
    }
}
