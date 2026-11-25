package dev.roastbook.app.ui.add

import android.os.Looper
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/**
 * Waits for work that hops to a background thread (Room, file copies) and resumes on the main
 * looper: runs the looper's queued tasks until [done] holds, failing after [timeoutMs].
 */
internal fun awaitOnMain(timeoutMs: Long = 5_000, what: String = "condition", done: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (done()) return
        if (System.currentTimeMillis() > until) fail("timed out waiting for $what")
        Thread.sleep(10)
    }
}
