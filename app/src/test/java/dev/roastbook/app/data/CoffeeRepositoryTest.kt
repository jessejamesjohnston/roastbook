package dev.roastbook.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.color.RowColors
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.HistoryQuery
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.data.repo.NewPhoto
import dev.roastbook.app.reminders.ReminderScheduler
import dev.roastbook.app.vision.CropResult
import dev.roastbook.app.vision.HeroProcessor
import dev.roastbook.app.vision.PaletteExtractor
import dev.roastbook.app.vision.SubjectCropper
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Repository invariants: sources, drafts, orphan sweep, reminders, search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CoffeeRepositoryTest {
    private lateinit var db: RoastbookDatabase
    private lateinit var photos: PhotoStore
    private lateinit var repo: CoffeeRepository
    private lateinit var reminders: ReminderScheduler
    private lateinit var userPrefs: dev.roastbook.app.data.prefs.UserPrefs
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private object NoCrop : SubjectCropper {
        override suspend fun crop(bitmap: Bitmap) = CropResult(Rect(0, 0, bitmap.width, bitmap.height), null, false)
    }
    private object FlatPalette : PaletteExtractor {
        override fun extract(subject: Bitmap) = RowColors(0, 0, 0, 0, fallback = true)
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RoastbookDatabase::class.java).allowMainThreadQueries().build()
        photos = object : PhotoStore(context) {
            override suspend fun load(relative: String, maxEdge: Int): Bitmap? = null // hero work is not under test here
        }
        reminders = ReminderScheduler(context, db.coffeeDao())
        userPrefs = dev.roastbook.app.data.prefs.UserPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "test-${java.util.UUID.randomUUID()}.preferences_pb") })
        val noExport = object : dev.roastbook.app.data.photo.SquareExporter { override suspend fun maybeExport(square: File, displayName: String) {} }
        repo = CoffeeRepository(context, db.coffeeDao(), photos, HeroProcessor(db.coffeeDao(), photos, NoCrop, FlatPalette, noExport), reminders, userPrefs)
        photos.root.deleteRecursively()
    }
    @After fun tearDown() { db.close(); photos.root.deleteRecursively() }

    private suspend fun insert(id: String, state: CoffeeState, createdAt: Long = 1_000, roaster: String? = null, remindAt: Long? = null) {
        db.coffeeDao().insert(CoffeeEntity(id, createdAt, createdAt, createdAt, state, roaster = roaster, remindAt = remindAt))
    }
    private fun dirWithPhoto(id: String): File = File(photos.dir(id), "p.jpg").apply { writeBytes(byteArrayOf(1)) }

    // --- A failed import keeps its photos for another try ---

    private fun stagedPhoto(name: String): File = File(context.cacheDir, name).apply {
        outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test fun aFailedImportLeavesNoRowAndKeepsTheStagedPhotosForTheRetry() = runTest {
        val first = stagedPhoto("first.jpg")
        val unreadable = File(context.cacheDir, "never-written.jpg").also { it.delete() }
        val failed = runCatching { repo.create(listOf(NewPhoto(first, PhotoRole.FRONT), NewPhoto(unreadable, PhotoRole.BACK))) }
        assertTrue(failed.isFailure)
        assertTrue("the first photo is still staged", first.exists())
        assertTrue("no half-made coffee", db.coffeeDao().allIds().isEmpty())
        assertTrue("no imported copies left behind", photos.root.listFiles().isNullOrEmpty())

        val second = stagedPhoto("second.jpg")
        val id = repo.create(listOf(NewPhoto(first, PhotoRole.FRONT), NewPhoto(second, PhotoRole.BACK)))
        assertEquals(2, db.coffeeDao().photos(id).size)
        assertFalse("staged copies go once the coffee is stored", first.exists() || second.exists())
    }

    // --- Clearing a value sticks ---

    @Test fun clearingProfileAndNotesInARatingSticks() = runTest {
        insert("r1", CoffeeState.LOGGED)
        repo.rate("r1", 4, 4, 4, acidity = 3, body = 2, buyAgain = true, notes = "bright")
        repo.rate("r1", 4, 4, 4, acidity = null, body = null, buyAgain = true, notes = "")
        val c = db.coffeeDao().get("r1")!!
        assertNull(c.acidity); assertNull(c.body); assertNull(c.notes)
    }

    @Test fun clearingTheSourceSticks() = runTest {
        insert("s9", CoffeeState.LOGGED)
        repo.setSource("s9", "Trade")
        assertNotNull(db.coffeeDao().get("s9")!!.sourceId)
        repo.setSource("s9", null)
        assertNull(db.coffeeDao().get("s9")!!.sourceId)
    }

    @Test fun theFirstRatingStoresItsSecondLookInTheSameWrite() = runTest {
        insert("f1", CoffeeState.LOGGED)
        repo.rate("f1", 4, 4, 4, null, null, buyAgain = false, notes = null)
        val c = db.coffeeDao().get("f1")!!
        assertNotNull(c.ratedAt); assertNotNull(c.secondLookAt)
    }

    // --- Sources: one per name, whatever its spacing or case ---

    @Test fun ensureSourceIgnoresWhitespaceAndCase() = runTest {
        val first = repo.ensureSource("Trade")
        assertEquals(first.id, repo.ensureSource(" Trade ").id)
        assertEquals(first.id, repo.ensureSource("trade").id)
        assertEquals("Trade", first.name)
    }

    // --- "Where you bought it" seeds: new installs only ---

    @Test fun aNewInstallStartsWithTheGenericSources() = runTest {
        repo.startupMaintenance()
        repo.startupMaintenance() // every start runs it; the second run adds nothing
        val names = db.coffeeDao().allSources().map { it.name }.sorted()
        assertEquals(listOf("Gift", "Local café", "Roaster direct", "Subscription"), names)
        assertTrue(db.coffeeDao().allSources().all { it.isSeed && it.id.startsWith("seed:") })
    }

    @Test fun anExistingListAndTheSourcesOnExistingCoffeesAreNeverRewritten() = runTest {
        // A journal from an earlier version: its old seeds, one typed by the user, a coffee using an old seed.
        val old = listOf(
            dev.roastbook.app.data.db.SourceEntity("seed:tasting-club", "Tasting Club", isSeed = true, createdAt = 1),
            dev.roastbook.app.data.db.SourceEntity("seed:trade", "Trade", isSeed = true, createdAt = 1),
            dev.roastbook.app.data.db.SourceEntity("seed:roaster-direct", "Roaster direct", isSeed = true, createdAt = 1),
            dev.roastbook.app.data.db.SourceEntity("u1", "Corner shop", isSeed = false, createdAt = 2),
        )
        db.coffeeDao().insertSources(old)
        db.coffeeDao().insert(CoffeeEntity("k1", 1_000, 1_000, 1_000, CoffeeState.LOGGED, sourceId = "seed:tasting-club"))
        db.coffeeDao().insert(CoffeeEntity("k2", 1_000, 1_000, 1_000, CoffeeState.LOGGED, sourceId = "u1"))
        val before = db.coffeeDao().get("k1")!!

        repo.startupMaintenance(now = 1_000 + 120_000)

        assertEquals(old.sortedBy { it.id }, db.coffeeDao().allSources().sortedBy { it.id })
        assertEquals("no Subscription added to an existing list", null, db.coffeeDao().sourceByName("Subscription"))
        assertEquals("seed:tasting-club", db.coffeeDao().get("k1")!!.sourceId)
        assertEquals(before, db.coffeeDao().get("k1"))
        assertEquals("u1", db.coffeeDao().get("k2")!!.sourceId)
    }

    // --- Abandoned drafts and orphaned photo folders ---

    @Test fun oldEmptyDraftIsRemovedAtStartup() = runTest {
        insert("d1", CoffeeState.DRAFT, createdAt = 1_000)
        val dir = photos.dir("d1")
        repo.startupMaintenance(now = 1_000 + 120_000)
        assertNull(db.coffeeDao().get("d1"))
        assertFalse(dir.exists())
    }

    @Test fun oldDraftWithPhotosResumesAsExtracting() = runTest {
        insert("d2", CoffeeState.DRAFT, createdAt = 1_000)
        dirWithPhoto("d2")
        db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity("p2", "d2", PhotoRole.FRONT, 0, "photos/d2/p.jpg", takenAt = 1, addedAt = 1)))
        repo.startupMaintenance(now = 1_000 + 120_000)
        val c = db.coffeeDao().get("d2")!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals("p2", c.heroPhotoId)
    }

    @Test fun aDraftLeftByAKilledImportResumesEvenIfTheAppIsReopenedAtOnce() = runTest {
        // Reopened seconds after the import died: the row is young, but no import in this process
        // owns it, and nothing would look at it again until the next start.
        insert("d3", CoffeeState.DRAFT, createdAt = 5_000)
        dirWithPhoto("d3")
        db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity("p3", "d3", PhotoRole.FRONT, 0, "photos/d3/p.jpg", takenAt = 1, addedAt = 1)))
        repo.startupMaintenance(now = 5_000 + 10_000)
        assertEquals(CoffeeState.EXTRACTING, db.coffeeDao().get("d3")!!.state)
    }

    @Test fun anImportStillRunningInThisProcessIsLeftAlone() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        // Holds the import at its first photo (before any photo row exists) until the test lets go.
        val slow = object : File(stagedPhoto("slow.jpg").path) {
            override fun lastModified(): Long { entered.countDown(); gate.await(5, java.util.concurrent.TimeUnit.SECONDS); return super.lastModified() }
        }
        val import = async(kotlinx.coroutines.Dispatchers.Default) { repo.create(listOf(NewPhoto(slow, PhotoRole.FRONT))) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val draft = db.coffeeDao().drafts().single()
        // However old the row looks (a slow import, a clock change), maintenance leaves it to its import.
        repo.startupMaintenance(now = draft.createdAt + 120_000)
        assertEquals(CoffeeState.DRAFT, db.coffeeDao().get(draft.id)?.state)
        gate.countDown()
        val id = import.await()
        assertEquals(CoffeeState.EXTRACTING, db.coffeeDao().get(id)!!.state)
        assertEquals(1, db.coffeeDao().photos(id).size)
    }

    @Test fun orphanSweepDeletesOnlyDirectoriesWithoutARow() = runTest {
        insert("live", CoffeeState.LOGGED)
        val keep = dirWithPhoto("live")
        val orphan = dirWithPhoto("gone")
        repo.startupMaintenance()
        assertTrue(keep.exists())
        assertFalse(orphan.exists())
    }

    // --- Fired reminders ---

    @Test fun firedReminderIsConsumed() = runTest {
        insert("r1", CoffeeState.LOGGED, roaster = "R", remindAt = 42L)
        reminders.notify("r1")
        assertNull(db.coffeeDao().get("r1")!!.remindAt)
        assertTrue(db.coffeeDao().pendingReminders().isEmpty())
    }

    @Test fun setReminderTouchesOnlyItsColumn() = runTest {
        insert("r2", CoffeeState.LOGGED, roaster = "Before")
        repo.setReminder("r2", 99L)
        val c = db.coffeeDao().get("r2")!!
        assertEquals(99L, c.remindAt)
        assertEquals("Before", c.roaster)
    }

    // --- The per-row marker of hand-edited extracted fields ---

    @Test fun editRecordsOnlyTheExtractedFieldsItChanged() = runTest {
        insert("e1", CoffeeState.LOGGED, roaster = "Before")
        repo.edit("e1") { it.copy(roaster = "After", notes = "not an extracted field", priceUsd = 18f) }
        assertEquals(listOf("roaster"), db.coffeeDao().get("e1")!!.userEdited)
        repo.edit("e1") { it.copy(tastingNotes = listOf("Cherry"), roaster = "After again") }
        assertEquals(listOf("roaster", "tastingNotes"), db.coffeeDao().get("e1")!!.userEdited)
        repo.edit("e1") { it } // a no-op edit records nothing
        assertEquals(listOf("roaster", "tastingNotes"), db.coffeeDao().get("e1")!!.userEdited)
    }

    @Test fun ratingReminderAndSourceLeaveTheMarkerAlone() = runTest {
        insert("e2", CoffeeState.LOGGED, roaster = "Before")
        repo.edit("e2") { it.copy(roaster = "After") }
        repo.rate("e2", aroma = 4, flavor = 4, finish = 5, acidity = null, body = null, buyAgain = true, notes = "lovely")
        repo.setReminder("e2", 99L)
        repo.setSource("e2", "Trade")
        val c = db.coffeeDao().get("e2")!!
        assertEquals(listOf("roaster"), c.userEdited)
        assertEquals(CoffeeState.RATED, c.state)
        assertEquals(99L, c.remindAt)
    }

    /** Reread must not overwrite fields the user already edited, so the marker survives it. */
    @Test fun rereadKeepsTheMarker() = runTest {
        insert("e3", CoffeeState.LOGGED, roaster = "Before")
        repo.edit("e3") { it.copy(roaster = "After") }
        repo.reextract("e3")
        val c = db.coffeeDao().get("e3")!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(listOf("roaster"), c.userEdited)
        assertNull(c.nextAttemptAt)
        assertNull(c.extractionTrail)
    }

    @Test fun draftResumeClearsTheMarker() = runTest {
        db.coffeeDao().insert(CoffeeEntity("d4", 1_000, 1_000, 1_000, CoffeeState.DRAFT, userEdited = listOf("roaster")))
        dirWithPhoto("d4")
        db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity("p4", "d4", PhotoRole.FRONT, 0, "photos/d4/p.jpg", takenAt = 1, addedAt = 1)))
        repo.startupMaintenance(now = 1_000 + 120_000)
        val c = db.coffeeDao().get("d4")!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertTrue(c.userEdited.isEmpty())
    }

    // --- second look ---

    @Test fun firstRatingArmsOneSecondLook() = runTest {
        insert("s1", CoffeeState.LOGGED, roaster = "R")
        repo.rate("s1", 4, 4, 5, null, null, false, null)
        val c = db.coffeeDao().get("s1")!!
        val expected = c.ratedAt!! + 5L * 24 * 60 * 60 * 1000
        assertEquals(expected, c.secondLookAt)
        assertFalse(c.secondLookDone)
        // Re-rating does not move it.
        repo.rate("s1", 3, 3, 3, null, null, false, null)
        assertEquals(expected, db.coffeeDao().get("s1")!!.secondLookAt)
        // Firing consumes it for good. The alarm is due now: a broadcast that arrives before the
        // stored time is refused (see aSecondLookThatArrivesEarlyIsNotConsumed).
        db.coffeeDao().setSecondLook("s1", System.currentTimeMillis() - 1_000)
        reminders.notifySecondLook("s1")
        val after = db.coffeeDao().get("s1")!!
        assertTrue(after.secondLookDone)
        assertNull(after.secondLookAt)
        assertTrue(db.coffeeDao().pendingSecondLooks().isEmpty())
    }

    @Test fun secondLookRespectsTheSwitch() = runTest {
        userPrefs.setSecondLookEnabled(false)
        insert("s2", CoffeeState.LOGGED)
        repo.rate("s2", 4, 4, 4, null, null, false, null)
        assertNull(db.coffeeDao().get("s2")!!.secondLookAt)
    }

    // --- filters ---

    @Test fun pickerFiltersNarrowTheList() = runTest {
        db.coffeeDao().insert(CoffeeEntity("f1", 1, 1, 1, CoffeeState.LOGGED, roaster = "Alpha", originCountry = "Colombia", process = "Washed"))
        db.coffeeDao().insert(CoffeeEntity("f2", 2, 2, 2, CoffeeState.LOGGED, roaster = "Beta", originCountry = "Colombia", process = "Natural"))
        // Excluded only by the origin filter, so dropping that clause fails the first check.
        db.coffeeDao().insert(CoffeeEntity("f3", 3, 3, 3, CoffeeState.LOGGED, roaster = "Gamma", originCountry = "Ethiopia", process = "Honey"))
        fun ids(f: HistoryFilter): Set<String> {
            val q = HistoryQuery.build(HistorySort.RECENT, f, now = 100L * 24 * 60 * 60 * 1000) // day 100; rows are logged at ms 1 and 2
            val out = mutableSetOf<String>()
            db.query(SimpleSQLiteQuery(q.sql, q.argsForTest())).use { c -> while (c.moveToNext()) out += c.getString(0) }
            return out
        }
        assertEquals(setOf("f1", "f2"), ids(HistoryFilter(originCountry = "colombia")))
        assertEquals(setOf("f2"), ids(HistoryFilter(process = "Natural")))
        assertEquals(setOf("f1"), ids(HistoryFilter(roaster = "Alpha", originCountry = "Colombia")))
        assertEquals(emptySet<String>(), ids(HistoryFilter(dateRange = dev.roastbook.app.data.db.DateRange.DAYS_30)))
        assertEquals(setOf("f1", "f2", "f3"), ids(HistoryFilter(dateRange = dev.roastbook.app.data.db.DateRange.ANY)))
    }

    // --- Search ---

    private fun search(term: String): List<String> {
        val q = HistoryQuery.build(HistorySort.RECENT, HistoryFilter(search = term))
        val out = mutableListOf<String>()
        db.query(SimpleSQLiteQuery(q.sql, q.argsForTest())).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    @Test fun searchTreatsWildcardsLiterally() = runTest {
        insert("a", CoffeeState.LOGGED, roaster = "100% Arabica")
        insert("b", CoffeeState.LOGGED, roaster = "A_B Roasters")
        insert("c", CoffeeState.LOGGED, roaster = "AXB Roasters")
        insert("d", CoffeeState.LOGGED, roaster = "Back\\slash")
        assertEquals(listOf("b"), search("_"))
        assertEquals(listOf("a"), search("%"))
        assertEquals(listOf("d"), search("\\"))
        assertEquals(setOf("b", "c"), search("B Roast").toSet())
    }
    @Test fun editCannotOverwriteAConcurrentHeroResult() = kotlinx.coroutines.runBlocking {
        insert("overlap", CoffeeState.EXTRACTING)
        val insideEdit = java.util.concurrent.CountDownLatch(1)
        val releaseEdit = java.util.concurrent.CountDownLatch(1)
        val heroStarted = java.util.concurrent.CountDownLatch(1)
        val heroDone = java.util.concurrent.CountDownLatch(1)
        val edit = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
            repo.edit("overlap") { row ->
                insideEdit.countDown()
                check(releaseEdit.await(5, java.util.concurrent.TimeUnit.SECONDS))
                row.copy(roaster = "Hand edited")
            }
        }
        assertTrue(insideEdit.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val hero = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
            heroStarted.countDown()
            db.coffeeDao().setHeroResult("overlap", 11, 12, 13, 14, false, true)
            heroDone.countDown()
        }
        try {
            assertTrue(heroStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // A column write cannot commit between the edit's snapshot and the whole-row write;
            // if it could, it would complete here and the row write would then erase it.
            assertFalse(heroDone.await(250, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            releaseEdit.countDown()
            edit.await()
            hero.await()
        }
        val row = db.coffeeDao().get("overlap")!!
        assertEquals("Hand edited", row.roaster)
        assertEquals(listOf("roaster"), row.userEdited)
        assertEquals(11, row.tintLight)
        assertEquals(14, row.accentDark)
        assertTrue(row.heroSegmented)
    }


    // --- Rate-it and second-look alarms ---

    /** Re-rating cancels the rate-it alarm only; the second look armed by the first rating survives. */
    @Test fun reRatingKeepsTheSecondLookAlarmArmed() = runTest {
        insert("g11", CoffeeState.LOGGED, roaster = "R")
        repo.rate("g11", 4, 4, 5, null, null, false, null)
        val armedAt = db.coffeeDao().get("g11")!!.secondLookAt!!
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        assertTrue(org.robolectric.Shadows.shadowOf(alarms).scheduledAlarms.any { it.triggerAtMs == armedAt })
        repo.rate("g11", 2, 2, 2, null, null, false, null)
        assertEquals(armedAt, db.coffeeDao().get("g11")!!.secondLookAt)
        assertTrue(
            "the re-rating disarmed the second look",
            org.robolectric.Shadows.shadowOf(alarms).scheduledAlarms.any { it.triggerAtMs == armedAt },
        )
    }

    /** A broadcast that beats the stored time must not consume the one second look we owe. */
    @Test fun aSecondLookThatArrivesEarlyIsNotConsumed() = runTest {
        insert("g12a", CoffeeState.LOGGED, roaster = "R")
        repo.rate("g12a", 4, 4, 5, null, null, false, null)
        val armedAt = db.coffeeDao().get("g12a")!!.secondLookAt!!
        reminders.notifySecondLook("g12a") // armed five days out, fired now
        val c = db.coffeeDao().get("g12a")!!
        assertFalse(c.secondLookDone)
        assertEquals(armedAt, c.secondLookAt)
        assertEquals(1, db.coffeeDao().pendingSecondLooks().size)
    }

    /** Nothing scheduled, or already looked at, means the broadcast is stale. */
    @Test fun aSecondLookWithNothingScheduledIsIgnored() = runTest {
        insert("g12b", CoffeeState.LOGGED, roaster = "R")
        reminders.notifySecondLook("g12b") // second_look_at is null: nothing was ever armed
        assertFalse(db.coffeeDao().get("g12b")!!.secondLookDone)

        insert("g12d", CoffeeState.LOGGED, roaster = "R")
        db.coffeeDao().mutate("g12d") { it.copy(secondLookDone = true) }
        val stale = 1_000L // a leftover alarm re-armed the column after the look was done
        db.coffeeDao().setSecondLook("g12d", stale)
        reminders.notifySecondLook("g12d")
        assertEquals(stale, db.coffeeDao().get("g12d")!!.secondLookAt) // untouched: the guard returned first
    }

    /** A rate-it broadcast for a coffee with no stored reminder clears nothing and posts nothing. */
    @Test fun aRateItBroadcastWithNoStoredReminderIsIgnored() = runTest {
        insert("g12c", CoffeeState.LOGGED, roaster = "R", remindAt = null)
        repo.edit("g12c") { it.copy(roaster = "Untouched") }
        val before = db.coffeeDao().get("g12c")!!.updatedAt
        reminders.notify("g12c")
        val after = db.coffeeDao().get("g12c")!!
        assertNull(after.remindAt)
        assertEquals(before, after.updatedAt) // clearReminder would have moved it
    }

    @Test fun aStaleRateItBroadcastLeavesALaterReminderAlone() = runTest {
        val later = System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L
        insert("g12e", CoffeeState.LOGGED, roaster = "R", remindAt = later)
        reminders.notify("g12e")
        assertEquals(later, db.coffeeDao().get("g12e")!!.remindAt)
    }

}

/** SimpleSQLiteQuery keeps its args private; rebuild them the way HistoryQuery does. */
private fun SimpleSQLiteQuery.argsForTest(): Array<Any?> {
    val out = arrayOfNulls<Any>(argCount)
    bindTo(object : androidx.sqlite.db.SupportSQLiteProgram {
        override fun bindNull(index: Int) { out[index - 1] = null }
        override fun bindLong(index: Int, value: Long) { out[index - 1] = value }
        override fun bindDouble(index: Int, value: Double) { out[index - 1] = value }
        override fun bindString(index: Int, value: String) { out[index - 1] = value }
        override fun bindBlob(index: Int, value: ByteArray) { out[index - 1] = value }
        override fun clearBindings() {}
        override fun close() {}
    })
    return out


}
