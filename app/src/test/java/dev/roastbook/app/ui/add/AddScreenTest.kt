package dev.roastbook.app.ui.add

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import dev.roastbook.app.R
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.ui.theme.RoastbookTheme
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The camera screen around the camera itself: asking for the camera, the library, the staged photo
 * row with labels and removal, Done and Cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val graph = TestGraph()
    private val context: Context get() = graph.context
    private val vm = AddViewModel(graph.context, graph.repo, graph.photos, SavedStateHandle())
    private var canceled = 0
    private var created: String? = null

    /** Set by tests that allow the camera, which starts CameraX's process-wide provider. */
    private var cameraStarted = false

    @After fun tearDown() {
        // CameraX keeps initializing on its own threads after a test ends and can collide with the
        // next test's activity; let it finish and shut it down here.
        if (cameraStarted) {
            val provider = ProcessCameraProvider.getInstance(context)
            awaitOnMain(what = "CameraX to start") { provider.isDone }
            val shutdown = provider.get().shutdownAsync()
            awaitOnMain(what = "CameraX to stop") { shutdown.isDone }
        }
        graph.close()
    }

    private fun allowCamera() {
        cameraStarted = true
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
    }

    /**
     * Stands in for Android's side of the camera dialog and the photo picker: records each launch so
     * the test can answer it the way the person would.
     */
    private class AddScreenTestRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<Int, Any?>>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += requestCode to input
        }
        inline fun <reified T> last(): Int? = launches.lastOrNull { it.second is T }?.first
    }

    private val registry = AddScreenTestRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                RoastbookTheme { AddScreen(onCancel = { canceled++ }, onCreated = { created = it }, vm = vm) }
            }
        }
    }

    private fun text(id: Int) = context.getString(id)
    private fun roleLabel(role: PhotoRole) = text(role.label())
    private val asks get() = context.getSharedPreferences("permission_asks", Context.MODE_PRIVATE)
    private val shutter get() = compose.onNodeWithContentDescription(text(R.string.add_take_photo))
    private val done get() = compose.onNodeWithContentDescription(text(R.string.add_done))

    /** Answers the camera dialog Android would show, as the person did. */
    private fun answerCamera(granted: Boolean, rationaleAfter: Boolean = false) {
        assertEquals("the camera dialog was asked for", Manifest.permission.CAMERA, registry.launches.lastOrNull()?.second)
        val code = registry.last<String>()!!
        registry.launches.clear()
        if (granted) allowCamera()
        shadowOf(compose.activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, rationaleAfter)
        compose.runOnUiThread { registry.dispatchResult(code, granted) }
        compose.waitForIdle()
    }

    /** Opens the library and returns the picked photos, as the system photo picker would. */
    private fun pickFromLibrary(vararg uris: Uri) {
        compose.onNodeWithContentDescription(text(R.string.add_library)).performClick()
        val code = registry.last<PickVisualMediaRequest>()
        assertNotNull("the photo picker was opened", code)
        compose.runOnUiThread { registry.dispatchResult(code!!, uris.toList()) }
        compose.waitForIdle()
    }

    private fun stagedCount() = vm.photos.value.size

    /** The ids of the screen's live regions right now. */
    private fun liveRegions(): Set<Int> =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.id }.toSet()

    /**
     * TalkBack announces [line] when it appears: it sits in a live region that was already on screen
     * before it ([before], from [liveRegions]). A live region that appears together with its text
     * isn't announced, so a region on the line itself doesn't count.
     */
    private fun assertAnnouncedOnArrival(line: String, before: Set<Int>) {
        var node: SemanticsNode? = compose.onNodeWithText(line, useUnmergedTree = true).fetchSemanticsNode()
        while (node != null && node.config.getOrNull(SemanticsProperties.LiveRegion) != LiveRegionMode.Polite) node = node.parent
        assertNotNull("\"$line\" is inside a live region", node)
        assertTrue("\"$line\" appeared inside a live region that was already there", node!!.id in before)
    }

    @Test fun theFirstVisitExplainsTheCameraBeforeAskingAndTheShutterWaits() {
        show()
        compose.onNodeWithText(text(R.string.add_camera_why)).assertExists()
        compose.onNodeWithText(text(R.string.add_step_front)).assertExists()
        shutter.assertIsNotEnabled()
        done.assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.permission_continue)).performClick()
        assertTrue(asks.getBoolean(Manifest.permission.CAMERA, false))
        assertEquals(Manifest.permission.CAMERA, registry.launches.single().second)
    }

    /** Not now closes the reason and asks nothing; the reason stays on the screen with Allow camera. */
    @Test fun notNowOnTheCameraReasonAsksNothingAndLeavesAllowCamera() {
        show()
        compose.onNode(androidx.compose.ui.test.isDialog()).assertExists()
        compose.onNodeWithText(text(R.string.camera_reason_title)).assertExists()
        compose.onNodeWithText(text(R.string.permission_not_now)).performClick()
        compose.onNode(androidx.compose.ui.test.isDialog()).assertDoesNotExist()
        assertTrue(registry.launches.isEmpty())
        compose.onNodeWithText(text(R.string.add_camera_why)).assertExists()
        compose.onNodeWithText(text(R.string.add_camera_permission_button)).performClick()
        assertEquals(Manifest.permission.CAMERA, registry.launches.single().second)
    }

    @Test fun refusedOnceOffersAllowCameraAndRefusedAgainOpensSettings() {
        show()
        compose.onNodeWithText(text(R.string.permission_continue)).performClick()
        answerCamera(granted = false, rationaleAfter = true)
        compose.onNodeWithText(text(R.string.add_camera_permission)).assertExists()
        compose.onNodeWithText(text(R.string.add_camera_permission_button)).performClick()
        // A second refusal with no rationale is Android's "don't ask again".
        answerCamera(granted = false, rationaleAfter = false)
        compose.onNodeWithText(text(R.string.add_camera_permission)).assertExists()
        compose.onNodeWithText(text(R.string.permission_open_settings)).performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals(context.packageName, opened.data?.schemeSpecificPart)
        shutter.assertIsNotEnabled()
    }

    /**
     * Robolectric's device has no camera, so binding fails as it does on a device with no back
     * camera. Waits for that, then (when [started]) reports the camera started, as a device with
     * one would; nothing turns that back.
     */
    private fun awaitCamera(started: Boolean) {
        awaitOnMain(what = "the camera to report") { vm.cameraReady.value == false }
        if (started) compose.runOnUiThread { vm.cameraStarted(true) }
        compose.waitForIdle()
    }

    @Test fun allowingTheCameraTurnsTheShutterOnOnceTheCameraStarts() {
        show()
        compose.onNodeWithText(text(R.string.permission_continue)).performClick()
        answerCamera(granted = true)
        compose.onNodeWithText(text(R.string.add_camera_why)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.permission_continue)).assertDoesNotExist()
        awaitCamera(started = true)
        compose.onNodeWithText(text(R.string.add_camera_failed)).assertDoesNotExist()
        shutter.assertIsEnabled()
    }

    @Test fun aCameraThatCannotStartSaysSoAndOnlyTheLibraryWorks() {
        allowCamera()
        show()
        awaitCamera(started = false)
        compose.onNodeWithText(text(R.string.add_camera_failed)).assertExists()
        shutter.assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.add_library)).assertIsEnabled()
    }

    @Test fun talkBackHearsTheCameraFailWhenItDoes() {
        show()
        compose.onNodeWithText(text(R.string.permission_continue)).performClick()
        val before = liveRegions()
        answerCamera(granted = true)
        awaitCamera(started = false)
        assertAnnouncedOnArrival(text(R.string.add_camera_failed), before)
    }

    @Test fun aShotTheCameraCannotTakeSaysSoAndLeavesNoPhotoAndNoFileBehind() {
        allowCamera()
        show()
        // Reported started, but nothing is bound: the shot fails as it would with the camera taken away.
        awaitCamera(started = true)
        val before = liveRegions()
        shutter.assertIsEnabled().performClick()
        awaitOnMain(what = "the shot to fail") { vm.captureFailed.value }
        compose.onNodeWithText(text(R.string.add_capture_failed)).assertExists()
        assertAnnouncedOnArrival(text(R.string.add_capture_failed), before)
        assertEquals(0, vm.capturing.value)
        assertEquals(0, stagedCount())
        assertTrue(graph.photos.staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun libraryPhotosJoinTheRowWithTheLabelsTheTitleAskedFor() {
        show()
        pickFromLibrary(Uri.fromFile(graph.stagedJpeg()), Uri.fromFile(graph.stagedJpeg()))
        awaitOnMain(what = "library import") { stagedCount() == 2 && vm.importing.value == 0 }
        compose.onNodeWithText(text(R.string.add_step_more)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.add_photo_role, roleLabel(PhotoRole.FRONT))).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.add_photo_role, roleLabel(PhotoRole.BACK))).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.add_photo_position, 2, 2)).assertExists()
        done.assertIsEnabled()
    }

    @Test fun anUnreadableLibraryPhotoIsReported() {
        show()
        val before = liveRegions()
        pickFromLibrary(Uri.fromFile(File(context.cacheDir, "missing.jpg")))
        awaitOnMain(what = "library import") { vm.importing.value == 0 }
        compose.onNodeWithText(text(R.string.add_import_failed)).assertExists()
        assertAnnouncedOnArrival(text(R.string.add_import_failed), before)
        done.assertIsNotEnabled()
    }

    @Test fun aFailureShownFromTheStartIsAnnouncedWhenItComesBack() {
        val missing = Uri.fromFile(File(context.cacheDir, "missing.jpg"))
        vm.addFromLibrary(listOf(missing))
        awaitOnMain(what = "library import") { vm.importing.value == 0 }
        show()
        compose.onNodeWithText(text(R.string.add_import_failed)).assertExists()
        val before = liveRegions()
        // A new pick clears the line while it copies; the unreadable photo then brings it back.
        val slow = Uri.parse("content://picker.test/slow/1")
        val feed = PipedOutputStream()
        shadowOf(context.contentResolver).registerInputStream(slow, PipedInputStream(feed))
        pickFromLibrary(slow, missing)
        compose.onNodeWithText(text(R.string.add_import_failed)).assertDoesNotExist()
        feed.write(graph.stagedJpeg().readBytes())
        feed.close()
        awaitOnMain(what = "library import") { vm.importing.value == 0 }
        assertAnnouncedOnArrival(text(R.string.add_import_failed), before)
    }

    @Test fun doneWaitsWhileALibraryCopyIsStillRunning() {
        vm.addCaptured(graph.stagedJpeg())
        show()
        done.assertIsEnabled()
        val uri = Uri.parse("content://picker.test/slow/1")
        val feed = PipedOutputStream()
        shadowOf(context.contentResolver).registerInputStream(uri, PipedInputStream(feed))
        pickFromLibrary(uri)
        done.assertIsNotEnabled()
        feed.write(graph.stagedJpeg().readBytes())
        feed.close()
        awaitOnMain(what = "library import") { vm.importing.value == 0 }
        compose.onNodeWithText(text(R.string.add_step_more)).assertExists()
        done.assertIsEnabled()
    }

    @Test fun theLabelChipChangesAPhotosRole() {
        vm.addCaptured(graph.stagedJpeg())
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.add_photo_role, roleLabel(PhotoRole.FRONT))).performClick()
        compose.onNodeWithText(roleLabel(PhotoRole.SELLER)).performClick()
        assertEquals(listOf(PhotoRole.SELLER), vm.photos.value.map { it.role })
        compose.onNodeWithContentDescription(context.getString(R.string.add_photo_role, roleLabel(PhotoRole.SELLER))).assertExists()
        // The front is still missing, so the title asks for it again.
        compose.onNodeWithText(text(R.string.add_step_front)).assertExists()
    }

    @Test fun theLabelMenuOffersEveryRole() {
        vm.addCaptured(graph.stagedJpeg())
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.add_photo_role, roleLabel(PhotoRole.FRONT))).performClick()
        listOf(PhotoRole.BACK, PhotoRole.PAMPHLET, PhotoRole.BOX, PhotoRole.SELLER, PhotoRole.OTHER).forEach {
            compose.onNodeWithText(roleLabel(it)).assertExists()
        }
        compose.onNodeWithText(roleLabel(PhotoRole.PAMPHLET)).performClick()
        assertEquals(listOf(PhotoRole.PAMPHLET), vm.photos.value.map { it.role })
    }

    @Test fun aRemovedPhotoCanBeUndoneFromTheRow() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        show()
        compose.onNodeWithText(text(R.string.add_step_more)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.remove_photo, roleLabel(PhotoRole.BACK))).performClick()
        assertEquals(listOf(PhotoRole.FRONT), vm.photos.value.map { it.role })
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.add_photos_removed, 1, 1)).assertExists()
        compose.onNodeWithText(text(R.string.add_step_back)).assertExists()
        compose.onNodeWithText(text(R.string.add_photo_undo)).performClick()
        assertEquals(listOf(PhotoRole.FRONT, PhotoRole.BACK), vm.photos.value.map { it.role })
        compose.onNodeWithText(text(R.string.add_photo_undo)).assertDoesNotExist()
    }

    @Test fun theUndoOfferTimesOutAndThePhotoIsGoneForGood() {
        vm.addCaptured(graph.stagedJpeg())
        val file = vm.photos.value.single().file
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.remove_photo, roleLabel(PhotoRole.FRONT))).performClick()
        compose.onNodeWithText(text(R.string.add_photo_undo)).assertExists()
        assertTrue(file.exists())
        compose.mainClock.advanceTimeBy(30_000)
        compose.onNodeWithText(text(R.string.add_photo_undo)).assertDoesNotExist()
        assertFalse(file.exists())
    }

    @Test fun withTalkBackTheUndoOfferStaysUntilTheNextChange() {
        val a11y = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(a11y).setTouchExplorationEnabled(true)
        vm.addCaptured(graph.stagedJpeg())
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.remove_photo, roleLabel(PhotoRole.FRONT))).performClick()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText(text(R.string.add_photo_undo)).assertExists()
    }

    @Test fun doneSavesTheCoffeeAndHandsBackItsId() {
        repeat(2) { vm.addCaptured(graph.stagedJpeg()) }
        show()
        done.performClick()
        awaitOnMain(what = "save") { created != null }
        assertEquals(2, runBlocking { graph.dao.photos(created!!) }.size)
        assertEquals(0, canceled)
    }

    @Test fun aFailedSaveSaysSoAndKeepsThePhotos() {
        vm.addCaptured(graph.stagedJpeg())
        graph.photos.root.deleteRecursively()
        graph.photos.root.writeText("") // the photo library can't be written
        show()
        val before = liveRegions()
        done.performClick()
        awaitOnMain(what = "save to fail") { vm.saveFailed.value && !vm.saving.value }
        compose.onNodeWithText(text(R.string.add_save_failed)).assertExists()
        assertAnnouncedOnArrival(text(R.string.add_save_failed), before)
        assertEquals(1, stagedCount())
        done.assertIsEnabled()
        assertEquals(null, created)
    }

    @Test fun cancelDiscardsTheStagedPhotos() {
        vm.addCaptured(graph.stagedJpeg())
        val file = vm.photos.value.single().file
        show()
        compose.onNodeWithContentDescription(text(R.string.detail_cancel)).performClick()
        assertEquals(1, canceled)
        assertFalse(file.exists())
        assertEquals(0, stagedCount())
    }

    @Test fun backDiscardsTheStagedPhotosToo() {
        vm.addCaptured(graph.stagedJpeg())
        val file = vm.photos.value.single().file
        show()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, canceled)
        assertFalse(file.exists())
    }
}
