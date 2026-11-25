package dev.roastbook.app.ui.detail

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.roastbook.app.MainActivity
import dev.roastbook.app.reminders.EXTRA_RATE_COFFEE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher activity is exported, and ComponentActivity gives the launch intent's extras to
 * activity-scoped ViewModels as saved-state defaults. Only the app's own extra may survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LaunchExtrasTest {
    private val foreign = """{"coffeeId":"00000000-0000-0000-0000-000000000001","section":"facts","drafts":{"notes":"x"},"opened":{}}"""

    private fun sessionsOf(intent: Intent, strip: Boolean): EditSessions {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java, intent).create().get()
        if (strip) MainActivity.takeRateCoffee(activity.intent)
        // The same construction rememberEditSessions uses.
        val factory = viewModelFactory { initializer { EditSessions(createSavedStateHandle()) } }
        return ViewModelProvider.create(activity, factory)[EditSessions::class]
    }

    /** The mechanism the fix closes: an unstripped foreign extra becomes the open edit. */
    @Test fun aForeignExtraSeedsTheSessionWhenLeftOnTheIntent() {
        val s = sessionsOf(Intent().putExtra("edit_session", foreign), strip = false)
        assertNotNull(s.session.value)
    }

    @Test fun takeRateCoffeeDropsForeignExtrasAndKeepsTheReminderId() {
        val i = Intent().putExtra("edit_session", foreign).putExtra(EXTRA_RATE_COFFEE, "c1")
        assertEquals("c1", MainActivity.takeRateCoffee(i))
        assertNull(i.extras)
        val s = sessionsOf(Intent().putExtra("edit_session", foreign), strip = true)
        assertNull(s.session.value)
    }
}
