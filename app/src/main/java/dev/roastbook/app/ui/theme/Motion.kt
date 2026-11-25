package dev.roastbook.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Reduced motion. Android's "Remove animations" sets the animator duration scale to 0; Compose then
 * finishes its own animations at once. Code that chooses between moving and jumping (the intro's
 * Next button) asks here, so the jump is explicit and every state still shows.
 */
object Motion {
    fun animatorScale(context: Context): Float =
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

    fun animationsOff(context: Context): Boolean = animatorScale(context) == 0f
}

/** Read once per screen: the setting changes rarely, and a screen opened after the change picks it up. */
@Composable
fun rememberAnimationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) { Motion.animationsOff(context) }
}
