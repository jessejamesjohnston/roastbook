package dev.roastbook.app.ui.detail

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey

import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.PhotoRole
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A coffee's photos open full window from the page: the hero or any thumbnail, then the arrow keys
 * or the side buttons move between them, double tap or the scroll wheel zoom, and Escape, Back or
 * Close return to the page. The photos are read from the app's own folder; nothing is shared. The
 * window is tall so the photo strip is on screen without scrolling the page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h2000dp")
class PhotoViewerTest : DetailScreenTestBase() {
    private fun withPhotos() {
        insert { copy(roaster = "Northwind", coffeeName = "Cloud Terrace", heroPhotoId = "p2") }
        File(f.context.filesDir, "photos/c1").mkdirs()
        runBlocking {
            f.graph.dao.insertPhotos((1..3).map { n ->
                File(f.context.filesDir, "photos/c1/p$n.jpg").writeBytes(f.graph.stagedJpeg().readBytes())
                CoffeePhotoEntity("p$n", "c1", if (n == 1) PhotoRole.FRONT else PhotoRole.BACK, n - 1,
                    original = "photos/c1/p$n.jpg", takenAt = 1, addedAt = 1)
            })
        }
        show()
        compose.awaitUntil { vm.photos.value.size == 3 }
    }

    private fun viewing(n: Int) = compose.onNodeWithContentDescription(str(R.string.photo_viewer_description, "Cloud Terrace", n, 3))
    private fun close() = compose.onNodeWithContentDescription(str(R.string.photo_viewer_close))

    @Test fun aThumbnailOpensItsPhotoAndTheArrowKeysMoveBetweenThem() {
        withPhotos()
        compose.onNodeWithContentDescription(str(R.string.detail_photo_position, 2, 3), useUnmergedTree = true).performClick()
        compose.onNode(isDialog()).assertExists()
        viewing(2).assertExists()
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionRight) }
        viewing(3).assertExists()
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.DirectionLeft) }
        viewing(1).assertExists()
        compose.onNode(isDialog()).performKeyInput { pressKey(Key.Escape) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun theHeroOpensTheHeroPhotoAndTheSideButtonsAndCloseWork() {
        withPhotos()
        compose.onNodeWithContentDescription(str(R.string.detail_hero_description, "Cloud Terrace")).performClick()
        viewing(2).assertExists()
        compose.onNodeWithContentDescription(str(R.string.photo_viewer_next)).performClick()
        viewing(3).assertExists()
        compose.onNodeWithContentDescription(str(R.string.photo_viewer_previous)).performClick()
        viewing(2).assertExists()
        close().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun theSystemBackClosesTheViewer() {
        withPhotos()
        compose.onNodeWithContentDescription(str(R.string.detail_photo_position, 1, 3), useUnmergedTree = true).performClick()
        viewing(1).assertExists()
        // The system Back goes to the viewer's own window, which sits over the page.
        compose.runOnUiThread {
            (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        assert(backs == 0) { "Back closed the viewer, not the page" }
    }

    @Test fun doubleTapAndTheScrollWheelZoom() {
        withPhotos()
        compose.onNodeWithContentDescription(str(R.string.detail_photo_position, 1, 3), useUnmergedTree = true).performClick()
        val zoomed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.photo_viewer_zoomed))
        viewing(1).assert(!zoomed)
        viewing(1).performTouchInput { doubleClick() }
        viewing(1).assert(zoomed)
        viewing(1).performTouchInput { doubleClick() }
        viewing(1).assert(!zoomed)
        viewing(1).performMouseInput { scroll(-3f) }
        viewing(1).assert(zoomed)
    }
}
