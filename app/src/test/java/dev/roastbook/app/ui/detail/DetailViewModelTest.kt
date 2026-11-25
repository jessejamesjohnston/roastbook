package dev.roastbook.app.ui.detail

import dev.roastbook.app.cloud.Enricher
import dev.roastbook.app.cloud.LookupResult
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.ui.rating.RatingHandoff
import dev.roastbook.app.ui.rating.RatingDraft
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the coffee page's ViewModel writes and reports, without the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DetailViewModelTest {
    private var fixture: DetailFixture? = null
    private val jobs = mutableListOf<Job>()
    private val server = MockWebServer()

    private fun fixture(key: String? = null, eligible: Boolean = false) = DetailFixture(key, eligible).also { fixture = it }

    /** Keeps a WhileSubscribed flow running, as the screen's collector would. */
    private fun subscribe(flow: Flow<*>) { jobs += MainScope().launch { flow.collect {} } }

    @After fun tearDown() {
        jobs.forEach { it.cancel() }
        fixture?.close()
        server.shutdown()
    }

    private fun row(f: DetailFixture, id: String = "c1") = runBlocking { f.graph.dao.get(id) }

    @Test fun aHandedOverRatingIsTakenOnceAndOnlyByItsOwnCoffee() {
        val f = fixture()
        val draft = RatingDraft(aroma = 4, flavor = 3)
        f.pending.ratingDraft.value = RatingHandoff("other", draft)
        assertNull(f.vm("c1").takeRatingHandoff())
        assertEquals(RatingHandoff("other", draft), f.pending.ratingDraft.value)

        f.pending.ratingDraft.value = RatingHandoff("c1", draft)
        val vm = f.vm("c1")
        assertEquals(draft, vm.takeRatingHandoff())
        assertNull(f.pending.ratingDraft.value)
        assertNull(vm.takeRatingHandoff())
    }

    @Test fun savingARatingWritesTheScoresAndTheOverall() {
        val f = fixture()
        runBlocking { f.graph.insertCoffee("c1") }
        f.vm("c1").saveRating(RatingDraft(aroma = 5, flavor = 4, acidity = 2, body = 3, finish = 3, buyAgain = true, notes = "plum"))
        idleUntil { row(f)?.overall != null }
        val r = row(f)!!
        assertEquals(4f, r.overall)
        assertEquals(listOf(5, 4, 3, 2, 3), listOf(r.aroma, r.flavor, r.finish, r.acidity, r.body))
        assertTrue(r.buyAgain)
        assertEquals("plum", r.notes)
    }

    @Test fun anEditIsWrittenAndRememberedAsTheUsers() {
        val f = fixture()
        runBlocking { f.graph.insertCoffee("c1") { copy(roaster = "Northwind") } }
        f.vm("c1").edit { it.copy(roaster = "Larkmoor") }
        idleUntil { row(f)?.roaster == "Larkmoor" }
        assertTrue("roaster" in row(f)!!.userEdited)
    }

    @Test fun aSourceIsSetByNameAndABlankNameClearsIt() {
        val f = fixture()
        runBlocking { f.graph.insertCoffee("c1") }
        val vm = f.vm("c1")
        vm.setSource("Sable & Rye")
        idleUntil { row(f)?.sourceId != null }
        val source = runBlocking { f.graph.dao.sourceByName("Sable & Rye") }
        assertEquals(source!!.id, row(f)!!.sourceId)
        vm.setSource(" ")
        idleUntil { row(f)?.sourceId == null }
    }

    @Test fun closingTheNoteHidesItUntilTheCoffeeIsReadAgain() {
        val f = fixture()
        runBlocking { f.graph.insertCoffee("c1") }
        val vm = f.vm("c1")
        assertFalse(vm.noteDismissed.value)
        vm.dismissNote()
        idleUntil { vm.noteDismissed.value }
        // A page opened later starts with the note closed.
        assertTrue(f.vm("c1").noteDismissed.value)

        vm.reextract()
        idleUntil { !vm.noteDismissed.value && row(f)?.state == CoffeeState.EXTRACTING }
    }

    @Test fun deletingRemovesTheRowThenLeaves() {
        val f = fixture()
        runBlocking { f.graph.insertCoffee("c1") }
        var left = false
        f.vm("c1").delete { left = true }
        idleUntil { left }
        assertNull(row(f))
    }

    @Test fun theReaderIsOfferedOnlyWhereItCouldRun() {
        // What a small device's reader actually reports, read from the flow itself rather than
        // waited for: the page's flow starts at "not offered", so a wait could not tell.
        val small = fixture()
        val reported = runBlocking { small.reader.state.first() }
        assertNotEquals(dev.roastbook.app.reader.Eligibility.ELIGIBLE, reported.eligibility)
        assertFalse(DetailViewModel.offersReader(reported))
        small.close(); fixture = null

        val big = fixture(eligible = true)
        val vm = big.vm("c1")
        subscribe(vm.readerOffered)
        idleUntil { vm.readerOffered.value }

        // Once the model is in place the page stops offering it: an observed change, not a wait.
        // The model file is sparse, so its full size costs no disk.
        val store = big.reader.store
        store.ensureDirs()
        java.io.RandomAccessFile(store.part, "rw").use { it.setLength(big.reader.spec.sizeBytes) }
        store.promote(store.part, big.reader.spec.sha256)
        big.reader.onDiskChanged()
        idleUntil { !vm.readerOffered.value }
    }

    @Test fun aFailingDeviceAiIsReported() {
        val f = fixture()
        val vm = f.vm("c1")
        subscribe(vm.nanoFailing)
        assertFalse(vm.nanoFailing.value)
        runBlocking { f.readerPrefs.recordNano(ok = false, reason = "timeout") }
        idleUntil { vm.nanoFailing.value }
    }

    @Test fun withoutAKeyALookupSendsNothing() {
        val f = fixture()
        server.start()
        f.client.baseUrl = server.url("/").toString().trimEnd('/')
        runBlocking { f.graph.insertCoffee("c1") { copy(roaster = "Northwind") } }
        val vm = f.vm("c1")
        assertFalse(vm.hasCloudKey.value)
        vm.lookUpRoaster()
        idleUntil { vm.lookupOutcome.value != null }
        assertEquals(LookupResult.Skipped, vm.lookupOutcome.value)
        assertFalse(vm.lookingUp.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun aLookupLandsSuggestionsAndWrongRoasterClearsThem() {
        val f = fixture(key = "FAKETestKey123")
        server.enqueue(MockResponse().setBody(
            """{"candidates":[{"content":{"parts":[{"text":"{\"roaster_url\":\"https://northwind.example\",\"product_url\":null,\"roaster_city\":\"Portland, OR\"}"}]},""" +
                """"groundingMetadata":{"webSearchQueries":["northwind coffee"]}}]}""",
        ))
        server.start()
        f.client.baseUrl = server.url("/").toString().trimEnd('/')
        runBlocking { f.graph.insertCoffee("c1") { copy(roaster = "Northwind") } }
        val vm = f.vm("c1")
        assertTrue(vm.hasCloudKey.value)
        vm.lookUpRoaster()
        idleUntil { vm.lookupOutcome.value != null }
        assertEquals(LookupResult.Found, vm.lookupOutcome.value)
        assertFalse(vm.lookingUp.value)
        val r = row(f)!!
        assertEquals("https://northwind.example", r.roasterUrl)
        assertEquals("Portland, OR", r.roasterCity)
        assertEquals(Enricher.STATUS_SUGGESTED, r.enrichmentStatus)

        vm.wrongRoaster()
        idleUntil { vm.lookupOutcome.value == null }
        assertNull(row(f)!!.enrichmentStatus)
        assertNull(row(f)!!.roasterUrl)
    }
}
