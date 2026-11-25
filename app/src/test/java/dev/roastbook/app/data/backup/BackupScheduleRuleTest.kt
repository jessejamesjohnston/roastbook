package dev.roastbook.app.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back up daily is off until a folder is chosen, on once one is, off again when it's cleared. */
class BackupScheduleRuleTest {
    private val dir: File = Files.createTempDirectory("backup-rule").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") }
    private val prefs = BackupPrefs(store)

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun scheduled() = runBlocking { prefs.current().scheduled }

    @Test fun aNewInstallHasItOff() = assertFalse(scheduled())

    @Test fun theDefaultStateIsOffSoTheSwitchNeverFlashesOn() = assertFalse(BackupState().scheduled)

    @Test fun choosingAFolderSwitchesItOn() {
        runBlocking { prefs.setFolder("content://tree/a") }
        assertTrue(scheduled())
    }

    @Test fun clearingTheFolderSwitchesItOff() {
        runBlocking { prefs.setFolder("content://tree/a"); prefs.setFolder(null) }
        assertFalse(scheduled())
    }

    @Test fun changingTheFolderKeepsAnOffSwitchOff() {
        runBlocking { prefs.setFolder("content://tree/a"); prefs.setScheduled(false); prefs.setFolder("content://tree/b") }
        assertFalse(scheduled())
    }

    @Test fun changingTheFolderKeepsAnOnSwitchOn() {
        runBlocking { prefs.setFolder("content://tree/a"); prefs.setFolder("content://tree/b") }
        assertTrue(scheduled())
    }

    @Test fun anEarlierInstallWithAFolderAndNoStoredSwitchKeepsItOn() {
        // Before this rule the switch defaulted to on and stored nothing until touched.
        runBlocking { store.edit { it[stringPreferencesKey("backup_folder")] = "content://tree/a" } }
        assertTrue(scheduled())
    }

    @Test fun anEarlierInstallWithItOnAndAFolderKeepsItOn() {
        runBlocking { store.edit { it[stringPreferencesKey("backup_folder")] = "content://tree/a"; it[booleanPreferencesKey("backup_scheduled")] = true } }
        assertTrue(scheduled())
    }

    @Test fun anEarlierInstallWithItOnButNoFolderNowShowsOff() {
        runBlocking { store.edit { it[booleanPreferencesKey("backup_scheduled")] = true } }
        assertFalse(scheduled())
        // And it stays off until a folder is chosen, which then switches it on.
        runBlocking { prefs.setFolder("content://tree/a") }
        assertTrue(scheduled())
    }

    @Test fun anUnusableFolderIsForgottenWithItsReasonAndSwitchesItOff() {
        runBlocking { prefs.setFolder("content://tree/a"); prefs.recordError("offline"); prefs.folderUnusable("content://tree/a", BackupOff.FOLDER_REFUSED) }
        val s = runBlocking { prefs.current() }
        assertNull(s.folderUri)
        assertFalse(s.scheduled)
        assertEquals(BackupOff.FOLDER_REFUSED, s.off)
        assertNull("the off line replaces the failure line", s.lastError)
    }

    @Test fun aFolderChosenSinceTheCheckIsNotForgotten() {
        runBlocking { prefs.setFolder("content://tree/a"); prefs.setFolder("content://tree/b"); prefs.folderUnusable("content://tree/a", BackupOff.FOLDER_GONE) }
        val s = runBlocking { prefs.current() }
        assertEquals("content://tree/b", s.folderUri)
        assertTrue(s.scheduled)
        assertNull(s.off)
    }

    @Test fun choosingAFolderAfterBackupsWentOffTurnsThemBackOn() {
        runBlocking { prefs.setFolder("content://tree/a"); prefs.folderUnusable("content://tree/a", BackupOff.FOLDER_GONE); prefs.setFolder("content://tree/b") }
        val s = runBlocking { prefs.current() }
        assertTrue(s.scheduled)
        assertNull(s.off)
    }

    @Test fun anOffReasonThisVersionDoesNotKnowReadsAsAFolderGone() {
        runBlocking { store.edit { it[stringPreferencesKey("backup_off")] = "SOMETHING_NEWER" } }
        assertEquals(BackupOff.FOLDER_GONE, runBlocking { prefs.current() }.off)
    }

    @Test fun anEarlierInstallThatSwitchedItOffKeepsItOff() {
        runBlocking { store.edit { it[stringPreferencesKey("backup_folder")] = "content://tree/a"; it[booleanPreferencesKey("backup_scheduled")] = false } }
        assertFalse(scheduled())
    }
}
