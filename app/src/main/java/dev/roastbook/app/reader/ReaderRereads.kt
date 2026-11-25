package dev.roastbook.app.reader

import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reads again, with the on-device reader, the bags whose reader offer was accepted, as soon as the
 * download is ready (also after a restart, if the download finished while the app was gone).
 */
@Singleton
class ReaderRereads @Inject constructor(
    private val manager: LocalReaderManager,
    private val prefs: ReaderPrefs,
    private val repo: CoffeeRepository,
) {
    suspend fun run() {
        combine(manager.state, prefs.settings) { s, p -> (s.phase == DownloadPhase.READY) to p.pendingRereads.isNotEmpty() }
            .distinctUntilChanged()
            .collect { (ready, waiting) -> if (waiting) drain(ready, prefs) { repo.reextract(it) } }
    }

    companion object {
        /** When [ready], hands every waiting coffee to [reread] once; the list is emptied first, so none is read twice. */
        suspend fun drain(ready: Boolean, prefs: ReaderPrefs, reread: suspend (String) -> Unit) {
            if (!ready) return
            prefs.takePendingRereads().forEach { id -> runCatchingCancellable { reread(id) } }
        }
    }
}
