package dev.roastbook.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity

/**
 * Esc as Back, for the app's activity: the screen gets the key first ([screen], the activity's own
 * dispatch), and an unmodified Esc it leaves alone presses Back when the key comes up. One place,
 * so tests run the same mapping the app does.
 */
internal object EscapeKey {
    fun dispatch(activity: ComponentActivity, event: KeyEvent, screen: (KeyEvent) -> Boolean): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ESCAPE || !event.hasNoModifiers()) return screen(event)
        if (screen(event)) return true
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) activity.onBackPressedDispatcher.onBackPressed()
        return true
    }
}
