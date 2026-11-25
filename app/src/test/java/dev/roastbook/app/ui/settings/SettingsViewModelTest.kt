package dev.roastbook.app.ui.settings

import android.app.UiModeManager
import android.net.Uri
import android.os.Looper
import dev.roastbook.app.R
import dev.roastbook.app.cloud.cleanApiKey
import dev.roastbook.app.data.backup.BackupManager
import dev.roastbook.app.data.backup.BackupOff
import dev.roastbook.app.data.prefs.ThemeMode
import dev.roastbook.app.data.prefs.UserSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings' state holder: the cloud key, backups, restores and the stored preferences it writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsViewModelTest {
    private val rig = SettingsRig()
    private val vm get() = rig.vm
    private val collectors = mutableListOf<Job>()

    @After fun tearDown() {
        collectors.forEach { it.cancel() }
        rig.close()
    }

    /** The screen collects these; a test that reads `.value` has to as well (they share WhileSubscribed). */
    private fun collectState() {
        val scope = CoroutineScope(Dispatchers.Main)
        collectors += scope.launch { vm.state.collect {} }
        collectors += scope.launch { vm.settings.collect {} }
    }

    private fun saveKey(key: String) {
        vm.saveKey(key)
        val kept = cleanApiKey(key)
        rig.waitFor { rig.graph.secrets.key == kept && vm.cloud.value.tail == kept?.takeLast(4) }
    }

    private fun testKeyAndWait(): BackupUi.Message {
        vm.testKey()
        rig.waitFor { !vm.cloud.value.testing && vm.cloud.value.message != null }
        return vm.cloud.value.message!!
    }

    // ---- Cloud key ----

    @Test fun withNoKeyTheCardHasNoKeyAndNoEnding() {
        assertFalse(vm.cloud.value.hasKey)
        assertNull(vm.cloud.value.tail)
    }

    @Test fun aKeySavedEarlierShowsItsLastFourCharactersWhenSettingsOpens() {
        runBlocking { rig.graph.secrets.setGeminiKey("FAKEEarlierKey9876") }
        assertTrue(vm.cloud.value.hasKey)
        assertEquals("9876", vm.cloud.value.tail)
    }

    @Test fun savingAKeyStoresItAndShowsOnlyItsLastFourCharacters() {
        saveKey("FAKETestKey1234")
        assertEquals("FAKETestKey1234", rig.graph.secrets.key)
        assertTrue(vm.cloud.value.hasKey)
        assertEquals("1234", vm.cloud.value.tail)
    }

    @Test fun replacingAKeyShowsTheNewKeysEnding() {
        saveKey("FAKEFirstKey1111")
        saveKey("FAKESecondKey2222")
        assertEquals("2222", vm.cloud.value.tail)
    }

    @Test fun removingTheKeyForgetsItAndClearsTheLastTestResult() {
        saveKey("FAKETestKey1234")
        rig.server.enqueue(MockResponse().setBody("""{"name":"models/x"}"""))
        testKeyAndWait()
        vm.removeKey()
        rig.waitFor { !vm.cloud.value.hasKey }
        assertNull(rig.graph.secrets.key)
        assertNull(vm.cloud.value.tail)
        assertNull(vm.cloud.value.message)
    }

    /** Holds Test key's request on the server until [release]; [arrived] opens when it gets there. */
    private class HeldAnswer {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"name":"models/x"}""")
            }
        }
    }

    /** Runs the main looper for [ms] and checks [holds] the whole time. */
    private fun stays(ms: Long, holds: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(holds())
            Thread.sleep(10)
        }
    }

    private fun startHeldTest(): HeldAnswer {
        val held = HeldAnswer()
        rig.server.dispatcher = held.dispatcher
        vm.testKey()
        rig.waitFor { vm.cloud.value.testing }
        assertTrue("the test request reached the server", held.arrived.await(5, TimeUnit.SECONDS))
        return held
    }

    @Test fun removingTheKeyWhileATestRunsDropsTheTestAndItsResult() {
        saveKey("FAKETestKey1234")
        val held = startHeldTest()
        try {
            vm.removeKey()
            rig.waitFor { !vm.cloud.value.hasKey }
        } finally {
            held.release.countDown()
        }
        // The held answer would have landed within this window had the test kept running.
        stays(1_500) { !vm.cloud.value.testing && vm.cloud.value.message == null }
    }

    @Test fun replacingTheKeyWhileATestRunsDropsTheOldKeysResult() {
        saveKey("FAKEFirstKey1111")
        val held = startHeldTest()
        try {
            saveKey("FAKESecondKey2222")
        } finally {
            held.release.countDown()
        }
        stays(1_500) { !vm.cloud.value.testing && vm.cloud.value.message == null }
    }

    @Test fun aWorkingKeySaysWhichModelItWorksWith() {
        saveKey("FAKETestKey1234")
        rig.server.enqueue(MockResponse().setBody("""{"name":"models/x"}"""))
        val m = testKeyAndWait()
        assertEquals(BackupUi.Message(R.string.settings_cloud_test_ok, listOf(UserSettings.DEFAULT_GEMINI_MODEL)), m)
        val req = rig.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("FAKETestKey1234", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!.endsWith("/models/${UserSettings.DEFAULT_GEMINI_MODEL}"))
    }

    @Test fun aRefusedKeyAsksToCheckTheKey() {
        saveKey("FAKETestKey1234")
        rig.server.enqueue(MockResponse().setResponseCode(400).setBody(BAD_KEY_BODY))
        assertEquals("HTTP 400", BackupUi.Message(R.string.cloud_err_key, isError = true), testKeyAndWait())
        for (code in listOf(401, 403)) {
            rig.server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"code":$code,"message":"Permission denied","status":"PERMISSION_DENIED"}}"""))
            assertEquals("HTTP $code", BackupUi.Message(R.string.cloud_err_key, isError = true), testKeyAndWait())
        }
    }

    /** A 400 that Google does not tie to the key (a malformed request) must not send the person to re-paste a good key. */
    @Test fun aBadRequestThatIsNotTheKeyIsTheGenericCloudError() {
        saveKey("FAKETestKey1234")
        rig.server.enqueue(MockResponse().setResponseCode(400).setBody(BAD_REQUEST_BODY))
        assertEquals(BackupUi.Message(R.string.cloud_err_other, isError = true), testKeyAndWait())
    }

    @Test fun anUnknownModelNamesTheModelInTheError() {
        saveKey("FAKETestKey1234")
        vm.setModel("  gemini-retired  ")
        rig.waitFor { runBlocking { rig.graph.userPrefs.current().geminiModel } == "gemini-retired" }
        rig.server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(BackupUi.Message(R.string.cloud_err_model, listOf("gemini-retired"), isError = true), testKeyAndWait())
        assertTrue(rig.server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.endsWith("/models/gemini-retired"))
    }

    @Test fun aRateLimitSaysToTryLater() {
        saveKey("FAKETestKey1234")
        rig.server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(BackupUi.Message(R.string.cloud_err_limit, isError = true), testKeyAndWait())
    }

    @Test fun aServerErrorIsTheGenericCloudError() {
        saveKey("FAKETestKey1234")
        rig.server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(BackupUi.Message(R.string.cloud_err_other, isError = true), testKeyAndWait())
    }

    @Test fun noConnectionSaysToCheckTheConnection() {
        saveKey("FAKETestKey1234")
        rig.server.shutdown()
        assertEquals(BackupUi.Message(R.string.cloud_err_network, isError = true), testKeyAndWait())
    }

    /** The store cleans a paste; the client's own refusal of a raw key is tested in GeminiClientTest. */
    @Test fun aPastedKeyWithASpaceAndAnInvisibleCharacterIsTestedAsTheCleanedKey() {
        saveKey("FAKE Pasted​Key")
        assertEquals("FAKEPastedKey", rig.graph.secrets.key)
        rig.server.enqueue(MockResponse().setBody("""{"name":"models/x"}"""))
        assertEquals(BackupUi.Message(R.string.settings_cloud_test_ok, listOf(UserSettings.DEFAULT_GEMINI_MODEL)), testKeyAndWait())
        assertEquals("FAKEPastedKey", rig.server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("x-goog-api-key"))
    }

    @Test fun testingWithNoKeyDoesNothing() {
        vm.testKey()
        rig.waitFor { true }
        assertFalse(vm.cloud.value.testing)
        assertNull(vm.cloud.value.message)
        assertEquals(0, rig.server.requestCount)
    }

    // ---- Preferences ----

    @Test fun eachSwitchAndChoiceIsStored() {
        vm.setSecondLookEnabled(false)
        vm.setSecondLookDays(10)
        vm.setSaveSquares(true)
        vm.setHighContrast(true)
        rig.waitFor {
            val s = runBlocking { rig.graph.userPrefs.current() }
            !s.secondLookEnabled && s.secondLookDays == 10 && s.saveSquaresToPhotos && s.highContrast
        }
    }

    @Test fun choosingAThemeStoresItAndAppliesItToTheApp() {
        val ui = rig.context.getSystemService(UiModeManager::class.java)
        vm.setThemeMode(ThemeMode.DARK)
        rig.waitFor { shadowOf(ui).applicationNightMode == UiModeManager.MODE_NIGHT_YES }
        assertEquals(ThemeMode.DARK, runBlocking { rig.graph.userPrefs.current() }.themeMode)
        vm.setThemeMode(ThemeMode.LIGHT)
        rig.waitFor { shadowOf(ui).applicationNightMode == UiModeManager.MODE_NIGHT_NO }
        vm.setThemeMode(ThemeMode.SYSTEM)
        rig.waitFor { shadowOf(ui).applicationNightMode == UiModeManager.MODE_NIGHT_AUTO }
        assertEquals(ThemeMode.SYSTEM, runBlocking { rig.graph.userPrefs.current() }.themeMode)
    }

    // ---- Backup folder ----

    private fun prefs() = runBlocking { rig.backupPrefs.current() }

    /** A folder a storage app serves, picked through the ViewModel; returns the provider to misbehave with. */
    private fun pickUsableFolder(): FolderDocumentsProvider {
        val provider = FolderDocumentsProvider.install(File(rig.scratch, "Coffee backups"))
        vm.folderPicked(FolderDocumentsProvider.TREE)
        rig.waitFor { vm.ui.value.folderName == "Coffee backups" }
        return provider
    }

    private fun assertBackupsOff(why: BackupOff) {
        val s = prefs()
        assertNull(s.folderUri)
        assertFalse(s.scheduled)
        assertEquals(why, s.off)
        assertNull(vm.ui.value.folderName)
    }

    @Test fun pickingAFolderKeepsTheGrantAndTurnsDailyBackupOn() {
        pickUsableFolder()
        assertTrue(prefs().scheduled)
        assertTrue(rig.backups.folderStillGranted(FolderDocumentsProvider.TREE.toString()))
        assertNull(prefs().off)
    }

    @Test fun aFolderWhoseGrantIsGoneIsForgottenAndBackupsTurnOff() {
        runBlocking { rig.backupPrefs.setFolder(SettingsRig.TREE.toString()) }
        assertNull(vm.ui.value.message) // creating the ViewModel starts its folder check
        rig.waitFor { prefs().off != null }
        assertBackupsOff(BackupOff.FOLDER_GONE)
    }

    @Test fun aGrantedFolderWhoseNameCantBeReadStaysChosenUntilABackupToItFails() {
        // Granted, but no installed storage app answers for it right now (a deleted folder, or an app
        // mid-update). Opening Settings alone must not turn backups off: only a backup that fails does.
        collectState()
        vm.folderPicked(SettingsRig.TREE)
        rig.waitFor { vm.state.value.folderUri != null }
        rig.waitFor { vm.ui.value.folderName != null }
        assertEquals(rig.context.getString(R.string.settings_backup_folder_unnamed), vm.ui.value.folderName)
        assertNull(prefs().off)
        assertTrue(prefs().scheduled)

        vm.backUpNow()
        rig.waitFor { prefs().off != null }
        assertBackupsOff(BackupOff.FOLDER_GONE)
    }

    @Test fun choosingAFolderAfterBackupsWentOffTurnsThemBackOn() {
        collectState()
        vm.folderPicked(SettingsRig.TREE)
        rig.waitFor { vm.state.value.folderUri != null }
        vm.backUpNow()
        rig.waitFor { prefs().off != null }
        pickUsableFolder()
        assertNull(prefs().off)
        assertTrue(prefs().scheduled)
    }

    @Test fun theDailySwitchIsStored() {
        pickUsableFolder()
        vm.setScheduled(false)
        rig.waitFor { !prefs().scheduled }
    }

    @Test fun backUpNowWithNoFolderDoesNothing() {
        collectState()
        rig.waitFor { true }
        vm.backUpNow()
        assertEquals(BackupUi.Working.NONE, vm.ui.value.working)
        assertNull(vm.ui.value.message)
    }

    @Test fun aBackupThatFailsInAUsableFolderSaysItFailedAndKeepsTheFolder() {
        collectState()
        pickUsableFolder().failWrites = true
        rig.waitFor { vm.state.value.folderUri != null }
        vm.backUpNow()
        rig.waitFor { vm.ui.value.message != null }
        assertEquals(BackupUi.Working.NONE, vm.ui.value.working)
        assertEquals(
            BackupUi.Message(R.string.settings_backup_failed, listOf(rig.context.getString(R.string.error_backup_io)), isError = true),
            vm.ui.value.message,
        )
        rig.waitFor { vm.state.value.lastError != null }
        assertEquals(FolderDocumentsProvider.TREE.toString(), prefs().folderUri)
        assertTrue(prefs().scheduled)
        assertNull(prefs().off)
    }

    @Test fun aFolderThatRefusesTheFileNameIsForgottenAndBackupsTurnOff() {
        runBlocking { rig.graph.insertCoffee(java.util.UUID.randomUUID().toString()) { copy(roaster = "Larkmoor") } }
        collectState()
        pickUsableFolder().refuseRename = true
        rig.waitFor { vm.state.value.folderUri != null }
        vm.backUpNow()
        rig.waitFor { vm.ui.value.working == BackupUi.Working.NONE && prefs().off != null }
        assertBackupsOff(BackupOff.FOLDER_REFUSED)
        // The card's "Backups are off" line says it once; no second "Couldn't back up" below the buttons.
        assertNull(vm.ui.value.message)
    }

    @Test fun aBackupToTheChosenFolderWritesAZipAndSaysWhatItSaved() {
        val folder = File(rig.scratch, "Coffee backups")
        FolderDocumentsProvider.install(folder)
        runBlocking { repeat(2) { rig.graph.insertCoffee(java.util.UUID.randomUUID().toString()) { copy(roaster = "Northwind") } } }
        collectState()
        vm.folderPicked(FolderDocumentsProvider.TREE)
        rig.waitFor { vm.ui.value.folderName == "Coffee backups" }
        vm.backUpNow()
        rig.waitFor { vm.ui.value.message != null }
        val zip = folder.listFiles()!!.single()
        assertTrue(BackupManager.OUR_ZIP.matches(zip.name))
        val coffees = rig.context.resources.getQuantityString(R.plurals.coffees, 2, 2)
        assertEquals(BackupUi.Message(R.string.settings_backup_done, listOf(coffees, SettingsViewModel.humanBytes(zip.length()))), vm.ui.value.message)
        assertEquals(BackupUi.Working.NONE, vm.ui.value.working)
        rig.waitFor { vm.state.value.lastBackupName == zip.name }
    }

    // ---- Restore ----

    @Test fun pickingABackupAsksBeforeReplacingAndCancelLeavesEverything() {
        val zip = rig.backupZip(n = 3)
        vm.restorePicked(Uri.fromFile(zip))
        rig.waitFor { vm.ui.value.pendingRestore != null }
        val pending = vm.ui.value.pendingRestore!!
        assertEquals(3, pending.manifest.coffees)
        assertEquals(Uri.fromFile(zip), pending.uri)
        vm.cancelRestore()
        assertNull(vm.ui.value.pendingRestore)
        assertEquals(3, runBlocking { rig.graph.dao.allIds() }.size)
    }

    @Test fun confirmingARestoreReplacesTheLibraryAndSaysHowManyCoffeesCameBack() {
        val zip = rig.backupZip(n = 2)
        runBlocking { rig.graph.insertCoffee(java.util.UUID.randomUUID().toString()) { copy(roaster = "Larkmoor") } }
        vm.restorePicked(Uri.fromFile(zip))
        rig.waitFor { vm.ui.value.pendingRestore != null }
        vm.confirmRestore()
        assertNull(vm.ui.value.pendingRestore)
        rig.waitFor { vm.ui.value.working == BackupUi.Working.NONE && vm.ui.value.message != null }
        val coffees = rig.context.resources.getQuantityString(R.plurals.coffees, 2, 2)
        assertEquals(BackupUi.Message(R.string.settings_restore_done, listOf(coffees)), vm.ui.value.message)
        assertEquals(rig.seededIds.toSet(), runBlocking { rig.graph.dao.allIds() }.toSet())
    }

    /**
     * After the rows land, the restore finishes the follow-up a start would do: a scored
     * coffee left LOGGED is repaired to RATED, and a restored reminder is armed again.
     */
    @Test fun aRestoreRepairsRatedStatesAndArmsTheRestoredReminders() {
        val remindAt = System.currentTimeMillis() + 3_600_000L
        val reminded = java.util.UUID.randomUUID().toString()
        runBlocking { rig.graph.insertCoffee(reminded) { copy(roaster = "Stillwick Roasters", remindAt = remindAt) } }
        val zip = rig.backupZip(n = 2) // LOGGED rows that carry a score
        val alarms = shadowOf(rig.context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(alarms.scheduledAlarms.isEmpty())

        vm.restorePicked(Uri.fromFile(zip))
        rig.waitFor { vm.ui.value.pendingRestore != null }
        vm.confirmRestore()
        rig.waitFor { vm.ui.value.working == BackupUi.Working.NONE && vm.ui.value.message != null }

        rig.seededIds.forEach { assertEquals(dev.roastbook.app.data.db.CoffeeState.RATED, runBlocking { rig.graph.dao.get(it)!!.state }) }
        assertEquals(listOf(remindAt), alarms.scheduledAlarms.map { it.triggerAtMs })
    }

    @Test fun confirmingWithNothingPendingDoesNothing() {
        vm.confirmRestore()
        assertEquals(BackupUi.Working.NONE, vm.ui.value.working)
        assertNull(vm.ui.value.message)
    }

    private fun pickFails(uri: Uri): BackupUi.Message {
        vm.restorePicked(uri)
        rig.waitFor { vm.ui.value.message != null }
        assertNull(vm.ui.value.pendingRestore)
        return vm.ui.value.message!!
    }

    private fun confirmFails(file: File, beforeConfirm: () -> Unit = {}): BackupUi.Message {
        vm.restorePicked(Uri.fromFile(file))
        rig.waitFor { vm.ui.value.pendingRestore != null }
        beforeConfirm()
        vm.confirmRestore()
        rig.waitFor { vm.ui.value.working == BackupUi.Working.NONE && vm.ui.value.message != null }
        return vm.ui.value.message!!
    }

    private fun restoreFailed(error: Int) =
        BackupUi.Message(R.string.settings_restore_failed, listOf(rig.context.getString(error)), isError = true)

    @Test fun aFileThatIsNotABackupIsRefusedAtPickTime() {
        assertEquals(restoreFailed(R.string.error_backup_bad_archive), pickFails(Uri.fromFile(rig.junkFile())))
    }

    @Test fun aBackupWithADirectoryEntryIsRefusedAsUnsafe() {
        val zip = rig.rewriteZip(rig.backupZip(n = 1), extra = mapOf("photos/extra/" to ByteArray(0)))
        assertEquals(restoreFailed(R.string.error_backup_unsafe), pickFails(Uri.fromFile(zip)))
    }

    @Test fun aFileThatCannotBeOpenedSaysTheFolderCouldNotBeReached() {
        assertEquals(restoreFailed(R.string.error_backup_io), pickFails(Uri.fromFile(File(rig.scratch, "gone.zip"))))
    }

    @Test fun aBackupThatChangedAfterItWasPickedIsRefused() {
        val zip = rig.backupZip(n = 1)
        val ids = runBlocking { rig.graph.dao.allIds() }
        val m = confirmFails(zip) { zip.appendBytes(byteArrayOf(0)) }
        assertEquals(restoreFailed(R.string.error_backup_changed), m)
        assertEquals(ids, runBlocking { rig.graph.dao.allIds() })
    }

    @Test fun aDamagedPhotoInTheBackupIsRefusedAsCorrupt() {
        val zip = rig.backupZip(n = 1)
        // The checksum covers the database copy too; damaging it is enough.
        val damaged = rig.rewriteZip(zip) { name, bytes -> if (name == BackupManager.DB_ENTRY) bytes.also { it[200] = (it[200] + 1).toByte() } else bytes }
        assertEquals(restoreFailed(R.string.error_backup_corrupt), confirmFails(damaged))
    }

    @Test fun aBackupFromANewerAppVersionAsksForAnUpdate() {
        val future = rig.rewriteZip(rig.backupZip(n = 1)) { name, bytes ->
            if (name == BackupManager.MANIFEST_ENTRY) bytes.decodeToString().replace(Regex("\"schemaVersion\":\\d+"), "\"schemaVersion\":99")
                .also { check(it.contains("\"schemaVersion\":99")) }.toByteArray() else bytes
        }
        assertEquals(restoreFailed(R.string.error_backup_newer), confirmFails(future))
    }

    @Test fun aBackupTooLargeForTheFreeSpaceSaysSo() {
        val huge = rig.rewriteZip(rig.backupZip(n = 1)) { name, bytes ->
            if (name == BackupManager.MANIFEST_ENTRY) bytes.decodeToString().replaceFirst(Regex("\"size\":\\d+"), "\"size\":${1L shl 60}")
                .also { check(it.contains("\"size\":${1L shl 60}")) }.toByteArray() else bytes
        }
        assertEquals(restoreFailed(R.string.error_backup_space), confirmFails(huge))
    }

    private companion object {
        /** The shape of Google's answer to a key it does not accept. */
        const val BAD_KEY_BODY = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",""" +
            """"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"""
        /** The shape of Google's answer to a request body it cannot use. */
        const val BAD_REQUEST_BODY = """{"error":{"code":400,"message":"Invalid JSON payload received.","status":"INVALID_ARGUMENT",""" +
            """"details":[{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{"field":"contents[0]"}]}]}}"""
    }

    // ---- Formatting ----

    @Test fun theLastBackupTimeReadsNaturallyInTheMiddleOfItsLine() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            val zone = java.time.ZoneId.of("America/Los_Angeles")
            // Wednesday 2026-11-25, 15:00 local time.
            val now = java.time.ZonedDateTime.of(2026, 11, 25, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
            val s = 1000L
            fun at(ago: Long) = SettingsViewModel.lastBackupWhen(now - ago, now, "just now", "yesterday", zone)
            assertEquals("just now", at(0))
            assertEquals("just now", at(59 * s))
            assertEquals("a clock moved back is not the future", "just now", at(-120 * s))
            assertEquals("1 minute ago", at(60 * s))
            assertEquals("1 minute ago", at(119 * s))
            assertEquals("2 minutes ago", at(120 * s))
            assertEquals("1 hour ago", at(3600 * s))
            assertEquals("still today, so hours", "14 hours ago", at(14 * 3600 * s))
            assertEquals("the day before, under a day ago", "16 hours ago", at(16 * 3600 * s))
            assertEquals("yesterday", at(26 * 3600 * s))
            assertEquals("3 days ago", at(3 * 86400 * s))
            assertEquals("Nov 17, 2026", at(8 * 86400 * s))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun sizesReadAsBytesKilobytesOrMegabytes() {
        assertEquals("512 B", SettingsViewModel.humanBytes(512))
        assertEquals("2 KB", SettingsViewModel.humanBytes(2048))
        assertEquals("1.5 MB", SettingsViewModel.humanBytes(3L shl 19))
    }
}
