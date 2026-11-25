package dev.roastbook.app.reader

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import dev.roastbook.app.extraction.NanoStatus
import dev.roastbook.app.extraction.NanoTier
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * The reader's world for Robolectric tests: a test WorkManager whose download job runs [worker],
 * device-scoped prefs on a fresh file, the device's memory and CPU, and a small debug model spec
 * (debug builds read `no_backup/reader-debug-spec.properties`) so a "ready" model is a few bytes.
 * Build one per test and [close] it in `@After`.
 */
class ReaderHarness {
    val context: Context = ApplicationProvider.getApplicationContext()

    /** What the download job runs. Null falls back to WorkManager's default factory. */
    var worker: (Context, WorkerParameters) -> ListenableWorker? = { _, _ -> null }

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        worker(appContext, workerParameters)
                })
                .build(),
        )
        LocalReaderManager.storeRoot(context).deleteRecursively()
    }

    val workManager: WorkManager get() = WorkManager.getInstance(context)
    val driver: TestDriver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    val prefs = ReaderPrefs(PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "reader-${UUID.randomUUID()}.preferences_pb") })

    private val savedAbis: Array<String> = Build.SUPPORTED_ABIS

    /** What the device reports: memory as `MemoryInfo.totalMem` and the CPU's ABIs. */
    fun device(totalMem: Long = 8 * GIB, abis: List<String> = listOf("arm64-v8a")) {
        val am = context.getSystemService(ActivityManager::class.java)
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { this.totalMem = totalMem })
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", abis.toTypedArray())
    }

    /** A small model the manager will use in place of the 2.6 GB one. */
    fun debugSpec(
        bytes: ByteArray = MODEL_BYTES,
        url: String = "https://models.example/test.bin",
        displayName: String? = "Test model",
    ): ModelSpec {
        val lines = buildList {
            displayName?.let { add("displayName=$it") }
            add("url=$url")
            add("fileName=test.bin")
            add("sizeBytes=${bytes.size}")
            add("sha256=${sha256(bytes)}")
        }
        File(context.noBackupFilesDir, "reader-debug-spec.properties").writeText(lines.joinToString("\n"))
        return ModelSpec(displayName ?: "Debug model", url, "test.bin", bytes.size.toLong(), sha256(bytes))
    }

    fun manager(): LocalReaderManager = LocalReaderManager(context, prefs)

    /** Puts a verified model in place, as a finished download leaves it. */
    fun installModel(m: LocalReaderManager, bytes: ByteArray = MODEL_BYTES) {
        m.store.ensureDirs()
        m.store.part.writeBytes(bytes)
        m.store.promote(m.store.part, sha256(bytes))
    }

    fun writePart(m: LocalReaderManager, bytes: Int) {
        m.store.ensureDirs()
        m.store.part.writeBytes(MODEL_BYTES.copyOfRange(0, bytes))
    }

    fun jobs(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(LocalReaderManager.WORK_NAME).get()
    fun pendingJob(): WorkInfo = jobs().single { !it.state.isFinished }

    /** Lets the waiting download job run (its network rule is met). */
    fun runJob() = driver.setAllConstraintsMet(pendingJob().id)

    suspend fun awaitState(m: LocalReaderManager, what: (ReaderState) -> Boolean): ReaderState =
        withTimeout(10_000) { m.state.first(what) }

    fun close() {
        LocalReaderManager.storeRoot(context).deleteRecursively()
        File(context.noBackupFilesDir, "reader-debug-spec.properties").delete()
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", savedAbis)
    }

    companion object {
        const val GIB = 1024L * 1024 * 1024
        val MODEL_BYTES = ByteArray(64) { (it * 7 + 3).toByte() }
        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** A download job whose body the test writes: report progress, wait on a gate, return a result. */
class ScriptedWorker(
    context: Context,
    params: WorkerParameters,
    private val script: suspend ScriptedWorker.() -> ListenableWorker.Result,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result = script()
}

/** Gemini Nano answering with a fixed status, and counting how often it was asked. */
class FakeNano(private val answer: suspend () -> NanoStatus) : NanoTier {
    constructor(status: NanoStatus) : this({ status })
    @Volatile var asked = 0
        private set
    override suspend fun status(): NanoStatus { asked++; return answer() }
    override suspend fun prepare() {}
    override suspend fun generate(images: List<android.graphics.Bitmap>, prompt: String): String = ""
    override fun modelName(): String = "fake-nano"
}
