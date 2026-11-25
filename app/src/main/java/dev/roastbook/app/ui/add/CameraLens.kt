package dev.roastbook.app.ui.add

import androidx.camera.core.ExperimentalMirrorMode
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview

/** Which camera the Add screen's preview and shutter use. */
enum class Lens { BACK, FRONT }

/**
 * The back camera when there is one; otherwise the front one (some Chromebooks and tablets have
 * only a front camera, and holding the bag up to it still works). Null when there is neither, and
 * the screen says the camera couldn't start.
 */
fun chooseLens(hasBack: Boolean, hasFront: Boolean): Lens? = when {
    hasBack -> Lens.BACK
    hasFront -> Lens.FRONT
    else -> null
}

/**
 * The preview the Add screen shows. Never mirrored: the camera only ever frames a bag, so its text
 * reads the right way round with the front lens too (CameraX mirrors a front preview by default).
 * Preview's mirror mode is still marked experimental in CameraX 1.6; it is the documented switch.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalMirrorMode::class])
fun bagPreview(): Preview = Preview.Builder().setMirrorMode(MirrorMode.MIRROR_MODE_OFF).build()
