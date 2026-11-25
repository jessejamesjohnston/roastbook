package dev.roastbook.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Remove animations" is respected: nothing decorative moves, and nothing loops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MotionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun reset() { Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }

    @Test fun removeAnimationsIsSeen() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(Motion.animationsOff(context))
    }

    @Test fun normalAndSlowedAnimationsStillMove() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(Motion.animationsOff(context))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        assertFalse(Motion.animationsOff(context))
    }

    @Test fun screensReadTheSettingWhenTheyOpen() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertEquals(true, rememberedAnimationsOff())
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertEquals(false, rememberedAnimationsOff())
    }

    /** What a freshly opened screen reads from [rememberAnimationsOff]. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun rememberedAnimationsOff(): Boolean? {
        var off: Boolean? = null
        androidx.compose.ui.test.v2.runComposeUiTest {
            setContent { off = rememberAnimationsOff() }
            waitForIdle()
        }
        return off
    }

    /**
     * Compose finishes its own animations at once when the scale is 0, but a looping animation has
     * no end state to finish at. The app has none; this keeps it that way.
     */
    @Test fun noLoopingAnimationInTheApp() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("source tree not found from ${File(".").absolutePath}", sources.size > 20)
        val looping = sources.filter { f -> f.readText().let { "rememberInfiniteTransition" in it || "infiniteRepeatable" in it } }
        assertEquals(emptyList<File>(), looping)
    }
}
