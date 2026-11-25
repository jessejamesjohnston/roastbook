package dev.roastbook.app.reader

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.BuildConfig
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/** What Settings shows about the reader's model file. */
enum class DownloadPhase { NONE, WAITING_FOR_NETWORK, DOWNLOADING, VERIFYING, READY, FAILED }

/** What the Settings reader section shows: device fit, download progress, disk use, options. */
data class ReaderState(
    val eligibility: Eligibility,
    val phase: DownloadPhase,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val sizeOnDisk: Long,
    val allowMetered: Boolean,
    val nanoFailing: Boolean,
    /** A plain reason for the last failed download, or null. */
    val failure: DownloadFailure?,
    val modelName: String,
    /**
     * The last download was stopped part-way (Pause, here or in its notification), as opposed to
     * never started or canceled; only in [DownloadPhase.NONE].
     */
    val stopped: Boolean = false,
    /**
     * A download the person started again (Download on another bag's offer after a failure) is on
     * its way, and WorkManager has not reported its job yet; only in [DownloadPhase.NONE]. Any
     * partial file left by the earlier job is where the new one resumes, not a pause.
     */
    val starting: Boolean = false,
)

/**
 * Why the last download failed: not enough space, a file that did not match its expected size or
 * hash, or anything else (treated as the server's side).
 */
enum class DownloadFailure { NO_SPACE, CHECKSUM, SERVER }

/**
 * The on-device reader's model: where it lives, whether it is ready, and the download job. The
 * download is a WorkManager job (a foreground service with a progress notification) so it survives
 * the user leaving the app; "unmetered only" is a WorkManager constraint, so a download that loses
 * Wi-Fi simply waits and resumes from its partial file.
 */
