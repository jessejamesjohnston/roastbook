package dev.roastbook.app.data.repo

import kotlinx.coroutines.sync.withLock
import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeRow
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractedFields
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.HistoryQuery
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.SourceEntity
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.prefs.UserPrefs
import dev.roastbook.app.util.runCatchingCancellable
import dev.roastbook.app.vision.HeroProcessor
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo waiting in staging for [CoffeeRepository.create], with what it shows. */
data class NewPhoto(val staged: File, val role: PhotoRole)

private const val DAY = 24L * 60 * 60 * 1000

/**
 * The write path the screens use for coffees, photos and sources. Creating,
 * rating, editing, reminding, setting a source and deleting run to completion even if the calling
 * ViewModel's scope is canceled mid-way, and row edits go through [CoffeeDao.mutate], so a
 * read-modify-write never races the extractor writing the same row. Edits to extracted fields
 * are recorded in [CoffeeEntity.userEdited], which keeps a later read from overwriting them.
 * Also owns hero processing launches and the rate-it and second-look alarms that go with a rating
 * or reminder.
 */
@Singleton
class CoffeeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CoffeeDao,
    private val photos: PhotoStore,
    private val hero: HeroProcessor,
    private val reminders: dev.roastbook.app.reminders.ReminderScheduler,
    private val userPrefs: UserPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Callers are ViewModels whose scope dies when their screen leaves the back stack, sometimes in
     * the same frame as the tap that asked for the write. A write that started always lands.
     */
    private suspend fun <T> committed(block: suspend () -> T): T = withContext(NonCancellable) { block() }

    fun history(sort: HistorySort, filter: HistoryFilter, descending: Boolean): Flow<PagingData<CoffeeRow>> =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 20)) { dao.rows(HistoryQuery.build(sort, filter, descending)) }.flow

    fun count(): Flow<Int> = dao.count()
    fun unratedCount(): Flow<Int> = dao.unratedCount()
    fun observe(id: String): Flow<CoffeeEntity?> = dao.observe(id)
    fun observePhotos(id: String): Flow<List<CoffeePhotoEntity>> = dao.observePhotos(id)
    suspend fun get(id: String): CoffeeEntity? = dao.get(id)
    suspend fun photos(id: String): List<CoffeePhotoEntity> = dao.photos(id)
    fun sources(): Flow<List<SourceEntity>> = dao.sources()
    fun roasters(): Flow<List<String>> = dao.roasters()
    fun origins(): Flow<List<String>> = dao.origins()
    fun processes(): Flow<List<String>> = dao.processes()
    fun extractingIds(): Flow<List<String>> = dao.extractingIds()
    suspend fun hasPendingReads(): Boolean = dao.extractingIdsNow().isNotEmpty()

    /**
     * Creates the coffee row FIRST (state DRAFT) and imports photos into it. A failed import removes
     * the row and its files and keeps the staged inputs; a crash mid-way leaves a DRAFT with no
     * photo rows, which [reconcileDrafts] removes at the next start (the staged inputs survive). Hero
     * processing (segmentation crop, square, thumb, palette) runs in the background right after.
     */
    suspend fun create(newPhotos: List<NewPhoto>): String = committed {
        require(newPhotos.isNotEmpty())
        val id = UUID.randomUUID().toString()
        importing += id
        try { importInto(id, newPhotos) } finally { importing -= id }
    }

    /** Imports running in this process; any other DRAFT row was left by a process that died mid-import. */
    private val importing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun importInto(id: String, newPhotos: List<NewPhoto>): String {
        val now = System.currentTimeMillis()
        // DRAFT first: the extraction scheduler only picks up EXTRACTING rows, so photos must land
        // before the state flips, or the scheduler would find the row with no photos and mark it so.
        dao.insert(CoffeeEntity(id = id, createdAt = now, updatedAt = now, loggedAt = now, state = CoffeeState.DRAFT))
        val entities = try {
            newPhotos.mapIndexed { index, p ->
                val photoId = UUID.randomUUID().toString()
                val imported = photos.importOriginal(id, photoId, p.staged)
                CoffeePhotoEntity(
                    id = photoId, coffeeId = id, role = p.role, position = index,
                    original = imported.relativePath, takenAt = imported.takenAt, addedAt = now,
                )
            }.also { dao.insertPhotos(it) }
        } catch (e: Throwable) {
            // One photo failed (unreadable, disk full): undo this attempt completely and leave the
            // staged files where they are, so "Try Done again" has every photo to work with.
            runCatching { dao.get(id)?.let { dao.delete(it) } }
            runCatching { photos.deleteCoffee(id) }
            throw e
        }
        // Only now is every photo stored with its row; the staged copies can go.
        newPhotos.forEach { it.staged.delete() }
        val heroPhoto = entities.firstOrNull { it.role == PhotoRole.FRONT } ?: entities.first()
        val loggedAt = heroPhoto.takenAt.takeIf { it in 1..now } ?: now
        dao.mutate(id) { it.copy(heroPhotoId = heroPhoto.id, loggedAt = loggedAt, state = CoffeeState.EXTRACTING) }
        launchHero(id)
        return id
    }

    private fun launchHero(id: String) {
        scope.launch { runCatchingCancellable { hero.process(id) }.onFailure { android.util.Log.w("Hero", "hero failed for $id", it) } }
    }

    /** Re-run hero processing (crop + palette) for rows whose segmentation never succeeded. */
    suspend fun retryHeroes() {
        for (id in dao.heroRetryIds()) {
            try { hero.process(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Hero", "retry failed for $id", e)
            }
        }
    }

    /**
     * "Reread": queue extraction (and the hero crop) again for one coffee. Fields the user edited by
     * hand stay listed in [CoffeeEntity.userEdited], so the new read only fills or improves the
     * fields the user never touched. The trail keeps only which fields a garbled answer set
     * ([dev.roastbook.app.extraction.ExtractionTrail.garbledFields]).
     */
    suspend fun reextract(id: String) {
        val queued = committed {
            dao.mutate(id) { c ->
                if (c.state == CoffeeState.EXTRACTING) null // a second tap is a no-op
                else c.copy(
                    state = CoffeeState.EXTRACTING,
                    extractionAttempts = 0,
                    nextAttemptAt = null,
                    // Which fields a garbled answer set stays, so the new read can replace them.
                    extractionTrail = dev.roastbook.app.extraction.ExtractionTrail.decode(c.extractionTrail)
                        .carriedAcrossReread().encode().ifEmpty { null },
                    heroSegmented = false,
                    heroAttempts = 0,
                    updatedAt = System.currentTimeMillis(),
                )
            } != null
        }
        if (!queued) return
        try { hero.process(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            android.util.Log.w("Hero", "reextract hero failed for $id", e)
        }
    }

    /**
     * One rating per bag; Overall is the mean, stored for sorting. Re-rating overwrites, and the
     * draft carries every value the user sees: a cleared profile row or emptied notes are cleared.
     */
    suspend fun rate(id: String, aroma: Int, flavor: Int, finish: Int, acidity: Int?, body: Int?, buyAgain: Boolean, notes: String?) = committed {
        val now = System.currentTimeMillis()
        // Score comes only from the valenced dimensions; acidity and body are profile, not quality.
        val overall = listOf(aroma, flavor, finish).average().toFloat()
        val prefs = userPrefs.current()
        var secondLookAt: Long? = null
        dao.mutate(id) { c ->
            // The second nudge: one look back a few days after the FIRST rating, because ratings
            // drift. Stored in the same write as the rating, so a crash can't leave a first rating
            // without its second look.
            if (c.ratedAt == null && prefs.secondLookEnabled && !c.secondLookDone) secondLookAt = now + prefs.secondLookDays * DAY
            c.copy(
                aroma = aroma, flavor = flavor, finish = finish, overall = overall,
                acidity = acidity, body = body,
                buyAgain = buyAgain, notes = notes?.ifBlank { null },
                state = if (c.state == CoffeeState.EXTRACTING) c.state else CoffeeState.RATED,
                ratedAt = c.ratedAt ?: now, lastTastedAt = now, remindAt = null, updatedAt = now,
                secondLookAt = secondLookAt ?: c.secondLookAt,
            )
        } ?: return@committed
        // Only the rate-it alarm: a re-rating must not disarm the second look armed by the first.
        reminders.cancel(id, dev.roastbook.app.reminders.KIND_RATE)
        secondLookAt?.let { reminders.scheduleSecondLook(id, it) }
    }

    /** Stores the rate-it reminder and arms (or clears) the alarm. Column-scoped: extraction may be writing the row. */
    suspend fun setReminder(id: String, at: Long?) = committed {
        if (dao.get(id) == null) return@committed
        dao.setRemindAt(id, at, System.currentTimeMillis())
        if (at == null) reminders.cancel(id, dev.roastbook.app.reminders.KIND_RATE) else reminders.schedule(id, at)
    }

    /**
     * Read-modify-write of one row for the Detail editors. Any extracted field the edit changed is
     * added to [CoffeeEntity.userEdited], so a read in flight (or one still queued behind another
     * coffee, or waiting on the model download) keeps the user's value for it.
     */
    suspend fun edit(id: String, transform: (CoffeeEntity) -> CoffeeEntity) = committed {
        dao.mutate(id) { before ->
            val after = transform(before)
            val touched = ExtractedFields.changed(before, after)
            after.copy(userEdited = (after.userEdited + touched).distinct(), updatedAt = System.currentTimeMillis())
        }
    }

    /** Where the bag came from; null or blank clears it. */
    suspend fun setSource(id: String, name: String?) = committed {
        val sourceId = name?.takeIf { it.isNotBlank() }?.let { ensureSource(it).id }
        dao.mutate(id) { it.copy(sourceId = sourceId, updatedAt = System.currentTimeMillis()) }
    }

    suspend fun updatePhoto(photo: CoffeePhotoEntity) = dao.updatePhoto(photo)

    suspend fun delete(id: String) = committed {
        photos.captureLock.withLock {
            dao.get(id)?.let { dao.delete(it) }
            photos.deleteCoffee(id)
        }
    }

    suspend fun sameCoffee(coffee: CoffeeEntity): List<CoffeeEntity> {
        val r = coffee.roaster ?: return emptyList()
        val n = coffee.coffeeName ?: return emptyList()
        return dao.sameCoffee(r, n, coffee.id)
    }

    /** One row per source name, matched case-insensitively and without surrounding whitespace (keyboards add it). */
    suspend fun ensureSource(name: String): SourceEntity = committed {
        val n = name.trim()
        dao.sourceByName(n)?.let { return@committed it }
        dao.insertSources(listOf(SourceEntity(UUID.randomUUID().toString(), n, isSeed = false, createdAt = System.currentTimeMillis())))
        dao.sourceByName(n) ?: error("source '$n' was not stored")
    }

    /** Idempotent: seeds the source picklist, finishes or drops half-imported rows, removes orphaned photo directories. */
    suspend fun startupMaintenance(now: Long = System.currentTimeMillis()) {
        seedSourcesIfEmpty(now)
        reconcileDrafts(now)
        for (dir in photos.orphanDirs(dao.allIds().toSet())) {
            // Re-check at the moment of deletion: a coffee created after the snapshot keeps its photos.
            if (dao.get(dir.name) == null) photos.deleteCoffee(dir.name)
        }
        dao.repairRatedStates()
    }

    /**
     * The "where you bought it" picklist starts with a few generic choices, on a new install only.
     * A list that already has any entry (an existing journal, a restored or adopted backup) is the
     * user's and stays exactly as it is: nothing added, renamed or removed, and no coffee's source
     * changes. Runs after Tier 0 adoption, which happens before Room opens.
     */
    private suspend fun seedSourcesIfEmpty(now: Long) {
        if (dao.sourceCount() > 0) return
        val seeds = context.resources.getStringArray(R.array.sources_seed)
        dao.insertSources(seeds.map { SourceEntity("seed:" + it.lowercase().replace(' ', '-'), it, isSeed = true, createdAt = now) })
    }

    /**
     * A DRAFT row is an import that never finished (process death mid-[create]). With photo rows it
     * can go on to extraction; without, it is an empty row the user never saw. A row whose import is
     * still running in this process is left to it, however long it takes.
     */
    private suspend fun reconcileDrafts(now: Long) {
        for (c in dao.drafts()) {
            if (c.id in importing) continue
            val ps = dao.photos(c.id)
            if (ps.isEmpty()) {
                dao.delete(c)
                photos.deleteCoffee(c.id)
            } else {
                val heroPhoto = ps.firstOrNull { it.role == PhotoRole.FRONT } ?: ps.first()
                dao.mutate(c.id) { fresh ->
                    if (fresh.state != CoffeeState.DRAFT) null
                    else fresh.copy(
                        heroPhotoId = fresh.heroPhotoId ?: heroPhoto.id,
                        state = CoffeeState.EXTRACTING,
                        userEdited = emptyList(),
                        updatedAt = now,
                    )
                }
                launchHero(c.id)
            }
        }
    }
}
