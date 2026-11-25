package dev.roastbook.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import dev.roastbook.app.ui.nav.Shortcut
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.roastbook.app.reminders.EXTRA_RATE_COFFEE
import dev.roastbook.app.data.prefs.UserPrefs
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.ui.nav.RoastbookNavHost
import dev.roastbook.app.ui.theme.NightMode
import dev.roastbook.app.ui.theme.RoastbookTheme
import dev.roastbook.app.data.prefs.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import javax.inject.Inject

/**
 * The app's single activity and exported launcher. It reads settings once before the first frame
 * (onboarding, theme), hosts the Compose navigation, turns a reminder tap into a pending "rate this
 * coffee" action, and maps hardware-keyboard shortcuts and Esc. Launch extras other than the
 * reminder's are dropped before any screen sees them.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var pending: PendingActions
    @Inject lateinit var userPrefs: UserPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consume(intent)
        // One tiny DataStore read before the first frame: first run? which theme?
        val initial = kotlinx.coroutines.runBlocking { userPrefs.current() }
        val showOnboarding = !initial.onboardingDone
        // The system keeps the app's night mode itself; re-applying reconciles a restored backup
        // (DataStore came back, the system setting did not) and is a no-op otherwise.
        NightMode.apply(this, initial.themeMode)
        setContent {
            val settings by userPrefs.settings.collectAsState(initial = initial)
            val dark = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            RoastbookTheme(darkTheme = dark, highContrast = settings.highContrast) {
                RoastbookNavHost(pending, showOnboarding, shortcuts)
            }
        }
    }

    private val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 1)

    /** Ctrl+N adds a coffee, Ctrl+comma opens Settings (keyboards on tablets and Chromebooks). */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val s = when (keyCode) {
            KeyEvent.KEYCODE_N -> Shortcut.NEW_COFFEE
            KeyEvent.KEYCODE_COMMA -> Shortcut.SETTINGS
            else -> return super.onKeyShortcut(keyCode, event)
        }
        return shortcuts.tryEmit(s)
    }

    /**
     * Esc goes back, like Back, on every screen. Android only maps Esc to Back as a fallback when
     * nothing handles it, and Compose's focus handling takes it first, so it is mapped here: after
     * the screen had its chance (a field or a menu may use Esc), an unmodified Esc presses Back.
     * Dialogs are their own windows and close on Esc themselves.
     *
     * Activity.dispatchKeyEvent is public Android API; androidx.core's ComponentActivity marks its
     * own override restricted, which lint reports on any subclass that overrides it.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ESCAPE || !event.hasNoModifiers()) return super.dispatchKeyEvent(event)
        if (super.dispatchKeyEvent(event)) return true
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
        return true
    }

    /** Lists the shortcuts in the system's keyboard shortcuts helper (Meta + /). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.add(
            KeyboardShortcutGroup(
                getString(R.string.app_name),
                listOf(
                    KeyboardShortcutInfo(getString(R.string.shortcut_new_coffee), KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
                    KeyboardShortcutInfo(getString(R.string.shortcut_settings), KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON),
                ),
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        takeRateCoffee(intent)?.let { pending.rateCoffee.value = it }
    }

    companion object {
        /**
         * Reads the one extra the app sends itself and drops every other. This activity is the
         * exported launcher, so any app can start it with extras, and ComponentActivity hands the
         * launch intent's extras to every activity-scoped ViewModel as its saved-state defaults:
         * a foreign "edit_session" extra would otherwise open as an edit the user never typed.
         * Runs before setContent, so before any of those ViewModels exists.
         */
        internal fun takeRateCoffee(intent: Intent?): String? {
            val id = intent?.getStringExtra(EXTRA_RATE_COFFEE)
            intent?.replaceExtras(null as Bundle?)
            return id
        }
    }
}
