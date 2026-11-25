package dev.roastbook.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.testutil.TestGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The alarm and boot broadcasts reach the scheduler and end in the notification or the re-armed alarms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReceiverTestApp::class)
class ReminderReceiversTest {
    private lateinit var g: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Before fun setUp() {
        g = TestGraph()
        ReceiverTestApp.scheduler = g.reminders
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() {
        ReceiverTestApp.scheduler = null
        g.close()
    }

    private fun deliver(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The receivers finish on a background dispatcher; wait for what they promise to do. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test fun theRateItAlarmPostsTheNotification() = runBlocking {
        g.insertCoffee("c1") { copy(coffeeName = "Hills of Lumen", remindAt = 1_000) }
        deliver(Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_RATE_COFFEE, "c1").putExtra(EXTRA_KIND, KIND_RATE))
        waitFor("the rate-it notification") { notifications.isNotEmpty() }
        assertEquals(context.getString(R.string.notif_remind_title, "Hills of Lumen"), title(notifications.single()))
        waitFor("the reminder to be consumed") { runBlocking { g.dao.get("c1")!!.remindAt == null } }
    }

    @Test fun anAlarmWithNoKindIsTheRateItReminder() = runBlocking {
        g.insertCoffee("c1") { copy(roaster = "Larkmoor", remindAt = 1_000) }
        deliver(Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_RATE_COFFEE, "c1"))
        waitFor("the rate-it notification") { notifications.isNotEmpty() }
        assertEquals(context.getString(R.string.notif_remind_title, "Larkmoor"), title(notifications.single()))
    }

    @Test fun theSecondLookAlarmPostsTheSecondLookNotification() = runBlocking {
        g.insertCoffee("c1", CoffeeState.RATED) { copy(coffeeName = "Hills of Lumen", overall = 4f, secondLookAt = 1_000) }
        deliver(Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_RATE_COFFEE, "c1").putExtra(EXTRA_KIND, KIND_SECOND_LOOK))
        waitFor("the second-look notification") { notifications.isNotEmpty() }
        assertEquals(context.getString(R.string.notif_second_look_title, "4.0", "Hills of Lumen"), title(notifications.single()))
        assertTrue(g.dao.get("c1")!!.secondLookDone)
    }

    @Test fun anAlarmThatNamesNoCoffeeDoesNothing() = runBlocking {
        g.insertCoffee("c1") { copy(remindAt = 1_000) }
        deliver(Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_KIND, KIND_RATE))
        assertTrue(notifications.isEmpty())
        assertEquals(1_000L, g.dao.get("c1")!!.remindAt)
    }

    @Test fun bootPutsBackEveryNudgeThatHasNotFired() = runBlocking {
        val later = System.currentTimeMillis() + 86_400_000L
        g.insertCoffee("rate-me") { copy(remindAt = later) }
        g.insertCoffee("look-again", CoffeeState.RATED) { copy(overall = 3.5f, secondLookAt = later + 1) }
        deliver(Intent(Intent.ACTION_BOOT_COMPLETED))
        waitFor("both alarms") { alarms.size == 2 }
        assertEquals(setOf(later, later + 1), alarms.map { it.triggerAtMs }.toSet())
    }

    @Test fun anyOtherBroadcastToTheBootReceiverArmsNothing() = runBlocking {
        g.insertCoffee("rate-me") { copy(remindAt = System.currentTimeMillis() + 86_400_000L) }
        deliver(Intent(context, BootReceiver::class.java).setAction(Intent.ACTION_TIME_CHANGED))
        // Arming would happen on a background dispatcher, so an empty list right away proves nothing:
        // watch for longer than the boot test above needs to see its alarms.
        repeat(30) { Thread.sleep(10); assertTrue("nothing is armed", alarms.isEmpty()) }
        assertNull(notifications.firstOrNull())
    }
}
