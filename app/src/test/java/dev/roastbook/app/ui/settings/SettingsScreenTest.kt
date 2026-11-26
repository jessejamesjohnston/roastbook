package dev.roastbook.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.roastbook.app.R
import dev.roastbook.app.data.backup.NO_SPACE_ERROR
import dev.roastbook.app.data.prefs.UserSettings
import dev.roastbook.app.ui.theme.RoastbookTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Settings screen as a person uses it: what each card shows and what each control changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsScreenTest {
    // Effects run as soon as they are launched (the v1 rule's behavior). The backup card collects
    // DataStore flows that emit from other threads; under the v2 rule's queued default that
    // collection can lag the polling in eventually by more than its timeout.
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = UnconfinedTestDispatcher())
    private val rig = SettingsRig()
    private val context get() = rig.context
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private var backs = 0
    private var licenses = 0

    @After fun tearDown() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false)
        rig.close()
    }

    private fun show() {
        compose.setContent {
            RoastbookTheme { SettingsScreen(onBack = { backs++ }, onLicenses = { licenses++ }, vm = rig.vm, readerSection = {}) }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any): SemanticsNodeInteraction = compose.onNodeWithText(str(id, *args))
    private fun plural(id: Int, n: Int) = context.resources.getQuantityString(id, n, n)
    /**
     * Waits for work that hops off the main thread (DataStore, zips, HTTP). ViewModel launches run on
     * the main looper, which only proceeds when the rule idles; poll between idles.
     */
    private fun eventually(timeoutMs: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            compose.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            Thread.sleep(10)
        }
    }
    /**
     * Taps the control labeled [id] once it is enabled. The folder's name and the backup controls'
     * enabled state come from two separate DataStore collectors, so the name can show while the
     * controls are still disabled, and a tap on a disabled control is silently dropped.
     */
    private fun tapWhenEnabled(id: Int) {
        eventually { SemanticsProperties.Disabled !in compose.onNode(hasText(str(id))).fetchSemanticsNode().config }
        text(id).performScrollTo().performClick()
    }
    private fun waitForText(value: String) = eventually { compose.onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(value: String) = compose.onAllNodes(hasText(value)).fetchSemanticsNodes().isEmpty()
    private val keyField get() = compose.onNode(hasSetTextAction() and hasText(str(R.string.settings_cloud_key_hint)))
    private val modelField get() = compose.onNode(hasSetTextAction() and hasText(str(R.string.settings_cloud_model)))

    /** Returns the picker the screen just launched with [result] as its answer (null = the person backed out). */
    private fun answerPicker(result: Uri?): Intent {
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        shadowOf(compose.activity).receiveResult(started.intent, if (result != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            result?.let { Intent().setData(it) })
        compose.waitForIdle()
        return started.intent
    }

    // ---- Top bar ----

    @Test fun theBackArrowLeavesSettings() {
        show()
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        assertEquals(1, backs)
    }

    // ---- Backup ----

    @Test fun withNoFolderDailyBackupIsOffUnavailableAndSaysWhy() {
        show()
        text(R.string.settings_backup_folder_none).assertExists()
        text(R.string.settings_choose_folder).assertIsEnabled()
        text(R.string.settings_last_backup_never).assertExists()
        // Nothing is kept anywhere until there is a folder, and a folder never chosen isn't "off".
        assertTrue(gone(plural(R.plurals.settings_backup_keeps, dev.roastbook.app.data.backup.BackupState.DEFAULT_KEEP)))
        assertTrue(gone(str(R.string.settings_backup_off_gone)))
        text(R.string.settings_daily_backup).assertIsOff().assertIsNotEnabled()
        text(R.string.settings_daily_backup_needs_folder).assertExists()
        text(R.string.settings_back_up_now).assertIsNotEnabled()
    }

    @Test fun choosingAFolderInThePickerTurnsDailyBackupOn() {
        FolderDocumentsProvider.install(java.io.File(rig.scratch, "Coffee backups"))
        show()
        text(R.string.settings_choose_folder).performScrollTo().performClick()
        val picker = answerPicker(FolderDocumentsProvider.TREE)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.action)
        waitForText("Coffee backups")
        text(R.string.settings_change_folder).assertExists()
        text(R.string.settings_daily_backup).assertIsOn().assertIsEnabled()
        text(R.string.settings_daily_backup_hint).assertExists()
        compose.onNodeWithText(plural(R.plurals.settings_backup_keeps, dev.roastbook.app.data.backup.BackupState.DEFAULT_KEEP)).assertExists()
        text(R.string.settings_back_up_now).assertIsEnabled()
        assertTrue(rig.backups.folderStillGranted(FolderDocumentsProvider.TREE.toString()))
    }

    @Test fun backingOutOfTheFolderPickerChangesNothing() {
        show()
        text(R.string.settings_choose_folder).performScrollTo().performClick()
        answerPicker(null)
        text(R.string.settings_choose_folder).assertExists()
        assertNull(runBlocking { rig.backupPrefs.current() }.folderUri)
    }

    @Test fun theDailySwitchTurnsScheduledBackupsOff() {
        FolderDocumentsProvider.install(java.io.File(rig.scratch, "Coffee backups"))
        rig.vm.folderPicked(FolderDocumentsProvider.TREE)
        show()
        waitForText("Coffee backups")
        tapWhenEnabled(R.string.settings_daily_backup)
        eventually { !runBlocking { rig.backupPrefs.current() }.scheduled }
        text(R.string.settings_daily_backup).assertIsOff()
    }

    /** Everything the card shows once backups went off: why, the way back, and nothing that claims they run. */
    private fun assertBackupsOffSays(line: Int) {
        waitForText(str(line))
        text(R.string.settings_backup_folder_none).assertExists()
        text(R.string.settings_choose_folder).assertIsEnabled()
        text(R.string.settings_daily_backup).assertIsOff().assertIsNotEnabled()
        text(R.string.settings_daily_backup_needs_folder).assertExists()
        text(R.string.settings_back_up_now).assertIsNotEnabled()
        assertTrue(gone(plural(R.plurals.settings_backup_keeps, dev.roastbook.app.data.backup.BackupState.DEFAULT_KEEP)))
        assertTrue(gone(str(R.string.settings_last_backup_failed, str(R.string.error_backup_io))))
        assertTrue(gone(str(R.string.settings_backup_failed, str(R.string.error_backup_io))))
    }

    @Test fun aFolderWhoseAccessIsGoneTurnsBackupsOffAndSaysToChooseAFolder() {
        runBlocking { rig.backupPrefs.setFolder(SettingsRig.TREE.toString()) }
        show()
        assertBackupsOffSays(R.string.settings_backup_off_gone)
    }

    @Test fun aGrantedFolderThatCantBeReadStaysChosenUntilABackupToItFails() {
        show()
        text(R.string.settings_choose_folder).performScrollTo().performClick()
        answerPicker(SettingsRig.TREE)
        // Opening Settings with the storage app silent says so and keeps backups on...
        waitForText(str(R.string.settings_backup_folder_unnamed))
        assertTrue(gone(str(R.string.settings_backup_off_gone)))
        // ...and a backup that then fails turns them off, saying why.
        text(R.string.settings_back_up_now).performScrollTo().performClick()
        assertBackupsOffSays(R.string.settings_backup_off_gone)
    }

    @Test fun aFolderThatRefusesTheFileTurnsBackupsOffAndChoosingAnotherTurnsThemOn() {
        val provider = FolderDocumentsProvider.install(java.io.File(rig.scratch, "Coffee backups"))
        provider.refuseRename = true
        rig.vm.folderPicked(FolderDocumentsProvider.TREE)
        show()
        waitForText("Coffee backups")
        tapWhenEnabled(R.string.settings_back_up_now)
        assertBackupsOffSays(R.string.settings_backup_off_refused)
        assertTrue(gone(str(R.string.settings_backup_off_gone)))
        // Choosing a folder clears the off line and turns daily backup back on.
        provider.refuseRename = false
        text(R.string.settings_choose_folder).performScrollTo().performClick()
        answerPicker(FolderDocumentsProvider.TREE)
        waitForText("Coffee backups")
        eventually { gone(str(R.string.settings_backup_off_refused)) }
        text(R.string.settings_daily_backup).assertIsOn()
    }

    @Test fun theLastBackupLineGivesWhenHowBigAndHowManyCoffees() {
        val at = System.currentTimeMillis() - 3 * DateUtils.DAY_IN_MILLIS
        runBlocking { rig.backupPrefs.recordSuccess(at, "roastbook-test.zip", 3L shl 20, 4) }
        show()
        val expected = str(
            R.string.settings_last_backup,
            DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            SettingsViewModel.humanBytes(3L shl 20),
            context.resources.getQuantityString(R.plurals.coffees, 4, 4),
        )
        waitForText(expected)
        assertTrue(gone(str(R.string.settings_last_backup_never)))
    }

    @Test fun aBackupThatJustRanSaysJustNow() {
        runBlocking { rig.backupPrefs.recordSuccess(System.currentTimeMillis(), "roastbook-test.zip", 2048, 1) }
        show()
        waitForText(
            str(
                R.string.settings_last_backup, str(R.string.settings_last_backup_just_now),
                SettingsViewModel.humanBytes(2048), context.resources.getQuantityString(R.plurals.coffees, 1, 1),
            ),
        )
    }

    @Test fun aScheduledBackupThatFailedIsShownUntilTheNextSuccessAndKeepsBackupsOn() {
        FolderDocumentsProvider.install(java.io.File(rig.scratch, "Coffee backups"))
        // Stored before the ViewModel starts, as a daily run would have left it.
        context.contentResolver.takePersistableUriPermission(FolderDocumentsProvider.TREE, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runBlocking { rig.backupPrefs.setFolder(FolderDocumentsProvider.TREE.toString()); rig.backupPrefs.recordError("offline") }
        show()
        waitForText(str(R.string.settings_last_backup_failed, str(R.string.error_backup_io)))
        text(R.string.settings_daily_backup).assertIsOn()
        assertTrue(gone(str(R.string.settings_backup_off_gone)))
        runBlocking { rig.backupPrefs.recordSuccess(System.currentTimeMillis(), "roastbook-test.zip", 10, 1) }
        eventually { gone(str(R.string.settings_last_backup_failed, str(R.string.error_backup_io))) }
    }

    @Test fun aBackupThatRanOutOfSpaceSaysSoInsteadOfBlamingTheConnection() {
        FolderDocumentsProvider.install(java.io.File(rig.scratch, "Coffee backups"))
        context.contentResolver.takePersistableUriPermission(FolderDocumentsProvider.TREE, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runBlocking { rig.backupPrefs.setFolder(FolderDocumentsProvider.TREE.toString()); rig.backupPrefs.recordError(NO_SPACE_ERROR + "ENOSPC") }
        show()
        waitForText(str(R.string.settings_last_backup_failed, str(R.string.error_backup_full)))
        assertTrue(gone(str(R.string.settings_last_backup_failed, str(R.string.error_backup_io))))
    }

    @Test fun backUpNowShowsProgressThenTheFailureWhenTheFolderIsUnreachable() {
        FolderDocumentsProvider.install(java.io.File(rig.scratch, "Coffee backups")).failWrites = true
        rig.vm.folderPicked(FolderDocumentsProvider.TREE)
        show()
        waitForText("Coffee backups")
        // Hold the photo capture lock so the backup waits at its first step, where it can be seen running.
        runBlocking { rig.graph.photos.captureLock.lock() }
        try {
            tapWhenEnabled(R.string.settings_back_up_now)
            waitForText(str(R.string.settings_backing_up))
            text(R.string.settings_change_folder).assertIsNotEnabled()
            text(R.string.settings_restore).assertIsNotEnabled()
            text(R.string.settings_daily_backup).assertIsNotEnabled()
        } finally {
            rig.graph.photos.captureLock.unlock()
        }
        eventually { rig.vm.ui.value.working == BackupUi.Working.NONE }
        waitForText(str(R.string.settings_backup_failed, str(R.string.error_backup_io)))
        // A failure that may pass keeps the folder and daily backup.
        text(R.string.settings_back_up_now).assertIsEnabled()
        text(R.string.settings_change_folder).assertIsEnabled()
        text(R.string.settings_daily_backup).assertIsOn()
        assertTrue(gone(str(R.string.settings_backup_off_gone)))
    }

    @Test fun backUpNowToTheChosenFolderSavesAZipAndShowsTheNewLastBackup() {
        val folder = java.io.File(rig.scratch, "Coffee backups")
        FolderDocumentsProvider.install(folder)
        runBlocking { rig.graph.insertCoffee(java.util.UUID.randomUUID().toString()) { copy(roaster = "Sable & Rye") } }
        show()
        text(R.string.settings_choose_folder).performScrollTo().performClick()
        answerPicker(FolderDocumentsProvider.TREE)
        // The folder's own name replaces "None chosen".
        waitForText("Coffee backups")
        tapWhenEnabled(R.string.settings_back_up_now)
        val one = context.resources.getQuantityString(R.plurals.coffees, 1, 1)
        eventually { folder.listFiles().orEmpty().any { it.name.endsWith(".zip") } && rig.vm.ui.value.message != null }
        val zip = folder.listFiles()!!.single()
        waitForText(str(R.string.settings_backup_done, one, SettingsViewModel.humanBytes(zip.length())))
        eventually { gone(str(R.string.settings_last_backup_never)) }
    }

    // ---- Restore ----

    private fun pickBackup(n: Int): android.net.Uri {
        val zip = rig.backupZip(n)
        show()
        text(R.string.settings_restore).performScrollTo().performClick()
        val uri = Uri.fromFile(zip)
        val picker = answerPicker(uri)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertTrue(picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("application/zip"))
        return uri
    }

    @Test fun aPickedBackupAsksBeforeReplacingWithItsDateAndCounts() {
        pickBackup(n = 2)
        waitForText(str(R.string.settings_restore_title))
        val p = rig.vm.ui.value.pendingRestore!!
        val date = SettingsViewModel.backupMadeAt(p.manifest.createdAt)
        text(
            R.string.settings_restore_body, date,
            context.resources.getQuantityString(R.plurals.coffees, 2, 2), context.resources.getQuantityString(R.plurals.photos, 0, 0),
        ).assertExists()
    }

    /** The app's strings are English only, so the backup's date inside the sentence is English too. */
    @Test @Config(qualifiers = "de")
    fun theRestoreQuestionDatesTheBackupInEnglishOnAGermanDevice() = restoreDateIsEnglish()

    @Test @Config(qualifiers = "ar")
    fun theRestoreQuestionDatesTheBackupInEnglishOnAnArabicDevice() {
        // Only the date is checked here: the counts come from plurals, formatted by Android.
        val english = restoreQuestionShown()
        compose.onNode(hasText(english, substring = true)).assertExists()
    }

    private fun restoreDateIsEnglish() {
        val english = restoreQuestionShown()
        text(
            R.string.settings_restore_body, english,
            context.resources.getQuantityString(R.plurals.coffees, 2, 2), context.resources.getQuantityString(R.plurals.photos, 0, 0),
        ).assertExists()
    }

    /** Opens the restore question for a two-coffee backup; returns its date as an English reader expects it. */
    private fun restoreQuestionShown(): String {
        pickBackup(n = 2)
        waitForText(str(R.string.settings_restore_title))
        val created = rig.vm.ui.value.pendingRestore!!.manifest.createdAt
        return java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", java.util.Locale.US)
            .format(java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault()))
    }

    @Test fun confirmingTheRestoreReplacesTheLibraryAndSaysSo() {
        pickBackup(n = 2)
        waitForText(str(R.string.settings_restore_yes))
        runBlocking { rig.graph.insertCoffee(java.util.UUID.randomUUID().toString()) { copy(roaster = "Stillwick Roasters") } }
        text(R.string.settings_restore_yes).performClick()
        waitForText(str(R.string.settings_restore_done, context.resources.getQuantityString(R.plurals.coffees, 2, 2)))
        assertTrue(gone(str(R.string.settings_restore_title)))
        assertEquals(rig.seededIds.toSet(), runBlocking { rig.graph.dao.allIds() }.toSet())
    }

    @Test fun cancelingTheRestoreClosesTheDialogAndKeepsTheLibrary() {
        pickBackup(n = 1)
        waitForText(str(R.string.settings_restore_title))
        val extra = java.util.UUID.randomUUID().toString()
        runBlocking { rig.graph.insertCoffee(extra) { copy(roaster = "Northwind") } }
        text(R.string.detail_cancel).performClick()
        compose.waitForIdle()
        assertTrue(gone(str(R.string.settings_restore_title)))
        assertTrue(extra in runBlocking { rig.graph.dao.allIds() })
    }

    @Test fun aFileThatIsNotABackupIsRefusedInline() {
        show()
        text(R.string.settings_restore).performScrollTo().performClick()
        answerPicker(Uri.fromFile(rig.junkFile()))
        waitForText(str(R.string.settings_restore_failed, str(R.string.error_backup_bad_archive)))
        assertTrue(gone(str(R.string.settings_restore_title)))
    }

    // ---- Cloud AI ----

    @Test fun withNoKeyTheCardSaysBagsAreReadOnDeviceAndOffersOnlySave() {
        show()
        text(R.string.settings_cloud_key_none).assertExists()
        text(R.string.settings_cloud_save).assertIsNotEnabled()
        assertTrue(gone(str(R.string.settings_cloud_test)))
        assertTrue(gone(str(R.string.settings_cloud_remove)))
        assertTrue(compose.onAllNodes(hasSetTextAction() and hasText(str(R.string.settings_cloud_model))).fetchSemanticsNodes().isEmpty())
    }

    @Test fun savingAKeyShowsItsLastFourCharactersAndClearsTheField() {
        show()
        keyField.performTextInput("FAKETestKey1234")
        text(R.string.settings_cloud_save).performScrollTo().assertIsEnabled().performClick()
        waitForText(str(R.string.settings_cloud_key_saved, "1234"))
        assertEquals("FAKETestKey1234", rig.graph.secrets.key)
        text(R.string.settings_cloud_save).assertIsNotEnabled()
        text(R.string.settings_cloud_test).assertExists()
        text(R.string.settings_cloud_remove).assertExists()
    }

    @Test fun testingAKeyShowsTestingThenTheResult() {
        runBlocking { rig.graph.secrets.setGeminiKey("FAKETestKey1234") }
        // The server holds its answer until the test has seen the in-flight state.
        val release = CountDownLatch(1)
        rig.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"name":"models/x"}""")
            }
        }
        show()
        try {
            text(R.string.settings_cloud_test).performScrollTo().performClick()
            waitForText(str(R.string.settings_cloud_testing))
            text(R.string.settings_cloud_testing).assertIsNotEnabled()
        } finally {
            release.countDown()
        }
        waitForText(str(R.string.settings_cloud_test_ok, UserSettings.DEFAULT_GEMINI_MODEL))
        text(R.string.settings_cloud_test).assertIsEnabled()
    }

    @Test fun aRefusedKeyShowsTheKeyError() {
        runBlocking { rig.graph.secrets.setGeminiKey("FAKETestKey1234") }
        rig.server.enqueue(MockResponse().setResponseCode(403))
        show()
        text(R.string.settings_cloud_test).performScrollTo().performClick()
        waitForText(str(R.string.cloud_err_key))
    }

    @Test fun removingTheKeyReturnsTheCardToNoKey() {
        runBlocking { rig.graph.secrets.setGeminiKey("FAKETestKey1234") }
        show()
        text(R.string.settings_cloud_key_saved, "1234").assertExists()
        text(R.string.settings_cloud_remove).performScrollTo().performClick()
        waitForText(str(R.string.settings_cloud_key_none))
        assertNull(rig.graph.secrets.key)
    }

    @Test fun theModelCanBeChangedOnceAKeyIsSaved() {
        runBlocking { rig.graph.secrets.setGeminiKey("FAKETestKey1234") }
        show()
        modelField.assertExists()
        // The Save action appears only when the name differs from the stored one.
        assertTrue(gone(str(R.string.detail_save)))
        modelField.performScrollTo().performTextReplacement("gemini-next-flash")
        text(R.string.detail_save).performScrollTo().performClick()
        eventually { runBlocking { rig.graph.userPrefs.current() }.geminiModel == "gemini-next-flash" }
        eventually { gone(str(R.string.detail_save)) }
    }

    @Test fun aBlankModelNameOffersNoSave() {
        runBlocking { rig.graph.secrets.setGeminiKey("FAKETestKey1234") }
        show()
        modelField.performScrollTo().performTextReplacement("  ")
        assertTrue(gone(str(R.string.detail_save)))
    }

    // ---- Reminders and photos ----

    @Test fun secondLookOffersItsIntervalsAndStoresTheChoice() {
        show()
        text(R.string.settings_second_look).performScrollTo().assertIsOn()
        compose.onNodeWithText(plural(R.plurals.settings_second_look_hint, UserSettings.DEFAULT_SECOND_LOOK_DAYS)).assertExists()
        UserSettings.SECOND_LOOK_CHOICES.forEach { compose.onNodeWithText(plural(R.plurals.settings_days, it)).assertExists() }
        compose.onNodeWithText(plural(R.plurals.settings_days, 10)).performScrollTo().performClick()
        eventually { runBlocking { rig.graph.userPrefs.current() }.secondLookDays == 10 }
        waitForText(plural(R.plurals.settings_second_look_hint, 10))
    }

    @Test fun turningSecondLookOffHidesTheIntervals() {
        show()
        text(R.string.settings_second_look).performScrollTo().performClick()
        eventually { !runBlocking { rig.graph.userPrefs.current() }.secondLookEnabled }
        eventually { gone(plural(R.plurals.settings_days, 3)) }
        text(R.string.settings_second_look).assertIsOff()
    }

    @Test fun savingBagPhotosToTheGalleryIsAStoredSwitch() {
        show()
        text(R.string.settings_save_squares).performScrollTo().assertIsOff().performClick()
        eventually { runBlocking { rig.graph.userPrefs.current() }.saveSquaresToPhotos }
        text(R.string.settings_save_squares).assertIsOn()
    }

    // ---- Appearance (the screen wires the section to the ViewModel) ----

    @Test fun theAppearanceControlsReachTheStoredSettings() {
        show()
        text(R.string.settings_theme_dark).performScrollTo().performClick()
        text(R.string.settings_high_contrast).performScrollTo().performClick()
        eventually {
            val s = runBlocking { rig.graph.userPrefs.current() }
            s.themeMode == dev.roastbook.app.data.prefs.ThemeMode.DARK && s.highContrast
        }
    }

    // ---- About ----

    private fun aboutRow(title: Int, subtitle: Int, kind: Int): SemanticsNodeInteraction {
        val description = str(kind, str(title), str(subtitle).removeSuffix("."))
        return compose.onNode(hasContentDescription(description)).performScrollTo()
    }
    private fun aboutContactRow() = compose.onNode(
        hasContentDescription(str(R.string.about_opens_email, str(R.string.about_contact), str(R.string.about_contact_address))),
    ).performScrollTo()

    @Test fun theAboutCardShowsTheVersionAndTheCopyrightLine() {
        show()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        text(R.string.settings_version, version).assertExists()
        text(R.string.app_subtitle).assertExists()
        text(R.string.settings_copyright).assertExists()
    }

    @Test fun helpAndPrivacyOpenTheirPagesInTheBrowser() {
        show()
        aboutRow(R.string.about_help, R.string.about_help_hint, R.string.about_opens_browser).performClick()
        val help = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, help.action)
        assertEquals(str(R.string.support_url), help.dataString)
        aboutRow(R.string.settings_privacy_policy, R.string.about_privacy_hint, R.string.about_opens_browser).performClick()
        assertEquals(str(R.string.privacy_policy_url), shadowOf(compose.activity).nextStartedActivity.dataString)
    }

    @Test fun withNoBrowserTheRowSaysWhichPageCouldNotOpen() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        show()
        aboutRow(R.string.about_help, R.string.about_help_hint, R.string.about_opens_browser).performClick()
        waitForText(str(R.string.about_no_browser, str(R.string.support_url)))
    }

    @Test fun contactWritesAnEmailWithTheVersionAsTheSubject() {
        show()
        aboutContactRow().performClick()
        val mail = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, mail.action)
        assertEquals("mailto:", mail.dataString)
        assertEquals(listOf(str(R.string.about_contact_address)), mail.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList())
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        assertEquals(str(R.string.settings_version, version), mail.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test fun withNoEmailAppContactSaysWhereToWrite() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        show()
        aboutContactRow().performClick()
        waitForText(str(R.string.about_contact_no_app, ""))
    }

    @Test fun theSharedRowsComeFirstInTheirFixedOrderThenHelpAndContact() {
        show()
        val browser = R.string.about_opens_browser
        val rows = listOf(
            str(browser, str(R.string.about_maker), str(R.string.about_maker_hint)),
            str(browser, str(R.string.about_source), str(R.string.about_source_hint)),
            str(browser, str(R.string.settings_privacy_policy), str(R.string.about_privacy_hint).removeSuffix(".")),
            str(R.string.about_row, str(R.string.settings_licenses), str(R.string.about_licenses_hint)),
            str(browser, str(R.string.about_license), str(R.string.about_license_hint)),
            str(R.string.about_row, str(R.string.update_check), str(R.string.update_check_hint)),
            str(browser, str(R.string.about_help), str(R.string.about_help_hint)),
            str(R.string.about_opens_email, str(R.string.about_contact), str(R.string.about_contact_address)),
        )
        // Scrolling moves the rows, so compare each pair after scrolling to the second of them.
        rows.zipWithNext().forEach { (a, b) ->
            compose.onNode(hasContentDescription(b)).performScrollTo()
            val ya = compose.onNode(hasContentDescription(a)).fetchSemanticsNode().positionInRoot.y
            val yb = compose.onNode(hasContentDescription(b)).fetchSemanticsNode().positionInRoot.y
            assertTrue("$a above $b", ya < yb)
        }
    }

    @Test fun sourceCodeAndLicenseOpenThePublicRepository() {
        show()
        aboutRow(R.string.about_source, R.string.about_source_hint, R.string.about_opens_browser).performClick()
        assertEquals(str(R.string.about_source_url), shadowOf(compose.activity).nextStartedActivity.dataString)
        aboutRow(R.string.about_license, R.string.about_license_hint, R.string.about_opens_browser).performClick()
        assertEquals(str(R.string.about_license_url), shadowOf(compose.activity).nextStartedActivity.dataString)
        aboutRow(R.string.about_maker, R.string.about_maker_hint, R.string.about_opens_browser).performClick()
        assertEquals(str(R.string.about_maker_url), shadowOf(compose.activity).nextStartedActivity.dataString)
    }

    private fun updateRow() = aboutRow(R.string.update_check, R.string.update_check_hint, R.string.about_row)
    /** Waits until the update row's second line reads [line] (the row is one TalkBack description). */
    private fun waitForUpdateLine(line: String) = eventually {
        compose.onAllNodes(hasContentDescription(str(R.string.about_row, str(R.string.update_check), line.removeSuffix(".")))).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun checkingForUpdatesFindsANewerReleaseAndOpensItsPage() {
        rig.server.enqueue(MockResponse().setBody("""{"tag_name":"v99.0.0"}"""))
        show()
        updateRow().performClick()
        waitForUpdateLine(str(R.string.update_available_hint, "99.0.0"))
        eventually { shadowOf(compose.activity).peekNextStartedActivity() != null }
        assertEquals(dev.roastbook.app.update.UpdateChecker.RELEASES_PAGE, shadowOf(compose.activity).nextStartedActivity.dataString)
        assertEquals("/repos/jessejamesjohnston/roastbook/releases/latest", rig.server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test fun checkingForUpdatesSaysSoWhenThisIsTheLatestOrNothingIsPublished() {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        rig.server.enqueue(MockResponse().setBody("""{"tag_name":"v$version"}"""))
        rig.server.enqueue(MockResponse().setResponseCode(404))
        show()
        updateRow().performClick()
        waitForUpdateLine(str(R.string.update_latest))
        compose.onNode(hasContentDescription(str(R.string.about_row, str(R.string.update_check), str(R.string.update_latest).removeSuffix("."))))
            .performScrollTo().performClick()
        waitForUpdateLine(str(R.string.update_no_release))
        assertNull("nothing opens without a newer release", shadowOf(compose.activity).peekNextStartedActivity())
    }

    @Test fun theWeeklyCheckStartsOffAndItsSwitchIsStoredAndArmsTheJob() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        show()
        text(R.string.update_weekly).performScrollTo().assertIsOff().performClick()
        eventually { runBlocking { rig.graph.userPrefs.current() }.weeklyUpdateCheck }
        text(R.string.update_weekly).assertIsOn()
        eventually {
            androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork(dev.roastbook.app.update.WeeklyUpdateCheck.WORK_NAME).get()
                .any { it.state == androidx.work.WorkInfo.State.ENQUEUED }
        }
        assertEquals("turning it on asks GitHub nothing by itself", 0, rig.server.requestCount)
    }

    @Test fun theLicensesRowOpensTheLicensesScreen() {
        show()
        aboutRow(R.string.settings_licenses, R.string.about_licenses_hint, R.string.about_row).performClick()
        assertEquals(1, licenses)
        assertFalse(shadowOf(compose.activity).peekNextStartedActivity() != null)
    }
}