@Singleton
class LocalReaderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ReaderPrefs,
) {
    val spec: ModelSpec = debugSpec() ?: ModelSpec.GEMMA_4_E2B
    val store = ModelStore(storeRoot(context), spec)
    val eligibility: Eligibility by lazy { DeviceProfile.of(context) }

    /** Bumped after a delete or a finished download so the size-on-disk line refreshes. */
    private val diskTick = MutableStateFlow(0)

    fun isReady(): Boolean = eligibility == Eligibility.ELIGIBLE && store.isReady()

    suspend fun download() {
        if (eligibility != Eligibility.ELIGIBLE || store.isReady()) return
        enqueue(replace = false, allowMetered = prefs.current().allowMetered)
    }

    /**
     * Yes to the reader offer on [coffeeId]'s page: the reader becomes this device's choice, that bag
     * waits for it, and the download starts. A failed or stopped earlier job is set aside in the same
     * write, so the card says the download is starting rather than showing the old outcome.
     */
    suspend fun acceptOffer(coffeeId: String) {
        prefs.acceptOffer(coffeeId, supersededJobs = jobIds { it.state.isFinished })
        download()
    }

    private suspend fun jobIds(which: (WorkInfo) -> Boolean): Set<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrNull().orEmpty()
            .filter(which).map { it.id.toString() }.toSet()
    }

    /** Re-applies the network rule to a waiting or running download. */
    suspend fun setAllowMetered(on: Boolean) {
        prefs.setAllowMetered(on)
        val infos = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrNull().orEmpty()
        }
        if (infos.any { !it.state.isFinished }) enqueue(replace = true, allowMetered = on)
    }

    /** Stops the download and keeps the partial file; Settings then offers Resume (a Range request from where it stopped). */
    fun pause() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        diskTick.value++
    }

    /**
     * Stops the download and removes the partial file. No bag waits for the reader any more, and the
     * reader stops being this device's choice ([ReaderPrefs.abandonReader]).
     */
    suspend fun cancel() {
        val jobs = stopJobs()
        whenNoWriter { store.part.delete() }
        prefs.abandonReader(jobs)
    }

    /**
     * Removes the model, any partial download and the runtime cache. The reader stops being used at
     * once, and stops being this device's choice ([ReaderPrefs.abandonReader]).
     */
    suspend fun delete() {
        val jobs = stopJobs()
        whenNoWriter { store.deleteAll() }
        prefs.abandonReader(jobs)
    }

    /** Stops the download job for good (Cancel or Delete) and returns its ids, so [state] doesn't call it paused. */
    private suspend fun stopJobs(): Set<String> {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        return jobIds { true }
    }

    /**
     * Runs [removal] while no download is writing: at once when none is, otherwise as soon as the
     * one being stopped lets go (WorkManager stops it asynchronously, and until then it can still
     * open the part or move a verified file into place). The writer lock is fair, so a download
     * started again right away waits for this removal.
     */
    private fun whenNoWriter(removal: () -> Unit) {
        val writer = ModelDownloader.writer
        if (writer.tryLock()) {
            try { removal() } finally { writer.unlock() }
            diskTick.value++
        } else {
            cleanup.launch { writer.withLock { removal() }; diskTick.value++ }
        }
    }

    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onDiskChanged() { diskTick.value++ }

    private fun enqueue(replace: Boolean, allowMetered: Boolean) {
        val metered = allowMetered
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (metered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_METERED to metered))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    val state: Flow<ReaderState> by lazy { stateFlow() }

    private fun stateFlow(): Flow<ReaderState> = combine(
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME),
        prefs.settings,
        diskTick,
    ) { infos, settings, _ ->
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
        val ready = store.isReady()
        val progress = info?.progress
        val phase = when {
            ready -> DownloadPhase.READY
            info == null -> DownloadPhase.NONE
            info.state == WorkInfo.State.RUNNING ->
                if (progress?.getString(ModelDownloadWorker.KEY_PHASE) == DownloadProgress.Phase.VERIFYING.name) {
                    DownloadPhase.VERIFYING
                } else {
                    DownloadPhase.DOWNLOADING
                }
            info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> DownloadPhase.WAITING_FOR_NETWORK
            info.state == WorkInfo.State.FAILED && info.id.toString() !in settings.discardedJobs -> DownloadPhase.FAILED
            else -> DownloadPhase.NONE
        }
        val failure = if (phase == DownloadPhase.FAILED) when (info?.outputData?.getString(ModelDownloadWorker.KEY_FAILURE)) {
            DownloadResult.Reason.NO_SPACE.name -> DownloadFailure.NO_SPACE
            DownloadResult.Reason.HASH_MISMATCH.name, DownloadResult.Reason.SIZE_MISMATCH.name -> DownloadFailure.CHECKSUM
            else -> DownloadFailure.SERVER
        } else null
        ReaderState(
            eligibility = eligibility,
            phase = phase,
            downloadedBytes = if (phase == DownloadPhase.DOWNLOADING) {
                progress?.getLong(ModelDownloadWorker.KEY_BYTES, store.partialBytes()) ?: store.partialBytes()
            } else {
                store.partialBytes()
            },
            totalBytes = spec.sizeBytes,
            sizeOnDisk = store.sizeOnDisk(),
            allowMetered = settings.allowMetered,
            nanoFailing = settings.nanoFailing,
            failure = failure,
            modelName = spec.displayName,
            stopped = phase == DownloadPhase.NONE && info?.state == WorkInfo.State.CANCELLED &&
                info.id.toString() !in settings.discardedJobs,
            // Only an accepted offer sets a job aside while a bag waits: Cancel and Delete empty
            // the list in the same write that sets theirs aside.
            starting = phase == DownloadPhase.NONE && info != null && info.state.isFinished &&
                info.id.toString() in settings.discardedJobs && settings.pendingRereads.isNotEmpty(),
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Debug builds only: `no_backup/reader-debug-spec.properties` (url, fileName, sizeBytes,
     * sha256) swaps in a small file so the download, resume, checksum and cancel paths can be
     * exercised on an emulator without fetching 2.6 GB. Release builds never read it.
     */
    private fun debugSpec(): ModelSpec? {
        if (!BuildConfig.DEBUG) return null
        val f = File(context.noBackupFilesDir, "reader-debug-spec.properties").takeIf { it.isFile } ?: return null
        val p = Properties().apply { f.inputStream().use { load(it) } }
        return runCatching {
            ModelSpec(
                displayName = p.getProperty("displayName", "Debug model"),
                url = p.getProperty("url")!!,
                fileName = p.getProperty("fileName")!!,
                sizeBytes = p.getProperty("sizeBytes")!!.toLong(),
                sha256 = p.getProperty("sha256")!!,
            )
        }.getOrNull()
    }

    companion object {
        const val WORK_NAME = "reader-model-download"
        /** Under no_backup/: outside both backup tiers (see [ModelStore]). */
        fun storeRoot(context: Context): File = File(context.noBackupFilesDir, "reader")
    }
}
