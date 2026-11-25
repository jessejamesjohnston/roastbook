package dev.roastbook.app.cloud

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GeminiClientTest {
    private val server = MockWebServer()
    private val client = GeminiClient()

    @Before fun setUp() { server.start(); client.baseUrl = server.url("/").toString().trimEnd('/') }
    @After fun tearDown() { server.shutdown() }

    @Test fun keyTravelsInAHeaderAndTextComesBack() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"roaster\":\"X\"}"}],"role":"model"}}]}"""))
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = client.generate("sk-test", "gemini-test", listOf(bmp), "prompt")
        assertEquals("""{"roaster":"X"}""", out)
        val req = server.takeRequest()
        assertEquals("sk-test", req.getHeader("x-goog-api-key"))
        assertFalse(req.path!!.contains("sk-test"))
        assertTrue(req.path!!.endsWith("/v1beta/models/gemini-test:generateContent"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"inline_data\""))
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
    }

    @Test fun groundedRequestCarriesTheSearchToolAndNoJsonMime() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"a"},{"text":"b"}]}}]}"""))
        val out = client.generate("k", "m", emptyList(), "p", grounded = true, jsonOut = false)
        assertEquals("a\nb", out)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"google_search\""))
        assertFalse(body.contains("responseMimeType"))
    }

    @Test fun apiErrorsSurfaceWithTheirMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""))
        try { client.generate("bad", "m", emptyList(), "p"); fail("expected") } catch (e: GeminiRequestException) {
            assertEquals(400, e.code)
            assertTrue(e.message!!.contains("API key not valid"))
        }
    }

    @Test fun checkKeyUsesTheModelEndpoint() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"models/m"}"""))
        assertTrue(client.checkKey("k", "m").isSuccess)
        assertTrue(server.takeRequest().path!!.endsWith("/v1beta/models/m"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        assertTrue(client.checkKey("k", "m").isFailure)
    }

    @Test fun groundingRefusedFallsBackToPlainModelAndSaysSo() = runTest {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, dev.roastbook.app.data.db.RoastbookDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.coffeeDao().insert(dev.roastbook.app.data.db.CoffeeEntity("e1", 1, 1, 1, dev.roastbook.app.data.db.CoffeeState.LOGGED, roaster = "Larkmoor Coffee", coffeeName = "Hills of Lumen"))
            val secrets = object : SecretStore {
                override val hasGeminiKey = kotlinx.coroutines.flow.MutableStateFlow(true)
                override suspend fun geminiKey() = "k"
                override suspend fun setGeminiKey(key: String?) {}
            }
            val prefs = dev.roastbook.app.data.prefs.UserPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { java.io.File(context.filesDir, "t-${java.util.UUID.randomUUID()}.preferences_pb") })
            val enricher = Enricher(db.coffeeDao(), client, secrets, prefs)
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"You exceeded your current quota"}}"""))
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"roaster_url\":\"https://larkmoor.example\",\"product_url\":null,\"roaster_city\":\"Nashville, TN\"}"}]}}]}"""))
            val r = enricher.lookUp("e1")
            assertTrue(r is LookupResult.Found)
            val c = db.coffeeDao().get("e1")!!
            assertEquals("https://larkmoor.example", c.roasterUrl)
            assertEquals(Enricher.STATUS_SUGGESTED_UNVERIFIED, c.enrichmentStatus)
            assertTrue(server.takeRequest().body.readUtf8().contains("google_search"))
            assertFalse(server.takeRequest().body.readUtf8().contains("google_search"))
            // A hard failure on both stays a Failed result and leaves status alone.
            enricher.reset("e1")
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            assertTrue(enricher.lookUp("e1") is LookupResult.Failed)
            assertNull(db.coffeeDao().get("e1")!!.enrichmentStatus)
        } finally { db.close() }
    }

    // --- The key never reaches a message, a log or a second request it shouldn't ---

    @Test fun aKeyWithAnInvisibleCharacterIsRefusedBeforeAnyHeaderOrMessageHoldsIt() = runTest {
        val pasted = "FAKEFAKEFAKEFAKE\u200Bxyz123"
        val r = client.checkKey(pasted, "m")
        assertTrue(r.isFailure)
        assertFalse("the key is not in the message", r.exceptionOrNull()!!.message!!.contains("FAKEFAKE"))
        assertFalse(safeFailureReason(r.exceptionOrNull(), pasted).contains("FAKEFAKE"))
        assertEquals("nothing was sent", 0, server.requestCount)
    }

    @Test fun aPastedKeyKeepsOnlyItsKeyCharacters() {
        assertEquals("FAKExy-_9", cleanApiKey(" FAKE\u200Bx y\n-_9\u00A0"))
        assertNull(cleanApiKey(" \u200B "))
        assertNull(cleanApiKey(null))
    }

    private fun lookupHarness(roaster: String = "Larkmoor Coffee", keys: () -> String? = { "k" }, body: suspend (Enricher, dev.roastbook.app.data.db.RoastbookDatabase) -> Unit) = runTest {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, dev.roastbook.app.data.db.RoastbookDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.coffeeDao().insert(dev.roastbook.app.data.db.CoffeeEntity("e1", 1, 1, 1, dev.roastbook.app.data.db.CoffeeState.LOGGED, roaster = roaster, coffeeName = "Hills of Lumen"))
            val secrets = object : SecretStore {
                override val hasGeminiKey = kotlinx.coroutines.flow.MutableStateFlow(true)
                override suspend fun geminiKey() = keys()
                override suspend fun setGeminiKey(key: String?) {}
            }
            val prefs = dev.roastbook.app.data.prefs.UserPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { java.io.File(context.filesDir, "t-${java.util.UUID.randomUUID()}.preferences_pb") })
            body(Enricher(db.coffeeDao(), client, secrets, prefs), db)
        } finally { db.close() }
    }

    private val linkAnswer = """{"roaster_url":"https://larkmoor.example","product_url":null,"roaster_city":"Nashville, TN"}"""
    private fun answer(text: String, searched: Boolean) = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}]}""" +
            (if (searched) ""","groundingMetadata":{"webSearchQueries":["larkmoor coffee"],"groundingChunks":[{"web":{"uri":"https://x"}}]}""" else "") + "}]}",
    )

    @Test fun aGroundedAnswerWithoutSearchEvidenceIsMarkedUnverified() = lookupHarness { enricher, db ->
        server.enqueue(answer(linkAnswer, searched = false))
        assertTrue(enricher.lookUp("e1") is LookupResult.Found)
        assertEquals(Enricher.STATUS_SUGGESTED_UNVERIFIED, db.coffeeDao().get("e1")!!.enrichmentStatus)
        enricher.reset("e1")
        server.enqueue(answer(linkAnswer, searched = true))
        assertTrue(enricher.lookUp("e1") is LookupResult.Found)
        assertEquals(Enricher.STATUS_SUGGESTED, db.coffeeDao().get("e1")!!.enrichmentStatus)
    }

    @Test fun aLinkTypedDuringTheLookupIsKept() = lookupHarness { enricher, db ->
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                // The user types the roaster's site while the lookup is out.
                kotlinx.coroutines.runBlocking { db.coffeeDao().mutate("e1") { it.copy(roasterUrl = "https://typed.example") } }
                return answer(linkAnswer, searched = true)
            }
        }
        enricher.lookUp("e1")
        val c = db.coffeeDao().get("e1")!!
        assertEquals("https://typed.example", c.roasterUrl)
        assertEquals("Nashville, TN", c.roasterCity)
    }

    @Test fun anAnswerAboutARenamedRoasterIsDropped() = lookupHarness { enricher, db ->
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                kotlinx.coroutines.runBlocking { db.coffeeDao().mutate("e1") { it.copy(roaster = "Another Roaster") } }
                return answer(linkAnswer, searched = true)
            }
        }
        assertTrue(enricher.lookUp("e1") is LookupResult.Skipped)
        val c = db.coffeeDao().get("e1")!!
        assertNull(c.roasterUrl)
        assertNull(c.enrichmentStatus)
    }

    @Test fun aKeyRemovedDuringTheLookupSendsNoFallbackRequest() {
        var calls = 0
        lookupHarness(keys = { if (calls++ == 0) "k" else null }) { enricher, _ ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota"}}"""))
            assertTrue(enricher.lookUp("e1") is LookupResult.Skipped)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun enrichmentParserIsTolerant() {
        val e = Enricher.parse("Sure! ```json\n{\"roaster_url\": \"https://example.com\", \"product_url\": null, \"roaster_city\": \"Atlanta, GA\"}\n```")!!
        assertEquals("https://example.com", e.roasterUrl)
        assertNull(e.productUrl)
        assertEquals("Atlanta, GA", e.roasterCity)
        assertTrue("an answer in words with no object found nothing", Enricher.parse("I could not find it.")!!.isEmpty)
        assertNull(Enricher.parse("""{"roaster_url":"not a url"}""")!!.roasterUrl)
        assertNull("no answer at all", Enricher.parse(" "))
        assertNull("an object cut off", Enricher.parse("""{"roaster_url": "https://exam"""))
        assertNull("a broken object", Enricher.parse("""{"roaster_url": https://example.com}"""))
    }
}
