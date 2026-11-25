package dev.roastbook.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.MainActivity
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.ui.rating.RatingDraft
import dev.roastbook.app.ui.rating.RatingHandoff
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the rate-it and second-look nudges post, where a tap lands, and how a reboot re-arms them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReminderSchedulerTest {
    private lateinit var g: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Before fun setUp() { g = TestGraph() }
    @After fun tearDown() { g.close() }

    private fun allowNotifications() = shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    private fun posted() = shadowOf(nm).allNotifications

    @Test fun tappingTheNudgeOpensTheAppOnThatCoffee() = runTest {
        allowNotifications()
        g.insertCoffee("c1") { copy(coffeeName = "Hills of Lumen", remindAt = 1_000) }
        g.reminders.notify("c1")
        val n = posted().single()
        assertEquals(context.getString(R.string.notif_remind_body), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue("a tap dismisses it", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component!!.className)
        assertEquals("c1", open.getStringExtra(EXTRA_RATE_COFFEE))
    }

    @Test fun theNudgesPostOnTheRemindersChannel() = runTest {
        allowNotifications()
        g.insertCoffee("c1") { copy(roaster = "Northwind", remindAt = 1_000) }
        g.reminders.notify("c1")
        assertEquals(ReminderScheduler.CHANNEL, posted().single().channelId)
        assertEquals(context.getString(R.string.notif_channel_reminders), nm.getNotificationChannel(ReminderScheduler.CHANNEL).name.toString())
    }

    @Test fun aCoffeeWithNoNameIsCalledUnnamed() = runTest {
        allowNotifications()
        g.insertCoffee("c1") { copy(remindAt = 1_000) }
        g.reminders.notify("c1")
        val untitled = context.getString(R.string.detail_untitled)
        assertEquals(context.getString(R.string.notif_remind_title, untitled), posted().single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test fun aCoffeeRatedBeforeItsReminderFiredIsNotNudged() = runTest {
        allowNotifications()
        g.insertCoffee("c1", CoffeeState.RATED) { copy(roaster = "Larkmoor", overall = 4f, remindAt = 1_000) }
        g.reminders.notify("c1")
        assertTrue(posted().isEmpty())
        assertNull("still consumed", g.dao.get("c1")!!.remindAt)
    }

    @Test fun refusedNotificationsDropTheNudgeButStillConsumeIt() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        g.insertCoffee("c1") { copy(roaster = "Larkmoor", remindAt = 1_000) }
        g.reminders.notify("c1")
        assertTrue(posted().isEmpty())
        assertNull(g.dao.get("c1")!!.remindAt)
    }

    @Test fun theTwoNudgesForOneCoffeeAreSeparateNotifications() = runTest {
        allowNotifications()
        g.insertCoffee("c1", CoffeeState.RATED) { copy(roaster = "Larkmoor", overall = 3.5f, remindAt = 1_000, secondLookAt = 1_000) }
        g.reminders.notify("c1") // rated, so posts nothing
        g.insertCoffee("c2") { copy(roaster = "Larkmoor", remindAt = 1_000) }
        g.reminders.notify("c2")
        g.dao.setSecondLook("c2", 1_000)
        g.dao.mutate("c2") { it.copy(overall = 3.5f) }
        g.reminders.notifySecondLook("c2")
        assertEquals(2, posted().size)
        assertEquals(context.getString(R.string.notif_second_look_title, "3.5", "Larkmoor"), posted().last().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    /**
     * A scheduler whose DAO hands back the row and then lets [meanwhile] change it before the
     * delivery writes: a restore or a moved reminder landing between the read and the consume.
     */
    private fun racedScheduler(meanwhile: suspend CoffeeDao.(String) -> Unit): ReminderScheduler {
        val real = g.dao
        return ReminderScheduler(context, object : CoffeeDao by real {
            override suspend fun get(id: String): CoffeeEntity? = real.get(id).also { real.meanwhile(id) }
        })
    }

    @Test fun aRateItReminderMovedLaterWhileTheOldOneFiresIsKeptAndNothingPosts() = runTest {
        allowNotifications()
        val later = System.currentTimeMillis() + 86_400_000L
        g.insertCoffee("c1") { copy(roaster = "Larkmoor", remindAt = 1_000) }
        racedScheduler { setRemindAt(it, later, System.currentTimeMillis()) }.notify("c1")
        assertEquals(later, g.dao.get("c1")!!.remindAt)
        assertTrue(posted().isEmpty())
    }

    @Test fun aSecondLookReplacedWhileTheOldOneFiresIsKeptAndNothingPosts() = runTest {
        allowNotifications()
        val later = System.currentTimeMillis() + 86_400_000L
        g.insertCoffee("c1", CoffeeState.RATED) { copy(roaster = "Larkmoor", overall = 4f, secondLookAt = 1_000) }
        racedScheduler { setSecondLook(it, later) }.notifySecondLook("c1")
        val c = g.dao.get("c1")!!
        assertEquals(later, c.secondLookAt)
        assertFalse(c.secondLookDone)
        assertTrue(posted().isEmpty())
    }

    @Test fun aRebootReArmsAnOverdueNudgeAMinuteFromNowNotInThePast() = runTest {
        g.insertCoffee("overdue") { copy(remindAt = 1_000) }
        g.insertCoffee("look", CoffeeState.RATED) { copy(overall = 4f, secondLookAt = 2_000) }
        val before = System.currentTimeMillis()
        g.reminders.rescheduleAll()
        val after = System.currentTimeMillis()
        assertEquals(2, alarms.size)
        alarms.forEach { assertTrue("re-armed at ${it.triggerAtMs}", it.triggerAtMs in (before + 60_000)..(after + 60_000)) }
    }

    @Test fun eachAlarmCarriesItsCoffeeAndItsKindToTheReceiver() {
        g.reminders.schedule("c1", 5_000_000)
        g.reminders.scheduleSecondLook("c2", 6_000_000)
        val delivered = alarms.associate { a ->
            // Robolectric 4.15 exposes a scheduled alarm's PendingIntent only through this
            // deprecated field; ScheduledAlarm has no getter for it yet.
            @Suppress("DEPRECATION")
            val i = shadowOf(a.operation).savedIntent
            a.triggerAtMs to Triple(i.component?.className, i.getStringExtra(EXTRA_RATE_COFFEE), i.getStringExtra(EXTRA_KIND))
        }
        assertEquals(
            mapOf(
                5_000_000L to Triple(ReminderReceiver::class.java.name, "c1", KIND_RATE),
                6_000_000L to Triple(ReminderReceiver::class.java.name, "c2", KIND_SECOND_LOOK),
            ),
            delivered,
        )
    }

    @Test fun aRebootLeavesConsumedNudgesUnarmed() = runTest {
        g.insertCoffee("rated", CoffeeState.RATED) { copy(overall = 4f, remindAt = System.currentTimeMillis() + 86_400_000L) }
        g.insertCoffee("looked", CoffeeState.RATED) { copy(overall = 4f, secondLookAt = 5_000, secondLookDone = true) }
        g.reminders.rescheduleAll()
        assertTrue(alarms.isEmpty())
    }

    @Test fun pendingRequestsWaitForTheirOneConsumer() {
        val p = PendingActions()
        assertNull(p.rateCoffee.value)
        p.rateCoffee.value = "c1"
        p.showRatingSheet.value = "c1"
        p.ratingDraft.value = RatingHandoff("c1", RatingDraft(aroma = 4))
        assertEquals("c1", p.rateCoffee.value)
        assertEquals("c1", p.showRatingSheet.value)
        assertEquals(4, p.ratingDraft.value!!.draft.aroma)
    }
}
