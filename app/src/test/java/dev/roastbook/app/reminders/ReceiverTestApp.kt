package dev.roastbook.app.reminders

import android.app.Application
import dagger.hilt.internal.GeneratedComponentManager

/**
 * A plain test application that hands the two broadcast receivers their [ReminderScheduler], the
 * way the Hilt component would, without building the real application's graph. Set [scheduler]
 * before a broadcast is delivered.
 */
class ReceiverTestApp : Application(), GeneratedComponentManager<Any> {
    private val injector = object : ReminderReceiver_GeneratedInjector, BootReceiver_GeneratedInjector {
        override fun injectReminderReceiver(reminderReceiver: ReminderReceiver) { reminderReceiver.scheduler = scheduler!! }
        override fun injectBootReceiver(bootReceiver: BootReceiver) { bootReceiver.scheduler = scheduler!! }
    }

    override fun generatedComponent(): Any = injector

    companion object {
        var scheduler: ReminderScheduler? = null
    }
}
