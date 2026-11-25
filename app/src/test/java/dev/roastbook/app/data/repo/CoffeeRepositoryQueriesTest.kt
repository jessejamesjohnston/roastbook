package dev.roastbook.app.data.repo

import android.graphics.Bitmap
import androidx.paging.testing.asSnapshot
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.vision.CropResult
import dev.roastbook.app.vision.HeroProcessor
import dev.roastbook.app.vision.SubjectCropper
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** What the screens read through the repository, plus delete, same-coffee, and the hero retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CoffeeRepositoryQueriesTest {
    private lateinit var g: TestGraph
    private val repo get() = g.repo

    @Before fun setUp() { g = TestGraph() }
    @After fun tearDown() { g.close() }

    private fun photo(id: String, coffeeId: String, thumb: String? = null, position: Int = 0) =
        CoffeePhotoEntity(id, coffeeId, PhotoRole.FRONT, position, "photos/$coffeeId/$id.jpg", thumb = thumb, takenAt = 1, addedAt = 1)

    @Test fun historyListsEachCoffeeWithItsThumbAndPhotoCount() = runBlocking {
        g.insertCoffee("old", CoffeeState.RATED, at = 1_000) {
            copy(roaster = "Northwind", coffeeName = "Finca Veleta", tastingNotes = listOf("Plum", "Cocoa"), overall = 4.5f, buyAgain = true, heroPhotoId = "h", tintLight = 0x112233)
        }
        g.insertCoffee("new", CoffeeState.LOGGED, at = 2_000) { copy(roaster = "Larkmoor") }
        g.dao.insertPhotos(listOf(photo("h", "old", thumb = "photos/old/h.thumb.jpg"), photo("b", "old", position = 1)))

        val rows = repo.history(HistorySort.RECENT, HistoryFilter(), descending = true).asSnapshot()
        assertEquals(listOf("new", "old"), rows.map { it.id })
        val old = rows.last()
        assertEquals("Finca Veleta", old.coffeeName)
        assertEquals(listOf("Plum", "Cocoa"), old.tastingNotes)
        assertEquals(4.5f, old.overall)
        assertTrue(old.buyAgain)
        assertEquals("photos/old/h.thumb.jpg", old.heroThumb)
        assertEquals(2, old.photoCount)
        assertEquals(0x112233, old.tintLight)
        assertEquals(0, rows.first().photoCount)
    }

    @Test fun countsAndPickerListsComeFromTheLibrary() = runBlocking {
        g.insertCoffee("a", CoffeeState.LOGGED) { copy(roaster = "Stillwick Roasters", originCountry = "Kenya", process = "Washed") }
        g.insertCoffee("b", CoffeeState.RATED) { copy(roaster = "Larkmoor", originCountry = "Ethiopia", process = "Natural", overall = 3f) }
        g.insertCoffee("c", CoffeeState.EXTRACTING)
        repo.ensureSource("Corner Market")

        assertEquals(3, repo.count().first())
        assertEquals(1, repo.unratedCount().first())
        assertEquals(listOf("Larkmoor", "Stillwick Roasters"), repo.roasters().first())
        assertEquals(listOf("Ethiopia", "Kenya"), repo.origins().first())
        assertEquals(listOf("Natural", "Washed"), repo.processes().first())
        assertTrue(repo.sources().first().any { it.name == "Corner Market" })
        assertEquals(listOf("c"), repo.extractingIds().first())
        assertTrue(repo.hasPendingReads())
    }

    @Test fun noPendingReadsOnceEveryBagIsRead() = runBlocking {
        g.insertCoffee("a", CoffeeState.LOGGED)
        assertFalse(repo.hasPendingReads())
    }

    @Test fun oneCoffeeAndItsPhotosAreReadAndObserved() = runBlocking {
        g.insertCoffee("a") { copy(roaster = "Sable & Rye") }
        g.dao.insertPhotos(listOf(photo("p1", "a")))
        assertEquals("Sable & Rye", repo.get("a")!!.roaster)
        assertEquals("Sable & Rye", repo.observe("a").first()!!.roaster)
        assertEquals(listOf("p1"), repo.photos("a").map { it.id })
        assertEquals(listOf("p1"), repo.observePhotos("a").first().map { it.id })
        assertNull(repo.get("missing"))
    }

    @Test fun updatingAPhotoStoresItsRenditions() = runBlocking {
        g.insertCoffee("a")
        g.dao.insertPhotos(listOf(photo("p1", "a")))
        repo.updatePhoto(repo.photos("a").single().copy(square = "photos/a/p1.square.jpg"))
        assertEquals("photos/a/p1.square.jpg", repo.photos("a").single().square)
    }

    @Test fun deletingACoffeeRemovesItsRowAndItsPhotoFolder() = runBlocking {
        g.insertCoffee("a")
        val file = File(g.photos.dir("a"), "p1.jpg").apply { writeBytes(byteArrayOf(1)) }
        repo.delete("a")
        assertNull(g.dao.get("a"))
        assertFalse(file.exists())
    }

    @Test fun sameCoffeeFindsEarlierBagsOfTheSameRoasterAndName() = runBlocking {
        val me = g.insertCoffee("me") { copy(roaster = "Larkmoor", coffeeName = "Hills of Lumen") }
        g.insertCoffee("before", at = 500) { copy(roaster = "Larkmoor", coffeeName = "Hills of Lumen") }
        g.insertCoffee("other", at = 600) { copy(roaster = "Larkmoor", coffeeName = "Late Lamp") }
        assertEquals(listOf("before"), repo.sameCoffee(me).map { it.id })
        assertTrue("no name, no match", repo.sameCoffee(me.copy(coffeeName = null)).isEmpty())
        assertTrue("no roaster, no match", repo.sameCoffee(me.copy(roaster = null)).isEmpty())
    }

    // --- hero retries ---

    private object BrokenCropper : SubjectCropper {
        override suspend fun crop(bitmap: Bitmap): CropResult = throw IllegalStateException("segmentation crashed")
    }

    /** A repository whose hero crop always throws, over photos that do load. */
    private fun repoWithBrokenCrop(): CoffeeRepository {
        val photos = dev.roastbook.app.data.photo.PhotoStore(g.context)
        val hero = HeroProcessor(g.dao, photos, BrokenCropper, TestGraph.NeutralPalette, TestGraph.NoExport)
        return CoffeeRepository(g.context, g.dao, photos, hero, g.reminders, g.userPrefs)
    }

    private suspend fun coffeeWithHero(id: String, state: CoffeeState = CoffeeState.LOGGED) {
        g.insertCoffee(id, state) { copy(heroPhotoId = "p-$id") }
        g.stagedJpeg().copyTo(File(g.photos.dir(id), "p-$id.jpg"))
        g.dao.insertPhotos(listOf(photo("p-$id", id)))
    }

    @Test fun aHeroRetryThatFailsCountsAnAttemptAndTheNextCoffeeIsStillTried() = runBlocking {
        coffeeWithHero("a")
        coffeeWithHero("b")
        repoWithBrokenCrop().retryHeroes()
        assertEquals(1, g.dao.get("a")!!.heroAttempts)
        assertEquals(1, g.dao.get("b")!!.heroAttempts)
    }

    @Test fun rereadQueuesTheReadEvenWhenTheHeroCropFails() = runBlocking {
        coffeeWithHero("a")
        repoWithBrokenCrop().reextract("a")
        val c = g.dao.get("a")!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(1, c.heroAttempts)
    }

    // --- the rate-it reminder ---

    private val alarms get() = org.robolectric.Shadows.shadowOf(g.context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms

    @Test fun clearingTheReminderDisarmsItsAlarm() = runBlocking {
        g.insertCoffee("a") { copy(roaster = "Larkmoor") }
        val at = System.currentTimeMillis() + 86_400_000L
        repo.setReminder("a", at)
        assertEquals(listOf(at), alarms.map { it.triggerAtMs })
        repo.setReminder("a", null)
        assertTrue(alarms.isEmpty())
        assertNull(g.dao.get("a")!!.remindAt)
    }

    @Test fun aReminderForACoffeeThatIsGoneArmsNothing() = runBlocking {
        repo.setReminder("deleted", System.currentTimeMillis() + 86_400_000L)
        assertTrue(alarms.isEmpty())
    }
}
