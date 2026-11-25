package dev.roastbook.app.extraction

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs extraction for every coffee in EXTRACTING state while the app is in the foreground.
 * AICore refuses background calls, so this is tied to the process lifecycle: leaving the app
 * cancels the loop, returning restarts it and picks up where it left off (the state is in Room).
 * Rows parked for a retry (Nano's model still downloading) are picked up again when their time
 * comes; while the app is away, [ExtractionWorker] continues the reads that do not need Nano.
 * A read never starts the roaster lookup: that is the user's tap on Detail ([ReadPass]).
 */
@Singleton
class ExtractionScheduler @Inject constructor(
    private val dao: CoffeeDao,
    private val extractor: Extractor,
    private val readPass: ReadPass,
    private val repository: CoffeeRepository,
    private val clock: Clock,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        val owner = ProcessLifecycleOwner.get()
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    withContext(Dispatchers.Default) {
                        runCatchingCancellable { repository.retryHeroes() }
                            .onFailure { Log.w(TAG, "hero retry failed", it) }
                    }
                }
                // Room re-emits on ANY write to the coffee table (a hero palette landing, an attempt
                // counter); only a change in WHICH rows need reading may cancel and restart the loop.
                dao.extractingIds().distinctUntilChanged().collectLatest { ids ->
                    while (true) {
                        for (id in ids) {
                            withContext(Dispatchers.Default) { readPass.readIfDue(id, RunMode.FOREGROUND) }
                        }
                        // Rows parked for a timed retry: sleep until the earliest is due, then go round again.
                        val next = extractor.nextDueAt(ids) ?: break
                        delay((next - clock.now()).coerceAtLeast(1_000L))
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Extraction"
    }
}
