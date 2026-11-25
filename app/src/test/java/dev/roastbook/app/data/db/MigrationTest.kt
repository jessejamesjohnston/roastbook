package dev.roastbook.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens a database built from an older exported schema (app/schemas) through Room with the real
 * migrations. Room validates the migrated tables against the current entities when it opens a
 * database that has no identity record, so a migration that leaves a column wrong fails here.
 * (MigrationTestHelper cannot see test assets under Robolectric, which is why the schema JSON is
 * read from disk instead.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var room: RoastbookDatabase? = null

    @After fun tearDown() {
        room?.close()
        context.deleteDatabase(DB)
    }

    /** Creates [DB] at schema [version] by replaying every createSql of the exported JSON, as MigrationTestHelper does. */
    private fun createOldDatabase(version: Int, seed: (SQLiteDatabase) -> Unit = {}) {
        context.deleteDatabase(DB)
        val file = File(System.getProperty("user.dir")!!, "schemas/${RoastbookDatabase::class.java.name}/$version.json")
        assertTrue("exported schema missing: $file", file.exists())
        val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        val dbFile = context.getDatabasePath(DB).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            for (entity in database["entities"]!!.jsonArray) {
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in e["indices"]?.jsonArray.orEmpty()) {
                    db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            seed(db)
            db.version = version
        }
    }

    private fun insertRow(db: SQLiteDatabase, id: String) {
        db.execSQL(
            "INSERT INTO coffee (id, created_at, updated_at, logged_at, state, roaster, varietal, tasting_notes, buy_again, " +
                "palette_fallback, extraction_source, extraction_attempts, second_look_done) " +
                "VALUES ('$id', 1, 1, 1, 'LOGGED', 'Kept Roaster', '[]', '[\"Cherry\"]', 0, 0, 'NANO', 1, 0)"
        )
    }

    private fun openWithRoom(): RoastbookDatabase =
        Room.databaseBuilder(context, RoastbookDatabase::class.java, DB)
            .addMigrations(*RoastbookDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { room = it }

    @Test fun migrate2To3AddsTheEditMarkerAndKeepsRows() = runTest {
        createOldDatabase(2) { insertRow(it, "c1") }
        val dao = openWithRoom().coffeeDao()
        val c = dao.get("c1")!!
        assertEquals("Kept Roaster", c.roaster)
        assertEquals(listOf("Cherry"), c.tastingNotes)
        assertTrue(c.userEdited.isEmpty())
        // The marker round-trips through the new column.
        dao.update(c.copy(userEdited = listOf("roaster")))
        assertEquals(listOf("roaster"), dao.get("c1")!!.userEdited)
    }

    @Test fun migrate1To3RunsTheAutoStepThenTheManualOne() = runTest {
        createOldDatabase(1)
        val dao = openWithRoom().coffeeDao()
        dao.insert(CoffeeEntity("c2", 1, 1, 1, CoffeeState.LOGGED, userEdited = listOf("region")))
        assertEquals(listOf("region"), dao.get("c2")!!.userEdited)
    }

    @Test fun migrate3To4AddsTheRetryAndTrailColumns() = runTest {
        createOldDatabase(3) { insertRow(it, "c3") }
        val dao = openWithRoom().coffeeDao()
        val c = dao.get("c3")!!
        assertEquals("Kept Roaster", c.roaster)
        assertEquals(null, c.nextAttemptAt)
        assertEquals(null, c.extractionTrail)
        dao.update(c.copy(nextAttemptAt = 123L, extractionTrail = "nano=preparing", extractionSource = ExtractionSource.LOCAL))
        val back = dao.get("c3")!!
        assertEquals(123L, back.nextAttemptAt)
        assertEquals("nano=preparing", back.extractionTrail)
        assertEquals(ExtractionSource.LOCAL, back.extractionSource)
    }

    private companion object { const val DB = "migration-test.db" }
}
