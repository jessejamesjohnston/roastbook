package dev.roastbook.app.extraction

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.testutil.TestGraph
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The foreground read loop: it runs while the app is on screen, reads every row waiting for a read,
 * sleeps until a parked row is due, and retries the hero crops that never landed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtractionSchedulerTest {
    private lateinit var g: TestGraph
    private var now = 1_000_000L
    /** How often the loop has looked at the time: once per due check, once more before it sleeps. */
    @Volatile private var clockReads = 0
    private val clock = Clock { clockReads++; now }
    private val nano = ScriptedNano()
    private lateinit var scheduler: ExtractionScheduler

    /** The process-wide lifecycle the loop is tied to; a JVM singleton, so every test puts it back. */
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    @Before fun setUp() {
        g = TestGraph()
        val extractor = Extractor(g.db, g.photos, nano, TextOcr(), OffCloud, ScriptedReader(), MemoryHealth(), clock, shortTimeouts)
        scheduler = ExtractionScheduler(g.dao, extractor, ReadPass(g.dao, extractor), g.repo, clock)
    }

    @After fun tearDown() {
        process.currentState = Lifecycle.State.CREATED // leaving the foreground cancels the loop
        // The process scope outlives this test (it is never destroyed); end this test's loop so a
        // later test that brings the process to the foreground does not restart it on a closed database.
        ProcessLifecycleOwner.get().lifecycleScope.coroutineContext.job.cancelChildren()
        shadowOf(Looper.getMainLooper()).idle()
        g.close()
    }

    private fun appOnScreen() {
        process.currentState = Lifecycle.State.STARTED
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!runBlocking { condition() }) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun aBagWaitingForItsReadIsReadWhileTheAppIsOnScreen() = runBlocking {
        g.extractingCoffee("c1")
        nano.answers += { """{"roaster":"Stillwick Roasters","coffee_name":"Copper Ridge"}""" }
        scheduler.start()
        appOnScreen()
        waitFor("the read") { g.dao.get("c1")!!.state == CoffeeState.LOGGED }
        assertEquals("Stillwick Roasters", g.dao.get("c1")!!.roaster)
    }

    @Test fun nothingIsReadWhileTheAppIsAway() = runBlocking {
        g.extractingCoffee("c1")
        nano.answers += { """{"roaster":"Larkmoor"}""" }
        process.currentState = Lifecycle.State.CREATED
        scheduler.start()
        repeat(20) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(CoffeeState.EXTRACTING, g.dao.get("c1")!!.state)
        assertEquals(0, nano.calls)

        appOnScreen()
        waitFor("the read once the app is back") { g.dao.get("c1")!!.state == CoffeeState.LOGGED }
    }

    @Test fun leavingTheAppCancelsARunningReadAndCommitsNothing() = runBlocking {
        g.extractingCoffee("c1")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
        nano.answers += {
            entered.complete(Unit)
            try { gate.await() } catch (e: kotlinx.coroutines.CancellationException) { canceled.set(true); throw e }
        }
        // Long enough that only leaving the app can end this call.
        val extractor = Extractor(g.db, g.photos, nano, TextOcr(), OffCloud, ScriptedReader(), MemoryHealth(), clock, shortTimeouts.copy(nanoMs = 60_000))
        ExtractionScheduler(g.dao, extractor, ReadPass(g.dao, extractor), g.repo, clock).start()
        try {
            appOnScreen()
            waitFor("the model call to start") { entered.isCompleted }
            process.currentState = Lifecycle.State.CREATED
            waitFor("the model call to be canceled") { canceled.get() }
            gate.complete("""{"roaster":"Larkmoor"}""")
            repeat(20) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
            val c = g.dao.get("c1")!!
            assertEquals(CoffeeState.EXTRACTING, c.state)
            assertEquals(null, c.roaster)
            assertEquals("a read stopped by leaving the app is not a failed attempt", 0, c.extractionAttempts)
        } finally {
            gate.complete("{}")
        }
    }

    @Test fun aParkedBagIsReadOnceItsRetryComesDue() = runBlocking {
        val due = now + 180_000
        g.extractingCoffee("c1") { copy(nextAttemptAt = due, extractionAttempts = 1) }
        nano.answers += { """{"roaster":"Larkmoor"}""" }
        scheduler.start()
        appOnScreen()
        waitFor("the loop to check the parked row and go to sleep") { clockReads >= 2 }
        assertEquals("not read before its time", 0, nano.calls)
        assertEquals(CoffeeState.EXTRACTING, g.dao.get("c1")!!.state)

        now = due
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180_000))
        waitFor("the retry") { g.dao.get("c1")!!.state == CoffeeState.LOGGED }
        assertEquals("Larkmoor", g.dao.get("c1")!!.roaster)
    }

    @Test fun aLastAttemptOnTheOnDeviceReaderFinishesInsteadOfStrandingTheRow() = runBlocking {
        // Two attempts already used. The reader's attempt is counted before it runs, which takes the
        // row to the cap while the read is still going: the loop must not drop the row (and cancel
        // the read) because of that count.
        g.extractingCoffee("c1") { copy(extractionAttempts = 2) }
        val reader = ScriptedReader(LocalAvailability.READY) { kotlinx.coroutines.delay(300); """{"roaster":"Northwind"}""" }
        val extractor = Extractor(g.db, g.photos, ScriptedNano(status = NanoStatus.UNAVAILABLE), TextOcr(), OffCloud, reader, MemoryHealth(), clock, shortTimeouts)
        ExtractionScheduler(g.dao, extractor, ReadPass(g.dao, extractor), g.repo, clock).start()
        appOnScreen()
        waitFor("the read to finish") { g.dao.get("c1")!!.state == CoffeeState.LOGGED }
        val c = g.dao.get("c1")!!
        assertEquals(dev.roastbook.app.data.db.ExtractionSource.LOCAL, c.extractionSource)
        assertEquals("Northwind", c.roaster)
    }

    @Test fun startingTwiceRunsOneLoop() = runBlocking {
        // A hero crop that never landed is retried once per loop start; its original is unreadable
        // here, so each retry counts one attempt.
        g.insertCoffee("h1", CoffeeState.LOGGED) { copy(heroPhotoId = "p1") }
        g.dao.insertPhotos(listOf(dev.roastbook.app.data.db.CoffeePhotoEntity("p1", "h1", dev.roastbook.app.data.db.PhotoRole.FRONT, 0, "photos/h1/p1.jpg", takenAt = 1, addedAt = 1)))
        scheduler.start()
        scheduler.start()
        appOnScreen()
        waitFor("the hero retry") { g.dao.get("h1")!!.heroAttempts >= 1 }
        Thread.sleep(200)
        assertEquals(1, g.dao.get("h1")!!.heroAttempts)
    }
}
