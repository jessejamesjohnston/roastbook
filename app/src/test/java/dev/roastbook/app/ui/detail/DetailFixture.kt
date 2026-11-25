package dev.roastbook.app.ui.detail

import android.app.ActivityManager
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.testing.WorkManagerTestInitHelper
import dev.roastbook.app.cloud.Enricher
import dev.roastbook.app.cloud.GeminiClient
import dev.roastbook.app.reader.LocalReaderManager
import dev.roastbook.app.reader.ReaderPrefs
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.testutil.FakeSecretStore
import dev.roastbook.app.testutil.TestGraph
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.util.UUID
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * Everything a [DetailViewModel] needs, built from real collaborators on a [TestGraph]. Cloud
 * calls go to [client], whose base URL a test points at a MockWebServer when it needs an answer;
 * with no key in [secrets] nothing is ever sent.
 *
 * [eligibleForReader] makes this device one the on-device reader could run on (8 GiB, arm64),
 * so the reader is "offered" until it is downloaded.
 */
class DetailFixture(key: String? = null, eligibleForReader: Boolean = false) {
    val graph = TestGraph()
    val context get() = graph.context
    val secrets = FakeSecretStore(key)
    val pending = PendingActions()
    val readNotes = ReadNotes(graph.context)
    val client = GeminiClient()
    val enricher = Enricher(graph.dao, client, secrets, graph.userPrefs)
    val readerPrefs = ReaderPrefs(PreferenceDataStoreFactory.create { File(graph.context.noBackupFilesDir, "reader-${UUID.randomUUID()}.preferences_pb") })
    val reader: LocalReaderManager

    private val abis: Array<String> = Build.SUPPORTED_ABIS

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(graph.context)
        if (eligibleForReader) {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
            val am = graph.context.getSystemService(ActivityManager::class.java)
            shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 8L * 1024 * 1024 * 1024 })
        }
        reader = LocalReaderManager(graph.context, readerPrefs)
    }

    /** Owns every ViewModel built here, so [close] stops their flows before the database closes. */
    private val store = ViewModelStore()
    private var made = 0

    fun vm(id: String) = DetailViewModel(id, graph.repo, graph.photos, pending, enricher, secrets, reader, readNotes, graph.userPrefs)
        .also { store.put("detail-${made++}", it) }

    fun close() {
        store.clear()
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", abis)
        graph.close()
    }
}

/** Runs the main looper until [condition] holds, for tests without a Compose rule. */
fun idleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > end) throw AssertionError("condition not met in $timeoutMs ms")
        Thread.sleep(10)
    }
}

/**
 * Waits for [condition], letting real time pass between checks. Room delivers on its own threads,
 * which `waitUntil` alone does not give time to under Robolectric's paused looper.
 */
fun ComposeTestRule.awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        waitForIdle()
        if (condition()) return
        if (System.currentTimeMillis() > end) throw AssertionError("condition not met in $timeoutMs ms")
        Thread.sleep(10)
    }
}
