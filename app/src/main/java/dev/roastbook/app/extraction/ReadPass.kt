package dev.roastbook.app.extraction

import android.util.Log
import dev.roastbook.app.data.db.CoffeeDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * One read of one row that is due, shared by the foreground loop ([ExtractionScheduler]) and the
 * background job ([ExtractionWorker]). It reads the bag and nothing else. The roaster lookup is a
 * network call that only the user's tap starts (Detail, "Look up roaster"), so no read, in either
 * mode, ever leads to one.
 */
@Singleton
class ReadPass @Inject constructor(
    private val dao: CoffeeDao,
    private val extractor: Extractor,
) {
    /** The outcome, or null when the row was not due or the attempt failed (the failure is recorded). */
    suspend fun readIfDue(id: String, mode: RunMode): ExtractionOutcome? = try {
        extractor.runIfDue(id, mode)?.also { Log.i(TAG, "read $id ($mode): ${it.source} (${it.note})") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "read failed for $id ($mode)", e)
        extractor.recordFailure(id, dao.get(id)?.ocrText, "error: ${e::class.simpleName}: ${e.message?.take(120)}")
        null
    }

    private companion object {
        const val TAG = "Extraction"
    }
}
