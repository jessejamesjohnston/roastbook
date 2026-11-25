package dev.roastbook.app.ui.add

import androidx.camera.core.MirrorMode
import androidx.camera.core.impl.ImageOutputConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Text on a bag must read the right way round in the preview, with either lens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BagPreviewTest {
    @Test fun thePreviewIsNeverMirrored() {
        val config = bagPreview().currentConfig as ImageOutputConfig
        assertEquals(MirrorMode.MIRROR_MODE_OFF, config.getMirrorMode(MirrorMode.MIRROR_MODE_UNSPECIFIED))
    }
}
