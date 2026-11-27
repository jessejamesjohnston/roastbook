package dev.roastbook.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.MainActivity
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.ui.rating.RatingHandoff
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Intent extra carrying the coffee id, on both the alarm broadcast and the notification tap. */
const val EXTRA_RATE_COFFEE = "rate_coffee"
/** Intent extra naming which nudge an alarm is for: [KIND_RATE] or [KIND_SECOND_LOOK]. */
const val EXTRA_KIND = "kind"
/** The rate-it reminder the user sets for an unrated coffee. */
const val KIND_RATE = "rate"
/** The one look back a few days after a coffee's first rating. */
const val KIND_SECOND_LOOK = "second"

/**
 * Cross-screen requests. Each flow has exactly one consumer, which clears it:
 * [rateCoffee] (notification tap → the NavHost, which returns to History),
 * [showRatingSheet] (NavHost → History's ViewModel), [ratingDraft] (History sheet → Detail).
 */
@Singleton
class PendingActions @Inject constructor() {
    val rateCoffee = MutableStateFlow<String?>(null)
    val showRatingSheet = MutableStateFlow<String?>(null)
    val ratingDraft = MutableStateFlow<RatingHandoff?>(null)
}

/**
 * The rate-it reminder: an inexact alarm per coffee with a one-hour window (Doze may defer it
 * further, which is fine for "tomorrow"), posting a notification that deep-links to the rating sheet.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CoffeeDao,
) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    fun schedule(coffeeId: String, at: Long) = arm(coffeeId, at, KIND_RATE)

    /** The one "second look" a few days after the first rating; separate alarm, same tap target. */
    fun scheduleSecondLook(coffeeId: String, at: Long) = arm(coffeeId, at, KIND_SECOND_LOOK)

    private fun arm(coffeeId: String, at: Long, kind: String) {
        ensureChannel()
        val window = 60L * 60 * 1000
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, window, pendingIntent(coffeeId, kind))
        Log.i(TAG, "$kind for $coffeeId at $at")
    }

    /**
     * Cancels one nudge. The two kinds are independent: clearing or satisfying the rate-it reminder
     * must leave the second look armed, so every caller names the kind it means.
     */
    fun cancel(coffeeId: String, kind: String) {
        alarms.cancel(pendingIntent(coffeeId, kind))
    }

    /** After a reboot or force-stop alarms are gone; put back every nudge that has not fired yet. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        dao.pendingReminders().forEach { c -> c.remindAt?.let { at -> schedule(c.id, maxOf(at, now + 60_000)) } }
        dao.pendingSecondLooks().forEach { c -> c.secondLookAt?.let { at -> scheduleSecondLook(c.id, maxOf(at, now + 60_000)) } }
    }

    suspend fun notify(coffeeId: String) {
        val c = dao.get(coffeeId) ?: return
        // No stored reminder means this alarm was already canceled or consumed (a stale broadcast
        // from a canceled PendingIntent still reaches us); post nothing and clear nothing.
        val at = c.remindAt ?: return
        // A broadcast more than a minute early is a stale alarm from an earlier schedule (the
        // reminder was moved later, or a restore brought a later one): leave the stored one alone.
        if (at > System.currentTimeMillis() + 60_000) return
        // Consumed the moment it fires, whatever happens next: a reminder never re-arms itself at the
        // next app start, so an unrated coffee is nudged once, not on every launch. Only the
        // reminder read above is consumed: one moved or restored since then stays, and this
        // delivery posts nothing.
        if (dao.clearReminder(coffeeId, at) == 0) return
        if (c.overall != null) return
        val name = c.coffeeName ?: c.roaster ?: context.getString(R.string.detail_untitled)
        post(coffeeId, coffeeId.hashCode(), context.getString(R.string.notif_remind_title, name), context.getString(R.string.notif_remind_body))
    }

    suspend fun notifySecondLook(coffeeId: String) {
        val c = dao.get(coffeeId) ?: return
        // Three ways this alarm is not the one we owe the user, and none of them may consume it:
        // the look already happened, nothing is scheduled, or the broadcast arrived early (a stale
        // alarm from a previous schedule). Marking it done here would eat the real nudge.
        if (c.secondLookDone) return
        val at = c.secondLookAt ?: return
        if (at > System.currentTimeMillis() + 60_000) return
        // Once per coffee, whatever happens next; a second look replaced since the read is not this one.
        if (dao.markSecondLookDone(coffeeId, at) == 0) return
        val overall = c.overall ?: return
        val name = c.coffeeName ?: c.roaster ?: context.getString(R.string.detail_untitled)
        post(coffeeId, (coffeeId + KIND_SECOND_LOOK).hashCode(),
            context.getString(R.string.notif_second_look_title, "%.1f".format(java.util.Locale.US, overall), name),
            context.getString(R.string.notif_second_look_body))
    }

    private fun post(coffeeId: String, notificationId: Int, title: String, body: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "notifications not permitted; nudge for $coffeeId dropped")
            return
        }
        ensureChannel()
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_RATE_COFFEE, coffeeId)
        }
        val tap = PendingIntent.getActivity(context, notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_roastbook)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, n) }
            .onFailure { Log.w(TAG, "notify failed (permission?)", it) }
    }

    private fun pendingIntent(coffeeId: String, kind: String): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).apply { putExtra(EXTRA_RATE_COFFEE, coffeeId); putExtra(EXTRA_KIND, kind) }
        val requestCode = if (kind == KIND_RATE) coffeeId.hashCode() else (coffeeId + kind).hashCode()
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.notif_channel_reminders),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    companion object {
        const val CHANNEL = "reminders"
        private const val TAG = "Reminders"
    }
}

/**
 * Receives a nudge alarm and hands it to [ReminderScheduler], which decides whether it is still
 * owed and posts it. An alarm without a kind is treated as the rate-it reminder.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_RATE_COFFEE) ?: return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_RATE
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { if (kind == KIND_SECOND_LOOK) scheduler.notifySecondLook(id) else scheduler.notify(id) } finally { pending.finish() }
        }
    }
}

/** Re-arms every pending nudge after the device boots, since alarms do not survive a reboot. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { scheduler.rescheduleAll() } finally { pending.finish() }
        }
    }
}
