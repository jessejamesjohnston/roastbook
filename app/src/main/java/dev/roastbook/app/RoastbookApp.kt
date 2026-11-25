package dev.roastbook.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import androidx.work.Configuration
import dev.roastbook.app.data.backup.BackupManager
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.backup.BackupScheduler
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.extraction.ExtractionScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application entry point: supplies WorkManager with Hilt's worker factory and starts the
 * process-wide jobs at launch (startup maintenance, alarm rescheduling, the extraction scheduler,
 * keeping the daily backup and weekly update check in step with their settings). Each time the app
 * leaves the foreground it refreshes the Auto Backup snapshot and hands pending reads to a worker.
 */
@HiltAndroidApp
class RoastbookApp : Application(), Configuration.Provider {
    @Inject lateinit var repository: CoffeeRepository
    @Inject lateinit var scheduler: ExtractionScheduler
    @Inject lateinit var reminders: dev.roastbook.app.reminders.ReminderScheduler
    @Inject lateinit var backups: BackupManager
    @Inject lateinit var backupPrefs: BackupPrefs
    @Inject lateinit var backupScheduler: BackupScheduler
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var userPrefs: dev.roastbook.app.data.prefs.UserPrefs
    @Inject lateinit var weeklyUpdateCheck: dev.roastbook.app.update.WeeklyUpdateCheck

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { repository.startupMaintenance() }
            runCatching { reminders.rescheduleAll() } // alarms don't survive a force-stop or update
        }
        scheduler.start()
        // Tier 1 schedule follows the settings: a folder plus the daily switch arms the job, either going away disarms it.
        appScope.launch { backupPrefs.state.collect { runCatching { backupScheduler.sync(it) } } }
        // The weekly update check follows its switch (off by default), including after a restore.
        appScope.launch {
            userPrefs.settings.map { it.weeklyUpdateCheck }.distinctUntilChanged().collect { runCatching { weeklyUpdateCheck.sync(it) } }
        }
        // Tier 0: refresh the Auto Backup snapshot whenever the app leaves the foreground after a write.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                appScope.launch {
                    runCatching { backups.refreshAutoSnapshot() }
                        .onFailure { android.util.Log.w("AutoBackup", "snapshot failed", it) }
                }
                // Reads that don't need the system model can finish while the app is away.
                appScope.launch {
                    runCatching {
                        if (repository.hasPendingReads()) {
                            dev.roastbook.app.extraction.ExtractionWorker.enqueue(this@RoastbookApp)
                        }
                    }
                }
            }
        })
    }
}
