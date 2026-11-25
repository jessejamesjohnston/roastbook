package dev.roastbook.app.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.roastbook.app.extraction.NanoHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Device-scoped reader state. None of it may travel to another phone through a backup. */
data class ReaderSettings(
    /** The user allowed the model download on mobile data (default: Wi-Fi or other unmetered only). */
    val allowMetered: Boolean = false,
    /** Gemini Nano failed here since it last worked, so the reader is worth offering. */
    val nanoFailing: Boolean = false,
    val nanoFailReason: String? = null,
    val nanoFailedAt: Long? = null,
    /** Read bags with the on-device reader (once downloaded) instead of this device's built-in AI. */
    val preferReader: Boolean = false,
    /** Times the person answered "Not now" to the in-flow reader offer (see [ReaderNudge]). */
    val offerDeclines: Int = 0,
    /** Coffees whose reader offer was answered "Not now"; never offered again. */
    val offerDeclinedIds: Set<String> = emptySet(),
    /** Coffees to read again with the reader once its download finishes (the offer was accepted). */
    val pendingRereads: Set<String> = emptySet(),
    /**
     * Download jobs whose outcome no longer stands: canceled ones, ones whose reader was deleted
     * (WorkManager records both as CANCELLED, the same as a pause, so this is how a stopped job
     * tells the two apart), and a failed one an accepted offer is downloading again.
     */
    val discardedJobs: Set<String> = emptySet(),
)

/**
 * Stored in its own DataStore under `no_backup/` (see the Hilt module), not in the Tier 0
 * `datastore/` preferences: "Nano fails on this phone" and "mobile data is fine" are facts about
 * this device, and restoring them onto a new phone would be wrong.
 */
class ReaderPrefs(private val store: DataStore<Preferences>, private val now: () -> Long = System::currentTimeMillis) : NanoHealth {
    private object K {
        val allowMetered = booleanPreferencesKey("allow_metered")
        val nanoFailing = booleanPreferencesKey("nano_failing")
        val nanoFailReason = stringPreferencesKey("nano_fail_reason")
        val nanoFailedAt = longPreferencesKey("nano_failed_at")
        val preferReader = booleanPreferencesKey("prefer_reader")
        val offerDeclines = intPreferencesKey("offer_declines")
        val offerDeclinedIds = stringSetPreferencesKey("offer_declined_ids")
        val pendingRereads = stringSetPreferencesKey("pending_rereads")
        val discardedJobs = stringSetPreferencesKey("discarded_jobs")
    }

    val settings: Flow<ReaderSettings> = store.data.map { p ->
        ReaderSettings(
            allowMetered = p[K.allowMetered] ?: false,
            nanoFailing = p[K.nanoFailing] ?: false,
            nanoFailReason = p[K.nanoFailReason],
            nanoFailedAt = p[K.nanoFailedAt],
            preferReader = p[K.preferReader] ?: false,
            offerDeclines = p[K.offerDeclines] ?: 0,
            offerDeclinedIds = p[K.offerDeclinedIds] ?: emptySet(),
            pendingRereads = p[K.pendingRereads] ?: emptySet(),
            discardedJobs = p[K.discardedJobs] ?: emptySet(),
        )
    }

    suspend fun current(): ReaderSettings = settings.first()
    suspend fun setAllowMetered(on: Boolean) { store.edit { it[K.allowMetered] = on } }

    suspend fun setPreferReader(on: Boolean) { store.edit { it[K.preferReader] = on } }

    /**
     * "Not now" on [coffeeId]'s reader card. On the offer, that coffee is not offered again, and the
     * count goes up once per coffee, so a second tap before the card goes away doesn't use up the
     * other answer. On a failed download the bag was waiting for, the bag stops waiting and is not
     * offered again, without counting as a decline (the person said yes once): the partial
     * download and the reader switch stay as they are, for Settings to retry or cancel.
     */
    suspend fun declineOffer(coffeeId: String) {
        store.edit { p ->
            val declined = p[K.offerDeclinedIds] ?: emptySet()
            val waiting = p[K.pendingRereads] ?: emptySet()
            if (coffeeId in waiting) {
                p[K.pendingRereads] = waiting - coffeeId
                p[K.offerDeclinedIds] = declined + coffeeId
                return@edit
            }
            if (coffeeId in declined) return@edit
            p[K.offerDeclines] = (p[K.offerDeclines] ?: 0) + 1
            p[K.offerDeclinedIds] = declined + coffeeId
        }
    }

    /**
     * The person accepted the reader for [coffeeId]: they chose the reader, and that bag is read again
     * once it is here. [supersededJobs] are the finished download jobs the new download replaces, so
     * an earlier failure doesn't show while the new job is on its way.
     */
    suspend fun acceptOffer(coffeeId: String, supersededJobs: Set<String> = emptySet()) {
        store.edit { p ->
            p[K.preferReader] = true
            p[K.pendingRereads] = (p[K.pendingRereads] ?: emptySet()) + coffeeId
            if (supersededJobs.isNotEmpty()) p[K.discardedJobs] = supersededJobs
        }
    }

    /**
     * The download was canceled or the reader deleted: no bag waits for it any longer, and the
     * switch that has it read in place of the built-in AI goes off, since there is no reader left to
     * choose (a later download starts from the built-in AI again). The bags that were waiting count
     * as answered, so their pages don't offer the reader again straight away. [discardedJobs] are
     * the download's job ids, kept so that what they left behind doesn't read as a pause.
     */
    suspend fun abandonReader(discardedJobs: Set<String> = emptySet()) {
        store.edit { p ->
            p[K.discardedJobs] = discardedJobs
            val waiting = p[K.pendingRereads] ?: emptySet()
            p.remove(K.pendingRereads)
            p[K.preferReader] = false
            if (waiting.isNotEmpty()) p[K.offerDeclinedIds] = (p[K.offerDeclinedIds] ?: emptySet()) + waiting
        }
    }

    /**
     * [coffeeId] was read poorly by the built-in AI while an accepted reader download is still on its
     * way (some bag is already waiting for it): it waits too, and is read again when the reader
     * arrives. Not for a coffee whose offer was answered "Not now".
     */
    override suspend fun waitForReaderIfComing(coffeeId: String) {
        store.edit { p ->
            val waiting = p[K.pendingRereads] ?: emptySet()
            if (waiting.isEmpty() || coffeeId in (p[K.offerDeclinedIds] ?: emptySet())) return@edit
            p[K.pendingRereads] = waiting + coffeeId
        }
    }

    /** The coffees waiting for the reader, removed from the list in the same write. */
    suspend fun takePendingRereads(): Set<String> {
        var taken = emptySet<String>()
        store.edit { p -> taken = p[K.pendingRereads] ?: emptySet(); p.remove(K.pendingRereads) }
        return taken
    }

    override suspend fun nanoFailing(): Boolean = current().nanoFailing

    override suspend fun readerPreferred(): Boolean = current().preferReader

    override suspend fun recordNano(ok: Boolean, reason: String?) {
        store.edit { p ->
            if (ok) {
                if (p[K.nanoFailing] == true) { p[K.nanoFailing] = false; p.remove(K.nanoFailReason); p.remove(K.nanoFailedAt) }
            } else {
                p[K.nanoFailing] = true
                p[K.nanoFailReason] = (reason ?: "failed").take(160)
                p[K.nanoFailedAt] = now()
            }
        }
    }
}
