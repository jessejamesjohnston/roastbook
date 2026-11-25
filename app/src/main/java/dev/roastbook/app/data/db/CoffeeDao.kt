package dev.roastbook.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Room access to the coffee, coffee_photo and source tables. [mutate] reads and rewrites a row in
 * one transaction, for read-modify-write callers; fields with their own owners (reminders,
 * enrichment, hero results) also have column-scoped updates that touch nothing else. Flow queries
 * re-emit whenever their tables change.
 */
@Dao
interface CoffeeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(coffee: CoffeeEntity)

    @Update
    suspend fun update(coffee: CoffeeEntity)

    /** Serialize the read AND write with extraction and column-scoped writers. */
    @Transaction
    suspend fun mutate(id: String, transform: (CoffeeEntity) -> CoffeeEntity?): CoffeeEntity? {
        val before = get(id) ?: return null
        val after = transform(before) ?: return null
        update(after)
        return after
    }

    @Delete
    suspend fun delete(coffee: CoffeeEntity)

    @Query("SELECT * FROM coffee WHERE id = :id")
    suspend fun get(id: String): CoffeeEntity?

    @Query("SELECT * FROM coffee WHERE id = :id")
    fun observe(id: String): Flow<CoffeeEntity?>

    @Query("SELECT COUNT(*) FROM coffee")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM coffee WHERE state = 'LOGGED'")
    fun unratedCount(): Flow<Int>

    /**
     * Every row still being read, including one whose attempts ran out (the extractor finishes it
     * with the guess). Filtering on the count would make the up-front count of a last attempt drop
     * the row from this list, cancel the running read, and leave the row unread for good.
     */
    @Query("SELECT id FROM coffee WHERE state = 'EXTRACTING' ORDER BY created_at")
    fun extractingIds(): Flow<List<String>>

    @Query("SELECT id FROM coffee WHERE state = 'EXTRACTING' ORDER BY created_at")
    suspend fun extractingIdsNow(): List<String>

    @Query("SELECT * FROM coffee WHERE remind_at IS NOT NULL AND state != 'RATED'")
    suspend fun pendingReminders(): List<CoffeeEntity>

    /** Column-scoped writes: these fields have their own owners, so they never ride a whole-row update. */
    @Query("UPDATE coffee SET remind_at = :at, updated_at = :now WHERE id = :id")
    suspend fun setRemindAt(id: String, at: Long?, now: Long)

    /**
     * Consumes the rate-it reminder only if it is still the one the caller read ([at]); returns 1
     * when it was. A reminder moved or restored since that read is left alone.
     */
    @Query("UPDATE coffee SET remind_at = NULL WHERE id = :id AND remind_at = :at")
    suspend fun clearReminder(id: String, at: Long): Int

    @Query("UPDATE coffee SET second_look_at = :at WHERE id = :id")
    suspend fun setSecondLook(id: String, at: Long?)

    /** The second-look counterpart of [clearReminder]: consumes it only if it is still the one read at [at]. */
    @Query("UPDATE coffee SET second_look_done = 1, second_look_at = NULL WHERE id = :id AND second_look_at = :at AND second_look_done = 0")
    suspend fun markSecondLookDone(id: String, at: Long): Int

    @Query("SELECT * FROM coffee WHERE second_look_at IS NOT NULL AND second_look_done = 0")
    suspend fun pendingSecondLooks(): List<CoffeeEntity>

    @Query(
        "UPDATE coffee SET roaster_url = :roasterUrl, product_url = :productUrl, roaster_city = :roasterCity, " +
            "enrichment_status = :status WHERE id = :id"
    )
    suspend fun setEnrichment(id: String, roasterUrl: String?, productUrl: String?, roasterCity: String?, status: String?)

    // distinct values for the filter pickers
    @Query("SELECT DISTINCT roaster FROM coffee WHERE roaster IS NOT NULL AND roaster != '' ORDER BY roaster COLLATE NOCASE")
    fun roasters(): Flow<List<String>>

    @Query(
        "SELECT DISTINCT origin_country FROM coffee WHERE origin_country IS NOT NULL AND origin_country != '' " +
            "ORDER BY origin_country COLLATE NOCASE"
    )
    fun origins(): Flow<List<String>>

    @Query("SELECT DISTINCT process FROM coffee WHERE process IS NOT NULL AND process != '' ORDER BY process COLLATE NOCASE")
    fun processes(): Flow<List<String>>

    @Query("UPDATE coffee SET hero_attempts = hero_attempts + 1 WHERE id = :id")
    suspend fun bumpHeroAttempts(id: String)

    @Query(
        "UPDATE coffee SET tint_light = :tintLight, tint_dark = :tintDark, accent_light = :accentLight, accent_dark = :accentDark, " +
            "palette_fallback = :fallback, hero_segmented = :segmented, hero_attempts = hero_attempts + 1 WHERE id = :id"
    )
    suspend fun setHeroResult(id: String, tintLight: Int, tintDark: Int, accentLight: Int, accentDark: Int, fallback: Boolean, segmented: Boolean)

    @Query("SELECT * FROM coffee WHERE state = 'DRAFT'")
    suspend fun drafts(): List<CoffeeEntity>

    @Query("SELECT id FROM coffee WHERE hero_segmented = 0 AND hero_attempts < 3 AND hero_photo_id IS NOT NULL ORDER BY created_at")
    suspend fun heroRetryIds(): List<String>

    /**
     * History rows. Room needs a static query for PagingSource, so sorting is done with a
     * CASE over a parameter rather than string building; search is a LIKE over the text columns.
     */
    @Transaction
    @RawQuery(observedEntities = [CoffeeEntity::class, CoffeePhotoEntity::class])
    fun rows(query: SupportSQLiteQuery): PagingSource<Int, CoffeeRow>

    @Query("SELECT * FROM coffee WHERE roaster = :roaster AND coffee_name = :name AND id != :excludeId ORDER BY logged_at DESC")
    suspend fun sameCoffee(roaster: String, name: String, excludeId: String): List<CoffeeEntity>

    // photos
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<CoffeePhotoEntity>)

    @Update
    suspend fun updatePhoto(photo: CoffeePhotoEntity)

    @Query("SELECT * FROM coffee_photo WHERE coffee_id = :coffeeId ORDER BY position")
    suspend fun photos(coffeeId: String): List<CoffeePhotoEntity>

    @Query("SELECT * FROM coffee_photo WHERE coffee_id = :coffeeId ORDER BY position")
    fun observePhotos(coffeeId: String): Flow<List<CoffeePhotoEntity>>

    @Query("SELECT DISTINCT coffee_id FROM coffee_photo")
    suspend fun coffeeIdsWithPhotos(): List<String>

    @Query("SELECT id FROM coffee")
    suspend fun allIds(): List<String>

    // backup / restore
    @Query("SELECT * FROM coffee")
    suspend fun allCoffees(): List<CoffeeEntity>

    @Query("SELECT * FROM coffee_photo")
    suspend fun allPhotos(): List<CoffeePhotoEntity>

    @Query("SELECT * FROM source")
    suspend fun allSources(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoffees(coffees: List<CoffeeEntity>)

    /** Photos go with their coffee (FK cascade). */
    @Query("DELETE FROM coffee")
    suspend fun deleteAllCoffees()

    @Query("DELETE FROM source")
    suspend fun deleteAllSources()

    @Query("SELECT MAX(updated_at) FROM coffee")
    suspend fun lastUpdatedAt(): Long?

    /** Self-heal: a coffee with a score is rated, whatever an earlier re-read left behind. */
    @Query("UPDATE coffee SET state = 'RATED' WHERE overall IS NOT NULL AND state = 'LOGGED'")
    suspend fun repairRatedStates(): Int

    // sources
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<SourceEntity>)

    @Query("SELECT COUNT(*) FROM source")
    suspend fun sourceCount(): Int

    @Query("SELECT * FROM source ORDER BY is_seed DESC, name")
    fun sources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun sourceByName(name: String): SourceEntity?
}
