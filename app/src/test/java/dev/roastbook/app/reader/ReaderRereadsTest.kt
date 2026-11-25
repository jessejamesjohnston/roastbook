package dev.roastbook.app.reader

import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.testutil.TestGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bags whose reader offer was accepted are read again once the reader is here: when the
 * download finishes while the app runs, and at the next start when it finished while the app was
 * gone. A canceled download leaves nothing to read again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderRereadsTest {
    private val h = ReaderHarness()
    private val graph = TestGraph()

    @After fun tearDown() {
        graph.close()
        h.close()
    }

    private fun manager(): LocalReaderManager {
        h.device()
        h.debugSpec()
        return h.manager()
    }

    private suspend fun state(id: String) = graph.dao.get(id)!!.state

    private suspend fun awaitState(id: String, want: CoffeeState) =
        withTimeout(10_000) { while (state(id) != want) delay(10) }

    /** Runs [ReaderRereads.run] for [block], as the app does from its start. */
    private fun withRereads(m: LocalReaderManager, block: suspend () -> Unit) = runBlocking {
        val job = launch(Dispatchers.IO) { ReaderRereads(m, h.prefs, graph.repo).run() }
        try { block() } finally { job.cancelAndJoin() }
    }

    @Test fun waitingBagsAreReadAgainWhenTheDownloadFinishes() {
        val m = manager()
        runBlocking {
            graph.insertCoffee("a"); graph.insertCoffee("b"); graph.insertCoffee("c")
            h.prefs.acceptOffer("a"); h.prefs.acceptOffer("b")
        }
        withRereads(m) {
            delay(200)
            assertEquals("nothing is read before the reader is here", CoffeeState.LOGGED, state("a"))
            h.installModel(m)
            m.onDiskChanged()
            awaitState("a", CoffeeState.EXTRACTING)
            awaitState("b", CoffeeState.EXTRACTING)
            assertEquals(emptySet<String>(), h.prefs.current().pendingRereads)
            assertEquals("a bag that was not waiting is left alone", CoffeeState.LOGGED, state("c"))
        }
    }

    @Test fun aDownloadThatFinishedWhileTheAppWasGoneIsUsedAtTheNextStart() {
        val m = manager()
        runBlocking {
            graph.insertCoffee("a")
            h.prefs.acceptOffer("a")
        }
        // The download finished with no app running: the model is in place before anything watches.
        h.installModel(m)
        val restarted = h.manager()
        withRereads(restarted) {
            awaitState("a", CoffeeState.EXTRACTING)
            assertEquals(emptySet<String>(), h.prefs.current().pendingRereads)
        }
    }

    @Test fun aCanceledDownloadLeavesNothingToReadAgain() {
        val m = manager()
        runBlocking {
            graph.insertCoffee("a")
            h.prefs.acceptOffer("a")
            m.download()
            m.cancel()
        }
        withRereads(m) {
            h.installModel(m)
            m.onDiskChanged()
            h.awaitState(m) { it.phase == DownloadPhase.READY }
            delay(300)
            assertEquals(CoffeeState.LOGGED, state("a"))
        }
    }
}
