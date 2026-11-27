package dev.roastbook.app.ui.nav

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.roastbook.app.HiltTestActivity
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.ui.detail.EditSession
import dev.roastbook.app.ui.detail.EditSessions
import dev.roastbook.app.ui.theme.RoastbookTheme
import java.io.File
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app's navigation: where it starts, the notification and keyboard hand-offs, and the
 * phone page versus the list-and-pane layout, including a coffee moving between them when the
 * window changes. The host sits in a box of a chosen size inside a large window, so a test can
 * switch between one and two panes by resizing the box.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w1200dp-h900dp")
class NavHostTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    // Effects run as soon as they are launched (the v1 rule's behavior). These screens collect Room and
    // Paging flows that emit from other threads; under the v2 rule's queued default the polling
    // in waitFor never sees their results.
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>(effectContext = UnconfinedTestDispatcher())

    @Inject lateinit var pending: PendingActions
    @Inject lateinit var dao: CoffeeDao

    private val context get() = compose.activity
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 1)
    private var size by mutableStateOf(PHONE)

    /** Answers the photo picker with one staged JPEG and any permission request with "granted". */
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val result: Any = when (contract) {
                is ActivityResultContracts.PickMultipleVisualMedia -> listOf(Uri.fromFile(jpeg()))
                else -> true
            }
            dispatchResult(requestCode, result)
        }
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        hilt.inject()
    }

    private fun jpeg(): File = File(context.cacheDir, "picked.jpg").apply {
        outputStream().use { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun insert(id: String, name: String, build: CoffeeEntity.() -> CoffeeEntity = { this }) = runBlocking {
        dao.insert(CoffeeEntity(id, 1_000, 1_000, 1_000, CoffeeState.LOGGED, roaster = "Northwind", coffeeName = name, extractionSource = ExtractionSource.OCR).build())
    }

    private fun host(showOnboarding: Boolean = false, window: DpSize = PHONE, keyboard: Boolean = true) {
        size = window
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                RoastbookTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(size)) {
                        if (keyboard) RoastbookNavHost(pending, showOnboarding, shortcuts) else RoastbookNavHost(pending, showOnboarding)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Polls in real time (Room works on its own threads) while moving the main looper's clock on. */
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

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size
    private fun countDescribed(text: String) = compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().size
    private fun waitForText(text: String) = waitFor { count(text) > 0 }
    private fun waitGone(text: String) = waitFor { count(text) == 0 }

    private fun onHistory() = countDescribed(str(R.string.fab_add_coffee)) > 0
    private fun onDetailPage() = countDescribed(str(R.string.detail_back)) > 0 && count(str(R.string.settings_title)) == 0
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun roasterField() = compose.onNode(hasSetTextAction() and hasText(str(R.string.field_roaster)))
    private fun editSessions() = ViewModelProvider(compose.activity)[EditSessions::class.java]

    // Start destination

    @Test fun theFirstStartShowsTheIntroAndHistoryReplacesIt() {
        host(showOnboarding = true)
        waitForText(str(R.string.onboarding_1_title))
        compose.onNodeWithText(str(R.string.onboarding_skip)).performClick()
        waitFor { onHistory() }
        compose.onNodeWithText(str(R.string.history_empty_title)).assertExists()
        compose.onNodeWithText(str(R.string.onboarding_1_title)).assertDoesNotExist()
    }

    @Test fun laterStartsOpenOnHistoryEvenWithNoKeyboardShortcuts() {
        host(keyboard = false)
        waitFor { onHistory() }
        compose.onNodeWithText(str(R.string.onboarding_skip)).assertDoesNotExist()
    }

    // One pane

    @Test fun onAPhoneACoffeeOpensAsItsOwnPageAndBackReturns() {
        insert("c1", "Harbor Fog")
        host()
        waitForText("Harbor Fog")
        compose.onNodeWithText("Harbor Fog").performClick()
        waitFor { onDetailPage() }
        compose.onNodeWithText(str(R.string.pane_pick_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        waitFor { onHistory() }
    }

    @Test fun settingsLeadsToTheLicensesAndEachBackButtonReturnsOneStep() {
        host()
        waitFor { onHistory() }
        compose.onNodeWithContentDescription(str(R.string.history_settings)).performClick()
        waitForText(str(R.string.settings_title))
        compose.onNodeWithText(str(R.string.settings_licenses), useUnmergedTree = true).performScrollTo().performClick()
        waitForText(str(R.string.licenses_intro))
        compose.onNode(hasText(str(R.string.app_name)) and hasText(str(R.string.licenses_own_license))).performClick()
        waitGone(str(R.string.licenses_intro))

        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        waitForText(str(R.string.licenses_intro))
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        waitForText(str(R.string.settings_title))
        // Settings comes back scrolled to where it was; its bar scrolls with the page.
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performScrollTo().performClick()
        waitFor { onHistory() }
    }

    @Test fun aRatingNotificationReturnsToHistoryAndOpensTheSheet() {
        insert("c1", "Harbor Fog")
        host()
        waitForText("Harbor Fog")
        compose.onNodeWithContentDescription(str(R.string.history_settings)).performClick()
        waitForText(str(R.string.settings_title))

        pending.rateCoffee.value = "c1"
        waitForText(str(R.string.rate_title))
        compose.onNodeWithText(str(R.string.settings_title)).assertDoesNotExist()
        assertNull("the request is consumed", pending.rateCoffee.value)
        assertNull(pending.showRatingSheet.value)
    }

    @Test fun addDetailsFromTheSheetOpensTheCoffeesPageOnAPhone() {
        insert("c1", "Harbor Fog")
        host()
        waitForText("Harbor Fog")
        compose.onNodeWithText(str(R.string.row_unrated_cta)).performClick()
        waitForText(str(R.string.rate_more))
        compose.onNodeWithText(str(R.string.rate_more)).performClick()
        waitFor { onDetailPage() }
    }

    @Test fun aCoffeeAddedOnAPhoneReturnsToHistoryAndAsksWhetherItWasTried() {
        host()
        waitFor { onHistory() }
        compose.onNodeWithContentDescription(str(R.string.fab_add_coffee)).performClick()
        waitFor { countDescribed(str(R.string.add_library)) > 0 }
        compose.onNodeWithContentDescription(str(R.string.add_library)).performClick()
        waitFor { compose.onNodeWithContentDescription(str(R.string.add_done)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithContentDescription(str(R.string.add_done)).performClick()
        waitForText(str(R.string.sheet_tried_title))
        waitFor { onHistory() }
        assertEquals(1, runBlocking { dao.count().first() })
    }

    // Keyboard shortcuts

    @Test fun shortcutsOpenAddAndSettingsFromHistory() {
        host()
        waitFor { onHistory() }
        shortcuts.tryEmit(Shortcut.NEW_COFFEE)
        waitFor { countDescribed(str(R.string.add_library)) > 0 }

        // Off History a shortcut does nothing.
        shortcuts.tryEmit(Shortcut.SETTINGS)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.settings_title)).assertDoesNotExist()

        compose.onNodeWithContentDescription(str(R.string.detail_cancel)).performClick()
        waitFor { onHistory() }
        shortcuts.tryEmit(Shortcut.SETTINGS)
        waitForText(str(R.string.settings_title))
    }

    @Test fun shortcutsWaitWhileAnEditIsOpen() {
        host()
        waitFor { onHistory() }
        editSessions().start(EditSession("c1", "facts"))
        shortcuts.tryEmit(Shortcut.NEW_COFFEE)
        compose.waitForIdle()
        assertEquals(0, countDescribed(str(R.string.add_library)))
        assertEquals(true, onHistory())
    }

    // Two panes

    @Test fun onALargeScreenThePaneAsksForACoffeeAndShowsTheOnePicked() {
        insert("c1", "Harbor Fog")
        host(window = WIDE)
        waitForText(str(R.string.pane_pick_title))
        waitForText("Harbor Fog")
        compose.onNodeWithText("Harbor Fog").performClick()
        waitGone(str(R.string.pane_pick_title))
        waitFor { count("Harbor Fog") >= 2 } // the list row and the page beside it
        compose.onNodeWithContentDescription(str(R.string.detail_back)).assertDoesNotExist()

        // Back clears the pane before it would leave the app.
        back()
        waitForText(str(R.string.pane_pick_title))
    }

    @Test fun onALargeScreenWithNoCoffeesThePaneSaysWhereTheyWillShowUp() {
        host(window = WIDE)
        waitForText(str(R.string.pane_empty_title))
        compose.onNodeWithText(str(R.string.pane_pick_title)).assertDoesNotExist()
    }

    @Test fun whenTheOpenCoffeeDisappearsThePaneClosesAndItsEditIsDropped() {
        insert("c1", "Harbor Fog")
        host(window = WIDE)
        waitForText("Harbor Fog")
        compose.onNodeWithText("Harbor Fog").performClick()
        waitGone(str(R.string.pane_pick_title))
        editSessions().start(EditSession("c1", "facts", drafts = mapOf("roaster" to "Larkmoor")))

        runBlocking { dao.delete(dao.get("c1")!!) }
        waitForText(str(R.string.pane_empty_title))
        assertNull(editSessions().session.value)
    }

    @Test fun anEditOnAnotherCoffeeSurvivesTheOpenOneDisappearing() {
        insert("c1", "Harbor Fog")
        insert("c2", "Copper Kettle")
        host(window = WIDE)
        waitForText("Harbor Fog")
        compose.onNodeWithText("Harbor Fog").performClick()
        waitGone(str(R.string.pane_pick_title))
        val other = EditSession("c2", "facts", drafts = mapOf("roaster" to "Larkmoor"))
        editSessions().start(other)

        runBlocking { dao.delete(dao.get("c1")!!) }
        waitForText(str(R.string.pane_pick_title))
        assertEquals(other, editSessions().session.value)
    }

    @Test fun addDetailsFromTheSheetOpensTheCoffeeBesideTheList() {
        insert("c1", "Harbor Fog")
        host(window = WIDE)
        waitForText(str(R.string.pane_pick_title))
        waitForText(str(R.string.row_unrated_cta))
        compose.onNodeWithText(str(R.string.row_unrated_cta)).performClick()
        waitForText(str(R.string.rate_more))
        compose.onNodeWithText(str(R.string.rate_more)).performClick()
        waitGone(str(R.string.pane_pick_title))
        compose.onNodeWithContentDescription(str(R.string.detail_back)).assertDoesNotExist()
    }

    @Test fun addDetailsAgainForTheCoffeeAlreadyBesideTheListOpensTheEditorAgain() {
        insert("c1", "Harbor Fog")
        host(window = WIDE)
        fun addDetails() {
            waitForText(str(R.string.row_unrated_cta))
            compose.onNodeWithText(str(R.string.row_unrated_cta)).performClick()
            waitForText(str(R.string.rate_more))
            compose.onNodeWithText(str(R.string.rate_more)).performClick()
            waitForText(str(R.string.detail_save))
        }
        addDetails()
        // Closed with nothing chosen: the coffee stays unrated and open beside the list.
        compose.onNodeWithText(str(R.string.detail_cancel)).performClick()
        waitGone(str(R.string.detail_save))
        addDetails()
        assertEquals("c1", editSessions().session.value?.coffeeId)
    }

    @Test fun onALargeScreenAddAndSettingsStillNavigate() {
        host(window = WIDE)
        waitFor { onHistory() }
        compose.onNodeWithContentDescription(str(R.string.history_settings)).performClick()
        waitForText(str(R.string.settings_title))
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        waitFor { onHistory() }
        compose.onNodeWithContentDescription(str(R.string.fab_add_coffee)).performClick()
        waitFor { countDescribed(str(R.string.add_library)) > 0 }
    }

    @Test fun aCoffeeAddedOnALargeScreenOpensBesideTheList() {
        host(window = WIDE)
        waitForText(str(R.string.pane_empty_title))
        compose.onNodeWithContentDescription(str(R.string.fab_add_coffee)).performClick()
        waitFor { countDescribed(str(R.string.add_library)) > 0 }
        compose.onNodeWithContentDescription(str(R.string.add_library)).performClick()
        waitFor { compose.onNodeWithContentDescription(str(R.string.add_done)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithContentDescription(str(R.string.add_done)).performClick()
        waitForText(str(R.string.sheet_tried_title))
        waitGone(str(R.string.pane_empty_title))
        compose.onNodeWithText(str(R.string.pane_pick_title)).assertDoesNotExist()
    }

    // The window changing between one and two panes

    @Test fun aCoffeePageMovesIntoTheRightPaneWhenTheWindowWidensAndBackWhenItNarrows() {
        insert("c1", "Harbor Fog")
        host()
        waitForText("Harbor Fog")
        compose.onNodeWithText("Harbor Fog").performClick()
        waitFor { onDetailPage() }

        size = WIDE
        waitFor { onHistory() && count(str(R.string.pane_pick_title)) == 0 }
        waitFor { count("Harbor Fog") >= 2 }
        compose.onNodeWithContentDescription(str(R.string.detail_back)).assertDoesNotExist()

        size = PHONE
        waitFor { onDetailPage() }
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        waitFor { onHistory() }
        // Back on the list, nothing is left selected: widening again shows the tip.
        size = WIDE
        waitForText(str(R.string.pane_pick_title))
    }

    @Test fun anOpenEditMovesWithTheCoffeeBetweenLayoutsWithoutBeingSavedOnTheWay() {
        insert("c1", "Harbor Fog")
        host()
        waitForText("Harbor Fog")
        compose.onNodeWithText("Harbor Fog").performClick()
        waitFor { onDetailPage() }
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, str(R.string.detail_facts))).performScrollTo().performClick()
        roasterField().performTextReplacement("Larkmoor")

        // Widening hands the edit to the dialog over both panes: still open, nothing written.
        size = WIDE
        waitFor { onHistory() && count("Harbor Fog") >= 2 }
        assertEquals("Larkmoor", editSessions().forCoffee("c1")?.drafts?.get("roaster"))
        waitFor { count(str(R.string.detail_save)) > 0 }
        assertEquals("Larkmoor", roasterField().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals("Northwind", runBlocking { dao.get("c1")!!.roaster })

        // Narrowing hands it back to the phone page, still unsaved.
        size = PHONE
        waitFor { onDetailPage() && count(str(R.string.detail_save)) == 0 }
        assertEquals("Larkmoor", editSessions().forCoffee("c1")?.drafts?.get("roaster"))
        assertEquals("Larkmoor", roasterField().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals("Northwind", runBlocking { dao.get("c1")!!.roaster })
    }

    @Test fun widenAndNarrowWithNothingOpenStaysOnTheList() {
        insert("c1", "Harbor Fog")
        host()
        waitForText("Harbor Fog")
        size = WIDE
        waitForText(str(R.string.pane_pick_title))
        size = PHONE
        waitGone(str(R.string.pane_pick_title))
        assertEquals(true, onHistory())
        compose.onNodeWithContentDescription(str(R.string.detail_back)).assertDoesNotExist()
    }

    private companion object {
        val PHONE = DpSize(400.dp, 800.dp)
        val WIDE = DpSize(1100.dp, 800.dp)
    }
}
