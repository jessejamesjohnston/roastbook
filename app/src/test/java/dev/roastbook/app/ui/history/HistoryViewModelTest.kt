package dev.roastbook.app.ui.history

import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.roastbook.app.data.backup.BackupNudge
import dev.roastbook.app.data.backup.BackupOff
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.DateRange
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.ui.rating.RatingDraft
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/** History's state holder: the cross-screen rating request, sort and filter rules, and its guards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HistoryViewModelTest {
    private val g = TestGraph()
    private val pending = PendingActions()
    private val backupPrefs = BackupPrefs(PreferenceDataStoreFactory.create { File(g.context.filesDir, "backup-${UUID.randomUUID()}.preferences_pb") })

    @After fun tearDown() = g.close()

    private fun vm(triedSheetFor: String? = null, saved: SavedStateHandle = SavedStateHandle(mapOf("triedSheetFor" to triedSheetFor))) =
        HistoryViewModel(saved, g.repo, pending, backupPrefs)

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within 15 s" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(5)
        }
    }

    @Test fun aRatingRequestFromANotificationOpensTheSheetOnceAndIsConsumed() {
        runBlocking { g.insertCoffee("c1") { copy(coffeeName = "Harbor Fog") } }
        val vm = vm()
        pending.showRatingSheet.value = "c1"
        eventually { vm.ratingTarget.value != null }
        assertEquals("c1", vm.ratingTarget.value!!.id)
        assertNull("the request is cleared so it doesn't reopen", pending.showRatingSheet.value)
        vm.closeRating()
        assertNull(vm.ratingTarget.value)
    }

    @Test fun anOpenRatingSheetReopensAfterTheProcessIsRecreatedAndAClosedOneDoesNot() {
        runBlocking { g.insertCoffee("c1") { copy(coffeeName = "Harbor Fog") } }
        val saved = SavedStateHandle(mapOf("triedSheetFor" to null))
        val before = vm(saved = saved)
        before.openRating("c1")
        eventually { before.ratingTarget.value != null }

        // A new ViewModel on the same saved state, as after Android stopped the app in the background.
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val after = vm(saved = restored)
        eventually { after.ratingTarget.value != null }
        assertEquals("c1", after.ratingTarget.value!!.id)

        // Closing it (Cancel, Save, Add details) forgets it, so the next start doesn't reopen it.
        after.closeRating()
        assertNull(restored.get<String>("ratingTarget"))
    }

    @Test fun theTriedSheetTargetComesFromTheRouteAndDismissingClearsIt() {
        val vm = vm(triedSheetFor = "c9")
        assertEquals("c9", vm.triedSheetFor.value)
        vm.dismissTried()
        assertNull(vm.triedSheetFor.value)
        assertNull(vm(triedSheetFor = null).triedSheetFor.value)
    }

    @Test fun existsFollowsTheRowAsItComesAndGoes() = runBlocking {
        val vm = vm()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val job = launch(Dispatchers.IO) { vm.exists("c1").take(3).toList(seen) }
        eventually { seen.size == 1 }
        val e = g.insertCoffee("c1")
        eventually { seen.size == 2 }
        g.dao.delete(e)
        eventually { seen.size == 3 }
        job.join()
        assertEquals(listOf(false, true, false), seen)
    }

    @Test fun savingWithNoSheetOpenChangesNothing() {
        runBlocking { g.insertCoffee("c1") }
        val vm = vm()
        vm.saveRating(RatingDraft(4, 4, null, null, 4))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CoffeeState.LOGGED, runBlocking { g.dao.get("c1") }!!.state)
    }

    @Test fun answeringForACoffeeThatIsGoneIsIgnored() {
        val vm = vm()
        vm.answerTried("missing", TriedChoice.TOMORROW)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(runBlocking { g.dao.get("missing") })
    }

    @Test fun aNewSortStartsDescendingAndTheSameSortFlips() {
        val vm = vm()
        vm.tapSort(HistorySort.PRICE)
        assertEquals(HistorySort.PRICE, vm.sort.value)
        assertTrue(vm.descending.value)
        vm.tapSort(HistorySort.PRICE)
        assertFalse(vm.descending.value)
        vm.tapSort(HistorySort.BODY)
        assertEquals(HistorySort.BODY, vm.sort.value)
        assertTrue("a different sort starts over at descending", vm.descending.value)
    }

    @Test fun clearingFiltersKeepsTheSearchText() {
        val vm = vm()
        vm.setSearch("plum")
        vm.toggleUnrated(); vm.toggleBuyAgain(); vm.setSource("s1"); vm.setRoaster("Larkmoor")
        vm.setOrigin("Kenya"); vm.setProcess("Washed"); vm.setDateRange(DateRange.THIS_YEAR)
        assertEquals(HistoryFilter("plum", true, true, "s1", "Larkmoor", "Kenya", "Washed", DateRange.THIS_YEAR), vm.filter.value)
        vm.clearFilters()
        assertEquals(HistoryFilter(search = "plum"), vm.filter.value)
    }

    @Test fun theBackupBannerAppearsOnceTheBookHasACoffee() = runBlocking {
        val vm = vm()
        val job = launch(Dispatchers.IO) { vm.backupNudge.collect {} }
        eventually { vm.count.value == 0 }
        assertNull(vm.backupNudge.value)
        g.insertCoffee("c1")
        eventually { vm.backupNudge.value != null }
        assertEquals(BackupNudge.NoFolder, vm.backupNudge.value)
        job.cancel()
        assertEquals(1, g.repo.count().first())
    }

    @Test fun theBackupBannerSaysBackupsAreOffOnceTheFolderIsForgotten() = runBlocking {
        g.insertCoffee("c1")
        backupPrefs.setFolder("content://backups/tree")
        backupPrefs.recordSuccess(System.currentTimeMillis(), "roastbook.zip", 10, 1)
        val vm = vm()
        val job = launch(Dispatchers.IO) { vm.backupNudge.collect {} }
        backupPrefs.folderUnusable("content://backups/tree", BackupOff.FOLDER_GONE)
        eventually { vm.backupNudge.value == BackupNudge.Off }
        job.cancel()
    }
}
