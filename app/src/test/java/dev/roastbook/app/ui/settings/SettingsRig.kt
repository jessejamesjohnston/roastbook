package dev.roastbook.app.ui.settings

import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.roastbook.app.cloud.GeminiClient
import dev.roastbook.app.data.backup.AutoBackupAdopter
import dev.roastbook.app.data.backup.BackupManager
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.backup.DirectorySink
import dev.roastbook.app.data.backup.RestoreJournal
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.update.UpdateChecker
import dev.roastbook.app.update.WeeklyUpdateCheck
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.robolectric.Shadows.shadowOf

/**
 * Everything [SettingsViewModel] needs, built from the shared [TestGraph] plus real backup parts and
 * a Gemini client and update checker pointed at a local [MockWebServer]. The ViewModel lives in its own store, so
 * [close] cancels its coroutines.
 */
class SettingsRig {
    val graph = TestGraph()
    val context get() = graph.context
    val backupPrefs = BackupPrefs(PreferenceDataStoreFactory.create { File(context.filesDir, "backup-${UUID.randomUUID()}.preferences_pb") })
    val backups = BackupManager(context, graph.db, graph.photos, backupPrefs)
    val server = MockWebServer().apply { start() }
    val gemini = GeminiClient().apply { baseUrl = server.url("/").toString().trimEnd('/') }
    /** The update check talks to the same local server (GitHub's path differs from Gemini's). */
    val updates = UpdateChecker().apply { apiBase = server.url("/").toString().trimEnd('/') }
    val weeklyCheck = WeeklyUpdateCheck(context)
    /** Scratch space for zips the tests build; removed in [close]. */
    val scratch = File(context.cacheDir, "settings-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()

    val vm: SettingsViewModel by lazy {
        val factory = viewModelFactory {
            initializer { SettingsViewModel(context, backups, backupPrefs, graph.repo, graph.reminders, graph.userPrefs, graph.secrets, gemini, updates, weeklyCheck) }
        }
        ViewModelProvider.create(store, factory)[SettingsViewModel::class]
    }

    fun close() {
        store.clear()
        server.shutdown()
        graph.close()
        scratch.deleteRecursively()
        AutoBackupAdopter.discardSnapshot(context)
        RestoreJournal.clear(context)
        context.filesDir.listFiles()?.filter { it.name.startsWith("photos.old-") }?.forEach { it.deleteRecursively() }
    }

    /** Runs the main looper and lets IO work land until [done] holds (DataStore, zips and HTTP run off the main thread). */
    fun waitFor(timeoutMs: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            Thread.sleep(5)
        }
    }

    /** Ids of the coffees [backupZip] seeded (UUIDs: a restore accepts only ids of the shape the app writes). */
    val seededIds = mutableListOf<String>()

    /** Seeds [n] coffees from fictional roasters and writes a real backup zip of them; returns the zip. */
    fun backupZip(n: Int = 2): File = runBlocking {
        val roasters = listOf("Northwind", "Larkmoor", "Stillwick Roasters", "Sable & Rye")
        repeat(n) { i ->
            val id = UUID.randomUUID().toString()
            graph.insertCoffee(id) { copy(roaster = roasters[i % roasters.size], overall = 4f) }
            seededIds += id
        }
        val dir = File(scratch, "sink-${UUID.randomUUID()}")
        backups.backupNow(DirectorySink(dir))
        dir.listFiles()!!.single { it.name.endsWith(".zip") }
    }

    /** Copies a zip, letting the caller rewrite entry bytes by name and append extra entries. */
    fun rewriteZip(src: File, extra: Map<String, ByteArray> = emptyMap(), rewrite: (String, ByteArray) -> ByteArray = { _, b -> b }): File {
        val out = File(scratch, "rewritten-${UUID.randomUUID()}.zip")
        ZipOutputStream(out.outputStream()).use { zos ->
            ZipInputStream(src.inputStream()).use { zin ->
                generateSequence { zin.nextEntry }.forEach { e ->
                    val bytes = ByteArrayOutputStream().also { zin.copyTo(it) }.toByteArray()
                    zos.putNextEntry(ZipEntry(e.name)); zos.write(rewrite(e.name, bytes)); zos.closeEntry()
                }
            }
            extra.forEach { (name, bytes) -> zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
        }
        return out
    }

    /** A file that is not a zip at all. */
    fun junkFile(): File = File(scratch, "notes-${UUID.randomUUID()}.zip").apply { writeText("not a backup") }

    companion object {
        /** A Storage Access Framework tree URI from a provider that isn't installed. */
        val TREE: Uri = Uri.parse("content://dev.roastbook.test.documents/tree/primary%3ABackups")
    }
}
