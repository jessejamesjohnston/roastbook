package dev.roastbook.app.cloud

import dev.roastbook.app.R
import dev.roastbook.app.data.prefs.UserSettings
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.testutil.FakeSecretStore
import dev.roastbook.app.testutil.TestGraph
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cloud calls when the network drops, when the caller goes away, and when the web knows nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CloudEdgeCasesTest {
    private val server = MockWebServer()
    private val client = GeminiClient()
    private lateinit var g: TestGraph

    @Before fun setUp() {
        server.start()
        client.baseUrl = server.url("/").toString().trimEnd('/')
        g = TestGraph()
    }

    @After fun tearDown() {
        server.shutdown()
        g.close()
    }

    private fun enricher(key: String? = "k") = Enricher(g.dao, client, FakeSecretStore(key), g.userPrefs)

    private fun textAnswer(text: String) = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}]}}]}""",
    )

    @Test fun eachFailureHasItsOwnSentence() {
        assertEquals(
            listOf(R.string.cloud_err_key, R.string.cloud_err_model, R.string.cloud_err_limit, R.string.cloud_err_unusable, R.string.cloud_err_network, R.string.cloud_err_other),
            listOf(CloudFailure.KEY, CloudFailure.MODEL, CloudFailure.LIMIT, CloudFailure.UNUSABLE_KEY, CloudFailure.NETWORK, CloudFailure.OTHER).map { it.message },
        )
        val model = g.context.getString(CloudFailure.MODEL.message, "gemini-test")
        assertTrue("the model sentence names the model", model.contains("gemini-test"))
    }

    @Test fun aPlainAnswerAsksForJsonAndReportsNoSearch() = runBlocking {
        server.enqueue(textAnswer("""{"roaster":"Larkmoor"}"""))
        val answer = client.generateAnswer("k", "m", emptyList(), "p")
        assertEquals("""{"roaster":"Larkmoor"}""", answer.text)
        assertFalse(answer.searched)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"responseMimeType\":\"application/json\""))
    }

    @Test fun aDroppedConnectionIsANetworkFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            client.generate("k", "m", emptyList(), "p")
            fail("expected the call to fail")
        } catch (e: IOException) {
            assertEquals(CloudFailure.NETWORK, CloudFailure.of(e))
        }
    }

    @Test fun aKeyCheckWhoseScreenWentAwayIsCanceledNotFailed() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var result: Result<Unit>? = null
        val check = launch(Dispatchers.Default) { result = client.checkKey("k", "m") }
        server.takeRequest() // the request is out
        check.cancelAndJoin()
        assertNull("a canceled check reports neither success nor a failure to show", result)
    }

    @Test fun aLookupThatFindsNothingSaysSo() = runBlocking {
        g.insertCoffee("c1", CoffeeState.LOGGED) { copy(roaster = "Stillwick Roasters") }
        server.enqueue(textAnswer("""{"roaster_url":null,"product_url":null,"roaster_city":null}"""))
        assertTrue(enricher().lookUp("c1") is LookupResult.Nothing)
        val c = g.dao.get("c1")!!
        assertEquals(Enricher.STATUS_NOTHING, c.enrichmentStatus)
        assertNull(c.roasterUrl)
    }

    /**
     * No candidate, a blocked prompt, a candidate stopped for safety with no text, and an answer cut off
     * mid-object are not "nothing found": the lookup failed, the status stays empty and the button stays.
     */
    @Test fun aLookupWithNoUsableAnswerIsAFailureNotNothingFound() = runBlocking {
        g.insertCoffee("c1", CoffeeState.LOGGED) { copy(roaster = "Stillwick Roasters") }
        val bodies = listOf(
            """{"candidates":[]}""",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"finishReason":"SAFETY"}]}""",
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(body, LookupResult.Failed(CloudFailure.OTHER, UserSettings.DEFAULT_GEMINI_MODEL), enricher().lookUp("c1"))
        }
        server.enqueue(textAnswer("""{"roaster_url": "https://signal.exa"""))
        assertEquals("cut off", LookupResult.Failed(CloudFailure.OTHER, UserSettings.DEFAULT_GEMINI_MODEL), enricher().lookUp("c1"))
        assertNull("no status: the button stays", g.dao.get("c1")!!.enrichmentStatus)
    }

    /** Removing the key in Settings cancels a lookup still waiting on Google: nothing lands, nothing more is sent. */
    @Test fun removingTheKeyDuringALookupCancelsItAndLeavesTheRowAlone() = runBlocking {
        g.insertCoffee("c1", CoffeeState.LOGGED) { copy(roaster = "Stillwick Roasters") }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val store = FakeSecretStore("k")
        val lookup = async(Dispatchers.Default) { Enricher(g.dao, client, store, g.userPrefs).lookUp("c1") }
        server.takeRequest()
        store.setGeminiKey(null)
        assertEquals(LookupResult.Skipped, withTimeout(5_000) { lookup.await() })
        assertNull("no status: the button stays", g.dao.get("c1")!!.enrichmentStatus)
        assertEquals("no fallback request", 1, server.requestCount)
    }

    /**
     * A cloud read whose key is removed mid-call stops with a failure, not a cancellation, so the
     * extraction records the cloud as failed and carries on to the label text instead of stopping.
     */
    @Test fun removingTheKeyDuringACloudReadFailsTheCallInsteadOfCancelingTheRead() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val store = FakeSecretStore("k")
        val read = async(Dispatchers.Default) { runCatching { GeminiCloudTier(client, store, g.userPrefs).generate(emptyList(), "p") } }
        server.takeRequest()
        store.setGeminiKey(null)
        val failure = withTimeout(5_000) { read.await() }.exceptionOrNull()
        assertTrue("got $failure", failure is KeyRemovedException)
    }

    @Test fun aLookupWhoseScreenWentAwayLeavesTheRowAlone() = runBlocking {
        g.insertCoffee("c1", CoffeeState.LOGGED) { copy(roaster = "Stillwick Roasters") }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var result: LookupResult? = null
        val lookup = launch(Dispatchers.Default) { result = enricher().lookUp("c1") }
        server.takeRequest()
        lookup.cancelAndJoin()
        assertNull("no result, so no failure message on screen", result)
        assertNull("no status: the button stays", g.dao.get("c1")!!.enrichmentStatus)
    }
}
