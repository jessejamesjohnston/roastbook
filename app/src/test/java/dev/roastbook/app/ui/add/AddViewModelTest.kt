package dev.roastbook.app.ui.add

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.testutil.TestGraph
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Staging photos from the camera and the library, labeling, removal with undo, and saving a coffee. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddViewModelTest {
    private val graph = TestGraph()
    private val saved = SavedStateHandle()
    private val vm = newVm(saved)

    @After fun tearDown() = graph.close()

    private fun newVm(handle: SavedStateHandle) = AddViewModel(graph.context, graph.repo, graph.photos, handle)

    private fun roles(v: AddViewModel = vm) = v.photos.value.map { it.role }

    private fun awaitImports(v: AddViewModel = vm) = awaitOnMain(what = "library import") { v.importing.value == 0 }

    /** A picked photo whose copy blocks until the test writes its bytes and closes the feed. */
    private fun slowLibraryPhoto(): Pair<Uri, PipedOutputStream> {
        val uri = Uri.parse("content://picker.test/slow/${System.nanoTime()}")
        val feed = PipedOutputStream()
        shadowOf(graph.context.contentResolver).registerInputStream(uri, PipedInputStream(feed))
        return uri to feed
    }

    /** The photo library can't be written (a full or broken disk): a plain file sits where its folder goes. */
    private fun breakPhotoStorage() {
        graph.photos.root.deleteRecursively()
        graph.photos.root.writeText("")
    }

    @Test fun capturedPhotosAreLabeledFrontThenBackThenOther() {
        repeat(3) { vm.addCaptured(graph.stagedJpeg()) }
        assertEquals(listOf(PhotoRole.FRONT, PhotoRole.BACK, PhotoRole.OTHER), roles())
    }

    @Test fun aCaptureThatWroteNoFileAddsNothing() {
        vm.addCaptured(File(graph.context.cacheDir, "never-written.jpg"))
        assertTrue(vm.photos.value.isEmpty())
    }

    @Test fun aNewCaptureFileIsAFreshNameInTheStagingFolder() {
        val f = vm.newCaptureFile()
        assertEquals(graph.photos.staging, f.parentFile)
        assertFalse(f.exists())
    }

    @Test fun libraryPhotosAreCopiedIntoStagingInPickOrder() {
        // Different sizes, so each staged copy can be told apart and matched to the photo it came from.
        val a = graph.stagedJpeg(size = 8)
        val b = graph.stagedJpeg(size = 16)
        vm.addFromLibrary(listOf(Uri.fromFile(a), Uri.fromFile(b)))
        awaitImports()
        assertEquals(listOf(PhotoRole.FRONT, PhotoRole.BACK), roles())
        vm.photos.value.forEach { assertEquals(graph.photos.staging, it.file.parentFile) }
        assertEquals("the first pick is the front, the second the back",
            listOf(a.readBytes().toList(), b.readBytes().toList()), vm.photos.value.map { it.file.readBytes().toList() })
        assertFalse(vm.importFailed.value)
    }

    @Test fun anUnreadableLibraryPhotoIsSkippedAndReportedWhileTheOthersComeIn() {
        val good = graph.stagedJpeg()
        vm.addFromLibrary(listOf(Uri.fromFile(File(graph.context.cacheDir, "gone.jpg")), Uri.fromFile(good)))
        awaitImports()
        assertTrue(vm.importFailed.value)
        assertEquals(listOf(PhotoRole.FRONT), roles())
        // The next pick starts with a clean slate.
        vm.addFromLibrary(listOf(Uri.fromFile(graph.stagedJpeg())))
        assertFalse(vm.importFailed.value)
        awaitImports()
        assertEquals(listOf(PhotoRole.FRONT, PhotoRole.BACK), roles())
    }

    @Test fun anEmptyPickChangesNothing() {
        vm.addFromLibrary(emptyList())
        assertEquals(0, vm.importing.value)
        assertTrue(vm.photos.value.isEmpty())
    }

    @Test fun aRelabeledPhotoKeepsItsPlace() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        vm.setRole(1, PhotoRole.SELLER)
        assertEquals(listOf(PhotoRole.FRONT, PhotoRole.SELLER), roles())
        // The title asks for the back again, so the next capture is labeled Back.
        vm.addCaptured(graph.stagedJpeg())
        assertEquals(listOf(PhotoRole.FRONT, PhotoRole.SELLER, PhotoRole.BACK), roles())
    }

    @Test fun aRemovedPhotoCanBeUndoneAndItsFileIsKeptMeanwhile() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        val front = vm.photos.value[0]
        vm.remove(0)
        assertEquals(listOf(PhotoRole.BACK), roles())
        assertEquals(listOf(RemovedPhoto(0, front)), vm.removed.value)
        assertTrue(front.file.exists())
        vm.undoRemove()
        assertEquals(listOf(front.file), vm.photos.value.take(1).map { it.file })
        assertTrue(vm.removed.value.isEmpty())
    }

    @Test fun removingAnIndexThatIsNotThereChangesNothing() {
        vm.addCaptured(graph.stagedJpeg())
        vm.remove(4)
        assertEquals(1, vm.photos.value.size)
        assertTrue(vm.removed.value.isEmpty())
    }

    @Test fun anyOtherChangeEndsTheUndoWindowAndDeletesTheRemovedFile() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        val front = vm.photos.value[0].file
        vm.remove(0)
        vm.setRole(0, PhotoRole.FRONT)
        assertFalse(front.exists())
        assertTrue(vm.removed.value.isEmpty())
        vm.undoRemove() // nothing left to undo
        assertEquals(1, vm.photos.value.size)
    }

    @Test fun theUndoOfferTimingOutDeletesTheFileButAStaleTimerDoesNot() {
        repeat(3) { vm.addCaptured(graph.stagedJpeg()) }
        val first = vm.photos.value[0].file
        vm.remove(0)
        val stale = vm.removed.value
        vm.remove(0) // a second removal extends the window
        vm.undoExpired(stale)
        assertTrue(first.exists())
        assertEquals(2, vm.removed.value.size)
        vm.undoExpired(vm.removed.value)
        assertFalse(first.exists())
        assertTrue(vm.removed.value.isEmpty())
    }

    @Test fun stagedPhotosComeBackAfterTheProcessIsKilled() {
        repeat(3) { vm.addCaptured(graph.stagedJpeg()) }
        vm.setRole(2, PhotoRole.PAMPHLET)
        val expected = vm.photos.value
        val restored = newVm(saved)
        assertEquals(expected, restored.photos.value)
    }

    @Test fun aRestoredListDropsMissingFilesAndReadsAnUnknownLabelAsOther() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        val (front, back) = vm.photos.value
        front.file.delete()
        // The saved-state format: parallel lists of paths and role names (a later version may add roles).
        saved["staged_roles"] = arrayListOf("FRONT", "TRAY")
        val restored = newVm(saved)
        assertEquals(listOf(StagedPhoto(back.file, PhotoRole.OTHER)), restored.photos.value)
    }

    @Test fun nothingSavedRestoresAnEmptyList() {
        assertTrue(newVm(SavedStateHandle()).photos.value.isEmpty())
    }

    @Test fun doneCreatesTheCoffeeWithItsPhotosInOrderAndClearsTheStaging() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        vm.setRole(1, PhotoRole.BOX)
        val staged = vm.photos.value.map { it.file }
        var created: String? = null
        vm.save { created = it }
        awaitOnMain(what = "save") { created != null }
        val id = created!!
        val row = runBlocking { graph.dao.get(id) }
        assertNotNull(row)
        assertEquals("the new coffee is queued for its read", CoffeeState.EXTRACTING, row!!.state)
        assertEquals(listOf(PhotoRole.FRONT, PhotoRole.BOX), runBlocking { graph.dao.photos(id) }.sortedBy { it.position }.map { it.role })
        assertTrue(vm.photos.value.isEmpty())
        staged.forEach { assertFalse(it.exists()) }
        assertFalse(vm.saving.value)
        assertFalse(vm.saveFailed.value)
    }

    @Test fun aFailedSaveKeepsTheStagedPhotosForTheRetryAndLeavesNoRow() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        breakPhotoStorage()
        val before = vm.photos.value
        var created: String? = null
        vm.save { created = it }
        awaitOnMain(what = "save to fail") { !vm.saving.value }
        assertTrue(vm.saveFailed.value)
        assertEquals(null, created)
        assertEquals(before, vm.photos.value)
        before.forEach { assertTrue(it.file.exists()) }
        assertTrue(runBlocking { graph.dao.allCoffees() }.isEmpty())
    }

    @Test fun doneDoesNothingWithNoPhotosOrWhileALibraryCopyRuns() {
        vm.save { error("nothing to save") }
        assertFalse(vm.saving.value)
        vm.addCaptured(graph.stagedJpeg())
        val (uri, feed) = slowLibraryPhoto()
        vm.addFromLibrary(listOf(uri))
        assertEquals(1, vm.importing.value)
        vm.save { error("the copy is still running") }
        assertFalse(vm.saving.value)
        feed.write(graph.stagedJpeg().readBytes())
        feed.close()
        awaitImports()
        assertEquals(2, vm.photos.value.size)
    }

    @Test fun doneWaitsForAShotTheCameraIsStillWriting() {
        vm.addCaptured(graph.stagedJpeg())
        val shot = vm.newCaptureFile() // the shutter was pressed; the camera is writing this file
        assertEquals(1, vm.capturing.value)
        var created: String? = null
        vm.save { created = it }
        assertFalse("Done waits for the shot", vm.saving.value)
        graph.stagedJpeg().copyTo(shot)
        vm.addCaptured(shot) // the camera finished
        assertEquals(0, vm.capturing.value)
        assertEquals(2, vm.photos.value.size)
        vm.save { created = it }
        awaitOnMain(what = "save") { created != null }
        assertEquals("both photos are in the coffee", 2, runBlocking { graph.dao.photos(created!!) }.size)
    }

    @Test fun aShotTheCameraCouldNotTakeDeletesWhatItWroteAndSaysSo() {
        val shot = vm.newCaptureFile()
        assertEquals(1, vm.capturing.value)
        shot.writeBytes(byteArrayOf(1, 2, 3)) // a partial file from the failed shot
        vm.captureFailed(shot)
        assertFalse(shot.exists())
        assertTrue(vm.captureFailed.value)
        assertEquals("Done no longer waits for it", 0, vm.capturing.value)
        assertTrue(vm.photos.value.isEmpty())
        vm.newCaptureFile() // the next press starts clean
        assertFalse(vm.captureFailed.value)
    }

    @Test fun aShotThatFinishesAfterLeavingIsDeleted() {
        val shot = vm.newCaptureFile()
        vm.discard() // Cancel while the camera is still writing
        graph.stagedJpeg().copyTo(shot)
        vm.addCaptured(shot)
        assertFalse(shot.exists())
        assertTrue(vm.photos.value.isEmpty())
    }

    @Test fun whileSavingTheListIsFrozenAndANewCaptureIsDiscarded() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        val before = vm.photos.value
        var created = 0
        vm.save { created++ }
        assertTrue(vm.saving.value)
        val late = graph.stagedJpeg()
        vm.addCaptured(late)
        assertFalse(late.exists())
        vm.setRole(0, PhotoRole.OTHER)
        vm.remove(0)
        vm.undoRemove()
        vm.addFromLibrary(listOf(Uri.fromFile(graph.stagedJpeg())))
        vm.save { created++ }
        assertEquals(before, vm.photos.value)
        assertTrue(vm.removed.value.isEmpty())
        assertEquals(0, vm.importing.value)
        awaitOnMain(what = "save") { created > 0 }
        assertEquals(1, created)
    }

    @Test fun leavingDeletesEveryStagedFile() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        val files = vm.photos.value.map { it.file }
        vm.remove(1)
        vm.discard()
        assertTrue(vm.photos.value.isEmpty())
        files.forEach { assertFalse(it.exists()) }
        assertTrue(newVm(saved).photos.value.isEmpty())
    }

    /** A ViewModel owned by [store], so clearing the store ends it as leaving the screen would. */
    private fun ownedVm(store: ViewModelStore): AddViewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(newVm(SavedStateHandle()))!!
    })[AddViewModel::class.java]

    @Test fun leavingWhileSavingStillStoresTheCoffeeAndIsNotAFailure() {
        val store = ViewModelStore()
        val owned = ownedVm(store)
        repeat(2) { owned.addCaptured(graph.stagedJpeg()) }
        owned.save { }
        store.clear()
        awaitOnMain(what = "the coffee to be stored") {
            runBlocking { graph.dao.allCoffees() }.any { it.state == CoffeeState.EXTRACTING }
        }
        val id = runBlocking { graph.dao.allIds() }.single()
        assertEquals(2, runBlocking { graph.dao.photos(id) }.size)
        awaitOnMain(what = "the save to end") { !owned.saving.value }
        assertFalse(owned.saveFailed.value)
    }

    @Test fun leavingWhileALibraryCopyRunsIsNotReportedAsAFailedPhoto() {
        val store = ViewModelStore()
        val owned = ownedVm(store)
        val (uri, feed) = slowLibraryPhoto()
        owned.addFromLibrary(listOf(uri))
        assertEquals(1, owned.importing.value)
        store.clear()
        feed.write(graph.stagedJpeg().readBytes())
        feed.close()
        awaitOnMain(what = "the copy to end") { owned.importing.value == 0 }
        assertFalse(owned.importFailed.value)
        assertTrue(owned.photos.value.isEmpty())
        assertTrue("the finished copy nobody will save is not left in staging", graph.photos.staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun aRemovedPhotoIsDeletedWhenTheScreenGoesAway() {
        val store = ViewModelStore()
        val owned = ownedVm(store)
        owned.addCaptured(graph.stagedJpeg())
        val file = owned.photos.value[0].file
        owned.remove(0)
        assertTrue(file.exists())
        store.clear()
        assertFalse(file.exists())
    }
}
