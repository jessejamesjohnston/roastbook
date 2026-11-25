package dev.roastbook.app.reader

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 2.6 GB model and the device-scoped reader state must never reach either backup tier. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun theModelLivesOutsideEveryBackedUpPath() {
        val root = LocalReaderManager.storeRoot(context).canonicalFile
        assertTrue(root.path.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(root.path.startsWith(context.filesDir.canonicalPath + File.separator))
        // Tier 1 zips only the database copy and files/photos/.
        assertFalse(root.path.startsWith(File(context.filesDir, "photos").canonicalPath))
    }

    @Test fun tierZeroIncludesOnlyTheSnapshotAndPreferences() {
        val rules = File(System.getProperty("user.dir")!!, "src/main/res/xml/data_extraction_rules.xml").readText()
        val includes = Regex("""<include\s+domain="([^"]+)"\s+path="([^"]+)"""").findAll(rules).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        assertEquals(setOf("file" to "autobackup/", "file" to "datastore/"), includes)
    }

    @Test fun nanoHealthIsRecordedAndClearedBySuccess() = runTest {
        val prefs = ReaderPrefs(PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "test-${System.nanoTime()}.preferences_pb") }, now = { 42L })
        assertFalse(prefs.nanoFailing())
        prefs.recordNano(ok = false, reason = "GenAiException 29")
        assertTrue(prefs.nanoFailing())
        assertEquals("GenAiException 29", prefs.current().nanoFailReason)
        assertEquals(42L, prefs.current().nanoFailedAt)
        prefs.recordNano(ok = true)
        assertFalse(prefs.nanoFailing())
        assertEquals(null, prefs.current().nanoFailReason)
    }

    @Test fun aNanoFailureWithoutAReasonIsStillRecordedAndTheMobileDataChoicePersists() = runTest {
        val prefs = ReaderPrefs(PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "test-${System.nanoTime()}.preferences_pb") }, now = { 7L })
        assertEquals(ReaderSettings(), prefs.current())
        prefs.recordNano(ok = false, reason = null)
        assertEquals("failed", prefs.current().nanoFailReason)
        prefs.setAllowMetered(true)
        assertTrue(prefs.current().allowMetered)
        // A success while nothing was failing leaves the record alone.
        prefs.recordNano(ok = true)
        prefs.recordNano(ok = true)
        assertEquals(ReaderSettings(allowMetered = true), prefs.current())
    }

    @Test fun theReaderChoiceIsOffByDefaultAndPersists() = runTest {
        val prefs = ReaderPrefs(PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "test-${System.nanoTime()}.preferences_pb") })
        assertFalse(prefs.readerPreferred())
        prefs.setPreferReader(true)
        assertTrue(prefs.readerPreferred())
        assertTrue(prefs.current().preferReader)
        prefs.setPreferReader(false)
        assertFalse(prefs.readerPreferred())
    }
}
