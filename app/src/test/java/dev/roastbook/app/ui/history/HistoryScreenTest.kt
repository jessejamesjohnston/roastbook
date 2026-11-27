package dev.roastbook.app.ui.history

import android.Manifest
import android.os.Looper
import java.time.Duration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.roastbook.app.HiltTestActivity
import dev.roastbook.app.R
import dev.roastbook.app.data.backup.BackupOff
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.DateRange
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.ui.components.PermissionAsks
import dev.roastbook.app.ui.theme.RoastbookTheme
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The History list: rows, search, sort, filters, the backup banner and the sheets it opens. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class HistoryScreenTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    // Effects run as soon as they are launched (the v1 rule's behavior). These screens collect Room and
    // Paging flows that emit from other threads; under the v2 rule's queued default the polling
    // in waitFor never sees their results.
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>(effectContext = UnconfinedTestDispatcher())

    @Inject lateinit var repo: CoffeeRepository
    @Inject lateinit var dao: CoffeeDao
    @Inject lateinit var pending: PendingActions
    @Inject lateinit var backupPrefs: BackupPrefs

    private val context get() = compose.activity
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun coffees(n: Int) = context.resources.getQuantityString(R.plurals.coffees, n, n)

    private val opened = mutableListOf<String>()
    private val ratingEditorOpened = mutableListOf<String>()
    private var adds = 0
    private var settings = 0
    /** Permission requests the screen launched; each is answered "granted" at once. */
    private val launched = mutableListOf<Any?>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        hilt.inject()
    }

    private fun vm(triedSheetFor: String? = null) =
        HistoryViewModel(SavedStateHandle(mapOf("triedSheetFor" to triedSheetFor)), repo, pending, backupPrefs)

    private fun insert(id: String, state: CoffeeState = CoffeeState.LOGGED, at: Long = 1_000, build: CoffeeEntity.() -> CoffeeEntity = { this }) =
        runBlocking { dao.insert(CoffeeEntity(id, at, at, at, state).build()) }

    private fun row(id: String) = runBlocking { dao.get(id) }

    private val grantingRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += input
            dispatchResult(requestCode, true)
        }
    }

    private fun show(vm: HistoryViewModel, selectedId: String? = null, highContrast: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = grantingRegistry
            }) {
                RoastbookTheme(darkTheme = false, highContrast = highContrast) {
                    HistoryScreen(
                        onAdd = { adds++ }, onOpen = { opened += it }, onOpenRatingEditor = { ratingEditorOpened += it },
                        onSettings = { settings++ }, selectedId = selectedId, vm = vm,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(text: String) = waitFor { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty() }

    /**
     * Polls in real time (Room and DataStore work on their own threads) while moving the main
     * looper's clock forward (the search debounce and the sheet animations wait on it).
     */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within 15 s")
            Thread.sleep(5)
        }
    }

    /** The dropdown is the newest window, so its item is the last node with the text. */
    private fun lastWithText(text: String) = compose.onAllNodesWithText(text).let { it[it.fetchSemanticsNodes().size - 1] }

    private fun openFilters() {
        compose.onNodeWithContentDescription(str(R.string.history_filters)).performClick()
        waitForText(str(R.string.filter_title))
    }

    // Empty book, header, banner

    @Test fun anEmptyBookShowsTheWelcomeAndNoBackupBanner() {
        show(vm())
        waitForText(str(R.string.history_empty_title))
        compose.onNodeWithText(str(R.string.history_empty_subtitle)).assertExists()
        compose.onNodeWithText(coffees(0)).assertExists()
        compose.onNodeWithText(str(R.string.banner_no_folder)).assertDoesNotExist()
    }

    @Test fun theHeaderCountsCoffeesAndHowManyAreLeftToRate() {
        insert("a") { copy(coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        insert("b", CoffeeState.RATED) { copy(coffeeName = "Copper Kettle", overall = 4f, extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText(str(R.string.history_count_to_rate, coffees(2), 1))
    }

    @Test fun aFullyRatedBookShowsOnlyTheCount() {
        insert("b", CoffeeState.RATED) { copy(coffeeName = "Copper Kettle", overall = 4f, extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Copper Kettle")
        compose.onNodeWithText(coffees(1)).assertExists()
    }

    @Test fun withNoBackupFolderTheBannerSaysSoAndOpensSettings() {
        insert("a") { copy(coffeeName = "Harbor Fog") }
        show(vm())
        waitForText(str(R.string.banner_no_folder))
        compose.onNodeWithText(str(R.string.banner_no_folder)).performClick()
        assertEquals(1, settings)
    }

    @Test fun aFolderThatNeverFinishedABackupStillAsksForOne() {
        insert("a") { copy(coffeeName = "Harbor Fog") }
        runBlocking { backupPrefs.setFolder("content://backups/tree") }
        show(vm())
        waitForText(str(R.string.banner_no_folder))
    }

    @Test fun aStaleBackupNamesHowManyDaysAgo() {
        insert("a") { copy(coffeeName = "Harbor Fog") }
        runBlocking {
            backupPrefs.setFolder("content://backups/tree")
            backupPrefs.recordSuccess(System.currentTimeMillis() - 5L * 24 * 60 * 60 * 1000 - 60_000, "roastbook.zip", 10, 1)
        }
        show(vm())
        waitForText(context.resources.getQuantityString(R.plurals.banner_no_backup, 5, 5))
    }

    @Test fun withBackupsOffTheBannerSaysSoAndLeadsToChoosingAFolder() {
        insert("a") { copy(coffeeName = "Harbor Fog") }
        runBlocking {
            backupPrefs.setFolder("content://backups/tree")
            // A recent backup doesn't make it safe: nothing will back up until a folder is chosen.
            backupPrefs.recordSuccess(System.currentTimeMillis(), "roastbook.zip", 10, 1)
            backupPrefs.folderUnusable("content://backups/tree", BackupOff.FOLDER_GONE)
        }
        show(vm())
        waitForText(str(R.string.banner_backup_off))
        compose.onNodeWithText(str(R.string.banner_no_folder)).assertDoesNotExist()
        compose.onAllNodesWithText(str(R.string.banner_back_up)).assertCountEquals(0)
        compose.onNodeWithText(str(R.string.settings_choose_folder)).assertExists()
        compose.onNodeWithText(str(R.string.banner_backup_off)).performClick()
        assertEquals(1, settings)
    }

    @Test fun choosingAFolderAgainTakesTheBackupsOffBannerAway() {
        insert("a") { copy(coffeeName = "Harbor Fog") }
        runBlocking {
            backupPrefs.setFolder("content://backups/tree")
            backupPrefs.folderUnusable("content://backups/tree", BackupOff.FOLDER_REFUSED)
        }
        show(vm())
        waitForText(str(R.string.banner_backup_off))
        runBlocking { backupPrefs.setFolder("content://backups/other") }
        waitForText(str(R.string.banner_no_folder))
        compose.onNodeWithText(str(R.string.banner_backup_off)).assertDoesNotExist()
    }

    @Test fun aRecentBackupShowsNoBanner() {
        insert("a") { copy(coffeeName = "Harbor Fog") }
        runBlocking {
            backupPrefs.setFolder("content://backups/tree")
            backupPrefs.recordSuccess(System.currentTimeMillis(), "roastbook.zip", 10, 1)
        }
        show(vm())
        waitForText("Harbor Fog")
        compose.onNodeWithText(str(R.string.banner_no_folder)).assertDoesNotExist()
        compose.onAllNodesWithText(str(R.string.banner_back_up)).assertCountEquals(0)
    }

    @Test fun theAddButtonAndTheSettingsButtonCallBack() {
        show(vm())
        compose.onNodeWithContentDescription(str(R.string.fab_add_coffee)).performClick()
        compose.onNodeWithContentDescription(str(R.string.history_settings)).performClick()
        assertEquals(1, adds)
        assertEquals(1, settings)
    }

    // Rows

    @Test fun aNamedCoffeeShowsItsNotesRoasterAndTheRateButton() {
        insert("c1") { copy(roaster = "Northwind", coffeeName = "Harbor Fog", tastingNotes = listOf("Cocoa", "Plum"), extractionSource = ExtractionSource.NANO) }
        show(vm())
        waitForText("Harbor Fog")
        compose.onNodeWithText("Cocoa · Plum", substring = true).assertExists()
        compose.onNodeWithText("Northwind · ", substring = true).assertExists()
        compose.onNodeWithText(str(R.string.row_unrated_cta)).assertExists()
    }

    @Test fun aRatedCoffeeShowsItsScoreForTalkBackAndNoRateButton() {
        insert("c1", CoffeeState.RATED) { copy(roaster = "Larkmoor", coffeeName = "Ember Ridge", overall = 4.2f, extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Ember Ridge")
        compose.onNode(hasContentDescription(str(R.string.row_rating_description, "%.1f".format(4.2f)))).assertExists()
        compose.onNodeWithText(str(R.string.row_unrated_cta)).assertDoesNotExist()
    }

    @Test fun aCoffeeWithOnlyARoasterIsTitledByTheRoaster() {
        insert("c1") { copy(roaster = "Stillwick Roasters", extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Stillwick Roasters")
        compose.onNodeWithText("Stillwick Roasters · ", substring = true).assertDoesNotExist()
    }

    @Test fun aCoffeeWithNothingReadIsUntitled() {
        insert("c1", CoffeeState.DRAFT) { copy(extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText(str(R.string.detail_untitled))
        compose.onNodeWithText(str(R.string.row_unrated_cta)).assertDoesNotExist()
    }

    @Test fun aFailedReadSaysSoAndOffersTheFix() {
        insert("c1") { copy(extractionSource = ExtractionSource.NONE) }
        show(vm())
        waitForText(str(R.string.row_extract_failed))
        compose.onNodeWithText(str(R.string.row_extract_failed_subtitle)).assertExists()
    }

    @Test fun aCoffeeBeingReadShowsTheReadingLinesAndItsPhotoCount() {
        insert("c1", CoffeeState.EXTRACTING)
        show(vm())
        waitForText(str(R.string.row_extracting))
        compose.onNodeWithText(str(R.string.row_extracting_subtitle)).assertExists()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.photos, 0, 0) + " · " + str(R.string.row_just_now)).assertExists()
        compose.onNodeWithText(str(R.string.row_unrated_cta)).assertDoesNotExist()
    }

    @Test fun tappingARowOpensThatCoffee() {
        insert("c1") { copy(coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Harbor Fog")
        compose.onNodeWithText("Harbor Fog").performClick()
        assertEquals(listOf("c1"), opened)
    }

    @Test fun onlyTheCoffeeOpenBesideTheListIsMarkedSelected() {
        insert("c1") { copy(coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        insert("c2") { copy(coffeeName = "Copper Kettle", extractionSource = ExtractionSource.OCR) }
        show(vm(), selectedId = "c2")
        waitForText("Copper Kettle")
        compose.onNodeWithText("Copper Kettle").assertIsSelected()
        compose.onNodeWithText("Harbor Fog").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test fun inHighContrastTheSelectedCoffeeIsStillTheOnlySelectedOne() {
        insert("c1") { copy(coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        insert("c2") { copy(coffeeName = "Copper Kettle", extractionSource = ExtractionSource.OCR) }
        show(vm(), selectedId = "c1", highContrast = true)
        waitForText("Copper Kettle")
        compose.onNodeWithText("Harbor Fog").assertIsSelected()
        compose.onNodeWithText("Copper Kettle").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    // Search and sort

    @Test fun searchNarrowsTheListAndSaysWhenNothingMatches() {
        insert("c1") { copy(roaster = "Northwind", coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        insert("c2") { copy(roaster = "Larkmoor", coffeeName = "Copper Kettle", extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Copper Kettle")
        compose.onNode(hasSetTextAction()).performTextInput("Harbor")
        waitGone("Copper Kettle")
        compose.onNodeWithText("Harbor Fog").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        waitForText(str(R.string.history_no_matches))
        compose.onNodeWithText(str(R.string.history_empty_title)).assertDoesNotExist()
    }

    @Test fun tappingASortChipSortsByItAndTappingAgainFlipsTheDirection() {
        insert("lo", CoffeeState.RATED, at = 2_000) { copy(coffeeName = "Low Tide", overall = 2f, extractionSource = ExtractionSource.OCR) }
        insert("hi", CoffeeState.RATED, at = 1_000) { copy(coffeeName = "High Noon", overall = 5f, extractionSource = ExtractionSource.OCR) }
        val vm = vm()
        show(vm)
        waitForText("High Noon")
        fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
        assertTrue("newest first by default", top("Low Tide") < top("High Noon"))
        compose.onNodeWithContentDescription("${str(R.string.sort_recent)}, ${str(R.string.sort_descending)}").assertExists()

        // A score (or price) sort names its direction by value, not by date.
        val overall = str(R.string.sort_overall)
        compose.onNodeWithContentDescription(overall).performClick()
        waitFor { top("High Noon") < top("Low Tide") }
        compose.onNodeWithContentDescription("$overall, ${str(R.string.sort_value_descending)}").assertExists()

        compose.onNodeWithContentDescription("$overall, ${str(R.string.sort_value_descending)}").performClick()
        waitFor { top("Low Tide") < top("High Noon") }
        compose.onNodeWithContentDescription("$overall, ${str(R.string.sort_value_ascending)}").assertExists()
        assertEquals(HistorySort.OVERALL, vm.sort.value)
        assertFalse(vm.descending.value)
    }

    // Filters

    @Test fun onAnEmptyBookEveryPickerSaysNoneYet() {
        show(vm())
        openFilters()
        compose.onAllNodesWithText(str(R.string.filter_none_yet)).assertCountEquals(4)
        compose.onNodeWithText(str(R.string.filter_clear)).assertDoesNotExist()
    }

    @Test fun theUnratedFilterNarrowsTheListAndTheHeaderNamesIt() {
        insert("a") { copy(coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        insert("b", CoffeeState.RATED) { copy(coffeeName = "Copper Kettle", overall = 4f, extractionSource = ExtractionSource.OCR) }
        val vm = vm()
        show(vm)
        waitForText("Copper Kettle")
        openFilters()
        compose.onNodeWithText(str(R.string.filter_unrated)).performClick()
        waitGone("Copper Kettle")
        compose.onNodeWithText(str(R.string.filter_showing, str(R.string.filter_unrated))).assertExists()
        assertTrue(vm.filter.value.unratedOnly)

        // Clear, inside the sheet, lifts it again.
        compose.onNodeWithText(str(R.string.filter_clear)).performScrollTo().performClick()
        waitForText("Copper Kettle")
        assertFalse(vm.filter.value.isActive)
    }

    // A tall window, so every row is composed and a row missing after filtering was filtered out.
    @Config(qualifiers = "w400dp-h1600dp")
    @Test fun buyAgainAndADateRangeShowInTheHeaderAndClearFromIt() {
        val now = System.currentTimeMillis()
        insert("a", CoffeeState.RATED, at = now) {
            copy(coffeeName = "Harbor Fog", overall = 4f, buyAgain = true, extractionSource = ExtractionSource.OCR)
        }
        // Each of these fails exactly one of the two filters.
        insert("old", CoffeeState.RATED, at = now - 400L * 24 * 60 * 60 * 1000) {
            copy(coffeeName = "Copper Kettle", overall = 4f, buyAgain = true, extractionSource = ExtractionSource.OCR)
        }
        insert("meh", CoffeeState.RATED, at = now) {
            copy(coffeeName = "Salt Flats", overall = 2f, buyAgain = false, extractionSource = ExtractionSource.OCR)
        }
        val vm = vm()
        show(vm)
        listOf("Harbor Fog", "Copper Kettle", "Salt Flats").forEach { waitForText(it) }
        openFilters()
        compose.onNodeWithText(str(R.string.filter_buy_again)).performClick()
        compose.onNodeWithText(str(R.string.filter_date_30)).performClick()
        waitForText(str(R.string.filter_showing, str(R.string.filter_buy_again) + " · " + str(R.string.filter_date_30)))
        assertTrue(vm.filter.value.buyAgainOnly)
        assertEquals(DateRange.DAYS_30, vm.filter.value.dateRange)
        waitGone("Copper Kettle")
        waitGone("Salt Flats")
        compose.onNodeWithText("Harbor Fog").assertExists()

        compose.onNodeWithText(str(R.string.filter_clear_short)).performClick()
        waitFor { !vm.filter.value.isActive }
        compose.onNodeWithText(coffees(3)).assertExists()
    }

    /** Every row of the filter sheet reads in one style, and each date choice is a chip, spaced alike. */
    @Config(qualifiers = "w400dp-h1600dp")
    @Test fun theFilterSheetsRowsShareOneStyleAndEveryDateChoiceIsAChip() {
        insert("a") { copy(roaster = "Northwind", coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Harbor Fog")
        openFilters()
        fun size(text: String): Float {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layout)
            return layout.single().layoutInput.style.fontSize.value
        }
        assertEquals("Date reads like the other rows", size(str(R.string.field_roaster)), size(str(R.string.filter_logged)))
        assertEquals(size(str(R.string.filter_unrated)), size(str(R.string.filter_logged)))
        val chips = DateRange.entries.map { str(it.label()) }
        // Each choice, "Any time" included, is a selectable chip (one of several: a radio button).
        for (label in chips) {
            compose.onNode(hasText(label) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.RadioButton)).assertExists()
        }
        compose.onNode(hasText(str(R.string.filter_date_any)) and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).assertIsSelected()
        // The chips sit the same distance apart.
        val bounds = chips.map { label ->
            compose.onNode(hasText(label) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.RadioButton)).fetchSemanticsNode().boundsInRoot
        }
        // A chip narrower than its 48dp touch target sits centered in a 48dp slot; the gaps are between slots.
        val target = 48 * context.resources.displayMetrics.density
        fun slot(r: androidx.compose.ui.geometry.Rect): Pair<Float, Float> {
            val pad = maxOf(0f, (target - r.width) / 2)
            return (r.left - pad) to (r.right + pad)
        }
        val gaps = bounds.zipWithNext().filter { (a, b) -> a.top == b.top }.map { (a, b) -> slot(b).first - slot(a).second }
        assertTrue("gaps $gaps bounds $bounds", gaps.isNotEmpty() && gaps.all { kotlin.math.abs(it - gaps.first()) <= 1f })
    }

    @Test fun theRoasterPickerListsTheBooksRoastersAndAnyLiftsIt() {
        insert("a") { copy(roaster = "Northwind", coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        insert("b") { copy(roaster = "Sable & Rye", coffeeName = "Copper Kettle", extractionSource = ExtractionSource.OCR) }
        val vm = vm()
        show(vm)
        waitForText("Copper Kettle")
        openFilters()
        compose.onNodeWithText(str(R.string.field_roaster)).performClick()
        lastWithText("Sable & Rye").performClick()
        waitFor { vm.filter.value.roaster == "Sable & Rye" }
        waitGone("Harbor Fog")
        compose.onNodeWithText(str(R.string.filter_showing, "Sable & Rye")).assertExists()

        compose.onNodeWithText(str(R.string.field_roaster)).performClick()
        lastWithText(str(R.string.filter_any)).performClick()
        waitFor { vm.filter.value.roaster == null }
        waitForText("Harbor Fog")
    }

    @Config(qualifiers = "w400dp-h1600dp")
    @Test fun originProcessAndSourcePickersFilterByWhatTheBookHolds() {
        insert("a") { copy(roaster = "Larkmoor", coffeeName = "Harbor Fog", originCountry = "Kenya", process = "Washed", extractionSource = ExtractionSource.OCR) }
        // Each of these fails exactly one of the three filters: origin, process, source.
        insert("b") { copy(roaster = "Larkmoor", coffeeName = "Copper Kettle", originCountry = "Brazil", process = "Washed", extractionSource = ExtractionSource.OCR) }
        insert("c") { copy(roaster = "Larkmoor", coffeeName = "Salt Flats", originCountry = "Kenya", process = "Natural", extractionSource = ExtractionSource.OCR) }
        insert("d") { copy(roaster = "Larkmoor", coffeeName = "Night Owl", originCountry = "Kenya", process = "Washed", extractionSource = ExtractionSource.OCR) }
        runBlocking { listOf("a", "b", "c").forEach { repo.setSource(it, "Market stall") } }
        val vm = vm()
        show(vm)
        listOf("Harbor Fog", "Copper Kettle", "Salt Flats", "Night Owl").forEach { waitForText(it) }
        openFilters()

        fun pick(label: Int, value: String) {
            compose.onNodeWithText(str(label)).performClick()
            lastWithText(value).performClick()
            compose.waitForIdle()
        }
        pick(R.string.field_origin_country, "Kenya")
        pick(R.string.field_process, "Washed")
        pick(R.string.filter_source, "Market stall")
        waitFor { vm.filter.value.sourceId != null }
        assertEquals("Kenya", vm.filter.value.originCountry)
        assertEquals("Washed", vm.filter.value.process)
        waitGone("Copper Kettle")
        waitGone("Salt Flats")
        waitGone("Night Owl")
        compose.onNodeWithText("Harbor Fog").assertExists()
        waitForText(str(R.string.filter_showing, "Market stall · Kenya · Washed"))
    }

    // The rating sheet from a row

    @Test fun theRateButtonOpensTheSheetAndSavingRatesTheCoffee() {
        insert("c1") { copy(coffeeName = "Harbor Fog", aroma = 4, flavor = 5, finish = 3, extractionSource = ExtractionSource.OCR) }
        val vm = vm()
        show(vm)
        waitForText("Harbor Fog")
        compose.onNodeWithText(str(R.string.row_unrated_cta)).performClick()
        waitForText(str(R.string.rate_title))
        compose.onNodeWithText(str(R.string.rate_save)).performClick()
        waitFor { row("c1")?.state == CoffeeState.RATED }
        assertEquals(4f, row("c1")!!.overall!!, 0.001f)
        // The sheet closes on its own after the reaction.
        compose.mainClock.advanceTimeBy(2_000)
        waitFor { vm.ratingTarget.value == null }
    }

    @Test fun cancelClosesTheSheetWithoutRating() {
        insert("c1") { copy(coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        val vm = vm()
        show(vm)
        waitForText("Harbor Fog")
        compose.onNodeWithText(str(R.string.row_unrated_cta)).performClick()
        waitForText(str(R.string.rate_title))
        compose.onNodeWithText(str(R.string.detail_cancel)).performClick()
        waitGone(str(R.string.rate_title))
        assertEquals(CoffeeState.LOGGED, row("c1")!!.state)
    }

    @Test fun addDetailsWithAnIncompleteRatingHandsTheDraftToTheEditorWithoutSaving() {
        insert("c1") { copy(coffeeName = "Harbor Fog", aroma = 4, extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Harbor Fog")
        compose.onNodeWithText(str(R.string.row_unrated_cta)).performClick()
        waitForText(str(R.string.rate_more))
        compose.onNodeWithText(str(R.string.rate_more)).performClick()
        waitFor { ratingEditorOpened == listOf("c1") }
        assertEquals("c1", pending.ratingDraft.value?.coffeeId)
        assertEquals(4, pending.ratingDraft.value?.draft?.aroma)
        assertEquals(CoffeeState.LOGGED, row("c1")!!.state)
        waitGone(str(R.string.rate_title))
    }

    @Test fun addDetailsWithACompleteRatingSavesItFirst() {
        insert("c1") { copy(coffeeName = "Harbor Fog", aroma = 4, flavor = 4, finish = 4, extractionSource = ExtractionSource.OCR) }
        show(vm())
        waitForText("Harbor Fog")
        compose.onNodeWithText(str(R.string.row_unrated_cta)).performClick()
        waitForText(str(R.string.rate_more))
        compose.onNodeWithText(str(R.string.rate_more)).performClick()
        waitFor { row("c1")?.state == CoffeeState.RATED }
        assertEquals(listOf("c1"), ratingEditorOpened)
        assertNotNull(pending.ratingDraft.value)
    }

    // "Have you tried it yet?"

    private fun showTriedSheet(): HistoryViewModel {
        insert("c1") { copy(coffeeName = "Harbor Fog", extractionSource = ExtractionSource.OCR) }
        val vm = vm(triedSheetFor = "c1")
        show(vm)
        waitForText(str(R.string.sheet_tried_title))
        return vm
    }

    @Test fun rateNowClosesTheQuestionAndOpensTheRatingSheet() {
        val vm = showTriedSheet()
        compose.onNodeWithText(str(R.string.sheet_rate_now)).performClick()
        waitForText(str(R.string.rate_title))
        compose.onNodeWithText(str(R.string.sheet_tried_title)).assertDoesNotExist()
        assertNull(vm.triedSheetFor.value)
        assertNull(row("c1")!!.remindAt)
    }

    @Test fun aFirstReminderExplainsNotificationsThenAsksAndClosesOnTheAnswer() {
        val vm = showTriedSheet()
        val before = System.currentTimeMillis()
        compose.onNodeWithText(str(R.string.sheet_remind_tomorrow)).performClick()
        waitForText(str(R.string.notif_why_reminder))
        // The reminder is stored at the tap, before Android asks.
        waitFor { row("c1")?.remindAt != null }
        val day = 24L * 60 * 60 * 1000
        assertTrue(row("c1")!!.remindAt!! in before + day..System.currentTimeMillis() + day)

        compose.onNodeWithText(str(R.string.permission_continue)).performClick()
        waitGone(str(R.string.notif_why_reminder))
        assertEquals(listOf<Any?>(Manifest.permission.POST_NOTIFICATIONS), launched)
        assertTrue(PermissionAsks.asked(context, Manifest.permission.POST_NOTIFICATIONS))
        assertNull(vm.triedSheetFor.value)
    }

    /**
     * The explanation is a Material dialog: a title, the reason, then Not now and Continue together at
     * the bottom end, Continue last. Not now keeps the reminder and asks nothing.
     */
    @Test fun theReminderExplanationIsADialogWithItsActionsAtTheBottomEnd() {
        val vm = showTriedSheet()
        compose.onNodeWithText(str(R.string.sheet_remind_tomorrow)).performClick()
        waitForText(str(R.string.notif_reason_reminder_title))
        compose.onNode(androidx.compose.ui.test.isDialog()).assertExists()
        compose.onNodeWithText(str(R.string.sheet_tried_title)).assertDoesNotExist()
        fun box(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val title = box(str(R.string.notif_reason_reminder_title))
        val reason = box(str(R.string.notif_why_reminder))
        val notNow = box(str(R.string.permission_not_now))
        val go = box(str(R.string.permission_continue))
        assertTrue("reason under the title", reason.top >= title.bottom)
        assertTrue("actions under the reason", notNow.top >= reason.bottom && go.top >= reason.bottom)
        assertTrue("Not now beside Continue, Continue at the end", notNow.right <= go.left && kotlin.math.abs(notNow.top - go.top) < 2f)
        assertTrue("actions at the end", go.right >= reason.right - 1f)
        compose.onNodeWithText(str(R.string.permission_not_now)).performClick()
        waitGone(str(R.string.notif_why_reminder))
        assertTrue(launched.isEmpty())
        assertTrue(row("c1")!!.remindAt != null)
        assertNull(vm.triedSheetFor.value)
    }

    @Test fun withNotificationsAllowedNextWeekJustSetsTheReminder() {
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val vm = showTriedSheet()
        val before = System.currentTimeMillis()
        compose.onNodeWithText(str(R.string.sheet_remind_next_week)).performClick()
        waitGone(str(R.string.sheet_tried_title))
        waitFor { row("c1")?.remindAt != null }
        assertTrue(row("c1")!!.remindAt!! >= before + 7L * 24 * 60 * 60 * 1000)
        compose.onNodeWithText(str(R.string.notif_why_reminder)).assertDoesNotExist()
        assertTrue(launched.isEmpty())
        assertNull(vm.triedSheetFor.value)
    }

    @Test fun illLetYouKnowAndNoReminderStoreNoReminder() {
        val vm = showTriedSheet()
        runBlocking { repo.setReminder("c1", 42L) }
        compose.onNodeWithText(str(R.string.sheet_ill_let_you_know)).performClick()
        waitGone(str(R.string.sheet_tried_title))
        waitFor { row("c1")?.remindAt == null }
        assertNull(vm.triedSheetFor.value)
        assertTrue(launched.isEmpty())
    }

    @Test fun noReminderClosesTheQuestion() {
        val vm = showTriedSheet()
        compose.onNodeWithText(str(R.string.sheet_no_reminder)).performClick()
        waitGone(str(R.string.sheet_tried_title))
        assertNull(vm.triedSheetFor.value)
        assertNull(row("c1")!!.remindAt)
    }
}
