package dev.roastbook.app.ui.theme

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.view.WindowInsetsController
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toDrawable
import dev.roastbook.app.R

/**
 * Paints the window and its desktop caption bar in [color]: the caption is transparent over the
 * page, with dark caption buttons on a light page and light ones on a dark page, and the task
 * (the caption's title and the overview) takes the same color and [label].
 */
fun Activity.paintWindow(color: Color, label: String) {
    val argb = color.toArgb()
    window.setBackgroundDrawable(argb.toDrawable())
    // The task's color builder exists from Android 13, the caption-bar flags from Android 15 (before
    // it a window has no caption); on older versions only the window background is painted.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(label).setPrimaryColor(argb).setBackgroundColor(argb).build())
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
    val transparent = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
    window.insetsController?.setSystemBarsAppearance((if (color.luminance() > 0.5f) lightBars else 0) or transparent, lightBars or transparent)
}

/**
 * Keeps the window painted in the current page color, again whenever it changes: the in-app
 * Light/Dark choice, high contrast, or the system's theme.
 */
@Composable
fun PaintWindow() {
    val activity = LocalActivity.current ?: return
    val page = MaterialTheme.colorScheme.surface
    val label = stringResource(R.string.app_name)
    LaunchedEffect(page, label) { activity.paintWindow(page, label) }
}
