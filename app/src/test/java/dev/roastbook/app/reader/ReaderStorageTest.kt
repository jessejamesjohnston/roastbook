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

    @Test fun theInFlowOfferRemembersItsAnswersAndHandsOverWaitingBagsOnce() = runTest {
        val prefs = ReaderPrefs(PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "test-${System.nanoTime()}.preferences_pb") })
        prefs.declineOffer("a")
        assertEquals(1, prefs.current().offerDeclines)
        assertEquals(setOf("a"), prefs.current().offerDeclinedIds)
        prefs.acceptOffer("b")
        assertTrue(prefs.current().preferReader)
        assertEquals(setOf("b"), prefs.current().pendingRereads)
        val reread = mutableListOf<String>()
        ReaderRereads.drain(ready = false, prefs) { reread += it }
        assertEquals(emptyList<String>(), reread)
        ReaderRereads.drain(ready = true, prefs) { reread += it }
        ReaderRereads.drain(ready = true, prefs) { reread += it }
        assertEquals(listOf("b"), reread)
        assertEquals(emptySet<String>(), prefs.current().pendingRereads)
        // A reread that throws does not stop the others.
        prefs.acceptOffer("c"); prefs.acceptOffer("d")
        val done = mutableListOf<String>()
        ReaderRereads.drain(ready = true, prefs) { if (it == "c") error("boom") else done += it }
        assertEquals(listOf("d"), done)
    }

    private fun freshPrefs() = ReaderPrefs(PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "test-${System.nanoTime()}.preferences_pb") })

    @Test fun aSecondNotNowOnTheSameBagCountsOnce() = runTest {
        val prefs = freshPrefs()
        prefs.declineOffer("a")
        prefs.declineOffer("a")
        assertEquals(1, prefs.current().offerDeclines)
        prefs.declineOffer("b")
        assertEquals(2, prefs.current().offerDeclines)
        assertEquals(setOf("a", "b"), prefs.current().offerDeclinedIds)
    }

    @Test fun abandoningTheReaderEmptiesTheWaitingListTurnsTheSwitchOffAndAnswersThoseBags() = runTest {
        val prefs = freshPrefs()
        prefs.acceptOffer("a"); prefs.acceptOffer("b")
        prefs.declineOffer("c")
        prefs.abandonReader()
        val s = prefs.current()
        assertEquals(emptySet<String>(), s.pendingRereads)
        assertFalse(s.preferReader)
        assertEquals(setOf("a", "b", "c"), s.offerDeclinedIds)
        assertEquals("a cancel is not a Not now", 1, s.offerDeclines)
    }

    @Test fun notNowOnABagWaitingForAFailedDownloadStopsOnlyThatBagWaiting() = runTest {
        val prefs = freshPrefs()
        prefs.acceptOffer("a"); prefs.acceptOffer("b")
        prefs.declineOffer("a")
        val s = prefs.current()
        assertEquals(setOf("b"), s.pendingRereads)
        assertEquals(setOf("a"), s.offerDeclinedIds)
        assertEquals("the person said yes once, so it is not a decline", 0, s.offerDeclines)
        assertTrue("the reader stays this device's choice", s.preferReader)
        prefs.declineOffer("a")
        assertEquals("a second tap changes nothing", 0, prefs.current().offerDeclines)
        assertEquals("when the reader arrives, that bag is not read again", setOf("b"), prefs.takePendingRereads())
    }

    @Test fun acceptingAnOfferSetsAsideTheJobsItReplaces() = runTest {
        val prefs = freshPrefs()
        prefs.acceptOffer("a")
        assertEquals(emptySet<String>(), prefs.current().discardedJobs)
        prefs.acceptOffer("b", supersededJobs = setOf("job-1"))
        assertEquals(setOf("job-1"), prefs.current().discardedJobs)
        prefs.abandonReader(setOf("job-2"))
        assertEquals(setOf("job-2"), prefs.current().discardedJobs)
    }

    @Test fun aPoorReadWaitsForTheReaderOnlyWhileAnAcceptedDownloadIsComing() = runTest {
        val prefs = freshPrefs()
        prefs.waitForReaderIfComing("x")
        assertEquals("nothing is coming", emptySet<String>(), prefs.current().pendingRereads)
        prefs.acceptOffer("a")
        prefs.declineOffer("d")
        prefs.waitForReaderIfComing("x")
        prefs.waitForReaderIfComing("d")
        assertEquals(setOf("a", "x"), prefs.current().pendingRereads)
    }
}
