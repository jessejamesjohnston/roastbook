package dev.roastbook.app.extraction

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.cloud.Enricher
import dev.roastbook.app.cloud.GeminiClient
import dev.roastbook.app.cloud.GeminiCloudTier
import dev.roastbook.app.cloud.LookupResult
import dev.roastbook.app.cloud.SecretStore
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.prefs.UserPrefs
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The roaster lookup is tap-only: with a key set, a cloud read (foreground or background) sends one
 * request, the read itself, and nothing else; the grounded lookup goes out only when the Detail
 * button calls [Enricher.lookUp]. Real cloud tier and HTTP client against a local server, so any
 * extra network call would show up as an extra request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TapOnlyLookupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val client = GeminiClient()
    private lateinit var db: RoastbookDatabase
    private lateinit var photos: PhotoStore

    private val secrets = object : SecretStore {
        override val hasGeminiKey = MutableStateFlow(true)
        override suspend fun geminiKey() = "test-key"
        override suspend fun setGeminiKey(key: String?) {}
    }
    private val noNano = object : NanoTier {
        override suspend fun status() = NanoStatus.UNAVAILABLE
        override suspend fun prepare() {}
        override suspend fun generate(images: List<Bitmap>, prompt: String) = error("unused")
        override fun modelName() = "none"
    }
    private val noLocal = object : LocalTier {
        override suspend fun availability() = LocalAvailability.UNSUPPORTED
        override suspend fun generate(images: List<Bitmap>, prompt: String) = error("unused")
        override fun modelName() = "none"
    }
    private val ocr = object : OcrTier { override suspend fun recognize(image: Bitmap) = "LARKMOOR COFFEE  HILLS OF LUMEN  WASHED" }
    private val health = object : NanoHealth {
        override suspend fun nanoFailing() = true
        override suspend fun recordNano(ok: Boolean, reason: String?) {}
    }

    private fun prefs() = UserPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "t-${java.util.UUID.randomUUID()}.preferences_pb") })

    @Before fun setUp() {
        server.start()
        client.baseUrl = server.url("/").toString().trimEnd('/')
        db = Room.inMemoryDatabaseBuilder(context, RoastbookDatabase::class.java).allowMainThreadQueries().build()
        photos = object : PhotoStore(context) {
            override suspend fun load(relative: String, maxEdge: Int): Bitmap? =
                if (file(relative).exists()) Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) else null
        }
    }
    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun seed(id: String) {
        db.coffeeDao().insert(CoffeeEntity(id, 1, 1, 1, CoffeeState.EXTRACTING))
        File(photos.dir(id), "p1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity("p1-$id", id, PhotoRole.FRONT, 0, "photos/$id/p1.jpg", takenAt = 1, addedAt = 1)))
    }

    private fun answer(text: String) = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}],"role":"model"}}]}""",
    )

    @Test fun aCloudReadWithAKeyNeverStartsALookupUntilTheTap() = runBlocking {
        val p = prefs()
        val extractor = Extractor(db, photos, noNano, ocr, GeminiCloudTier(client, secrets, p), noLocal, health, { 1_000_000L }, ExtractionTimeouts(cloudMs = 5_000))
        val readPass = ReadPass(db.coffeeDao(), extractor)
        seed("fg"); seed("bg")
        repeat(2) { server.enqueue(answer("""{"roaster":"Larkmoor Coffee","coffee_name":"Hills of Lumen"}""")) }

        val fg = readPass.readIfDue("fg", RunMode.FOREGROUND)
        val bg = readPass.readIfDue("bg", RunMode.BACKGROUND)
        assertEquals(ExtractionSource.CLOUD, fg?.source)
        assertEquals(ExtractionSource.CLOUD, bg?.source)

        // Two reads, two requests: the reads themselves. No grounded lookup went out.
        assertEquals(2, server.requestCount)
        repeat(2) { assertFalse(server.takeRequest().body.readUtf8().contains("google_search")) }
        for (id in listOf("fg", "bg")) {
            val c = db.coffeeDao().get(id)!!
            assertEquals("Larkmoor Coffee", c.roaster)
            assertNull(c.enrichmentStatus)
            assertNull(c.roasterUrl)
        }

        // The tap: now, and only now, the grounded lookup is sent.
        server.enqueue(answer("""{"roaster_url":"https://larkmoor.example","product_url":null,"roaster_city":"Nashville, TN"}"""))
        val r = Enricher(db.coffeeDao(), client, secrets, p).lookUp("fg")
        assertTrue(r is LookupResult.Found)
        assertEquals(3, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("google_search"))
        assertEquals("https://larkmoor.example", db.coffeeDao().get("fg")!!.roasterUrl)
        assertNull(db.coffeeDao().get("bg")!!.enrichmentStatus)
    }
}
