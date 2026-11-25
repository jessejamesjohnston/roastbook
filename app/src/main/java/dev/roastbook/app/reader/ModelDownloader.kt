package dev.roastbook.app.reader

import android.annotation.SuppressLint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What a download attempt ended with. [Retry] is worth trying again later; [Failed] is not, as is. */
sealed interface DownloadResult {
    data object Done : DownloadResult
    data class Retry(val detail: String) : DownloadResult
    data class Failed(val reason: Reason, val detail: String) : DownloadResult
    enum class Reason { NO_SPACE, SIZE_MISMATCH, HASH_MISMATCH, HTTP }
}

/** Progress for the notification and Settings. */
data class DownloadProgress(val phase: Phase, val bytes: Long, val total: Long) {
    enum class Phase { DOWNLOADING, VERIFYING }
}

/**
 * Resumable, verified download of one pinned model file.
 *
 * - Writes to `<name>.part` and resumes from its length with an HTTP Range request. A 206 must say
 *   it starts where the part ends and that the whole file is the pinned size; a 200 means the
 *   server ignored the range, so the part is truncated and the download starts over.
 * - Every attempt starts at the published URL, never a cached redirect: Hugging Face redirects to a
 *   signed CDN address that expires.
 * - When the part reaches the pinned size, its SHA-256 is computed over the whole file. Only a
 *   match is renamed into place ([ModelStore.promote]); a mismatch deletes the part.
 * - Canceling (the coroutine) stops between chunks and leaves the part for a later resume.
 */
class ModelDownloader(private val http: OkHttpClient) {

    /**
     * One writer at a time per process: a download replaced while its blocking read is still running
     * (the mobile-data switch re-enqueues it) waits here for the old one to stop, instead of both
     * appending to the same part file.
     */
    suspend fun download(spec: ModelSpec, store: ModelStore, onProgress: suspend (DownloadProgress) -> Unit = {}): DownloadResult =
        writer.withLock { downloadLocked(spec, store, onProgress) }

    private suspend fun downloadLocked(spec: ModelSpec, store: ModelStore, onProgress: suspend (DownloadProgress) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            store.ensureDirs()
            if (store.isReady()) return@withContext DownloadResult.Done
            val part = store.part
            if (part.length() > spec.sizeBytes) part.delete()
            val have = if (part.isFile) part.length() else 0L

            val need = spec.sizeBytes - have + FREE_SPACE_MARGIN
            // Space free right now, not what the system could free by clearing other apps' caches
            // (StorageManager.allocateBytes): the download never evicts anyone else's data. A disk
            // that fills mid-download fails with NO_SPACE and the next attempt resumes the part file.
            @SuppressLint("UsableSpace")
            val free = store.root.usableSpace
            if (free < need) {
                val detail = "needs ${need / MB} MB free, has ${free / MB} MB"
                return@withContext DownloadResult.Failed(DownloadResult.Reason.NO_SPACE, detail)
            }

            if (have < spec.sizeBytes) {
                when (val opened = openRange(spec, part, have)) {
                    is RangeOpen.Stop -> return@withContext opened.result
                    RangeOpen.NothingToSend -> Unit
                    is RangeOpen.Stream -> opened.response.use { r ->
                        streamToPart(spec, part, r, opened.append, opened.from, onProgress)?.let { return@withContext it }
                    }
                }
            }

            if (part.length() != spec.sizeBytes) {
                return@withContext DownloadResult.Retry("incomplete: ${part.length()} of ${spec.sizeBytes}")
            }
            verifyAndPromote(spec, store, part, onProgress)
        }

    /** What asking the server for the rest of the file gave: bytes to write, nothing left to send, or an end. */
    private sealed interface RangeOpen {
        /** Write [response]'s body to the part, appending when [append], counting from [from] bytes. */
        class Stream(val response: Response, val append: Boolean, val from: Long) : RangeOpen
        /** The server has nothing past what the part holds; go on to verify it. */
        data object NothingToSend : RangeOpen
        class Stop(val result: DownloadResult) : RangeOpen
    }

    /**
     * Requests the file from byte [have] on and checks the answer: a 206 must start where the part
     * ends and name the pinned total; a 200 restarts from zero; a 416 means the part is already whole
     * (or is discarded). Any response not returned as a [RangeOpen.Stream] is closed here.
     */
    private fun openRange(spec: ModelSpec, part: File, have: Long): RangeOpen {
        val request = Request.Builder().url(spec.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val r = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            return RangeOpen.Stop(DownloadResult.Retry("network: ${e::class.simpleName}: ${e.message?.take(120)}"))
        }
        val opened = when (r.code) {
            206 -> {
                val range = CONTENT_RANGE.find(r.header("Content-Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toLongOrNull()
                val total = range?.groupValues?.get(3)?.toLongOrNull()
                if (total != null && total != spec.sizeBytes) {
                    part.delete()
                    RangeOpen.Stop(sizeMismatch("server file is $total bytes, expected ${spec.sizeBytes}"))
                } else if (start != have) {
                    // A range we did not ask for: start over rather than splice.
                    part.delete()
                    RangeOpen.Stop(DownloadResult.Retry("unexpected range start $start (had $have)"))
                } else RangeOpen.Stream(r, append = true, from = have)
            }
            200 -> {
                val length = r.body?.contentLength() ?: -1L
                if (length >= 0 && length != spec.sizeBytes) {
                    RangeOpen.Stop(sizeMismatch("server file is $length bytes, expected ${spec.sizeBytes}"))
                } else RangeOpen.Stream(r, append = false, from = 0L)
            }
            // Nothing left to send for our range: verify what we have, or start over.
            416 -> if (have != spec.sizeBytes) {
                part.delete()
                RangeOpen.Stop(DownloadResult.Retry("range not satisfiable at $have"))
            } else RangeOpen.NothingToSend
            in 500..599, 408, 429 -> RangeOpen.Stop(DownloadResult.Retry("HTTP ${r.code}"))
            else -> RangeOpen.Stop(DownloadResult.Failed(DownloadResult.Reason.HTTP, "HTTP ${r.code}"))
        }
        if (opened !is RangeOpen.Stream) r.close()
        return opened
    }

    /**
     * Copies [r]'s body into [part], reporting progress, and checks the running total never passes
     * the pinned size. Returns null when the body was written in full, else the attempt's result.
     */
    private suspend fun streamToPart(
        spec: ModelSpec, part: File, r: Response, append: Boolean, from: Long, onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult? {
        val body = r.body ?: return DownloadResult.Retry("empty body")
        try {
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER)
                    var written = from
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written > spec.sizeBytes) {
                            out.close(); part.delete()
                            return sizeMismatch("more than ${spec.sizeBytes} bytes")
                        }
                        if (written - lastReport >= REPORT_EVERY || written == spec.sizeBytes) {
                            lastReport = written
                            onProgress(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, written, spec.sizeBytes))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return DownloadResult.Retry("interrupted at ${part.length()}: ${e::class.simpleName}")
        }
        return null
    }

    /** Hashes the whole part; only a match with the pinned SHA-256 is moved into place. */
    private suspend fun verifyAndPromote(
        spec: ModelSpec, store: ModelStore, part: File, onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult {
        onProgress(DownloadProgress(DownloadProgress.Phase.VERIFYING, 0, spec.sizeBytes))
        val digest = sha256(part, spec.sizeBytes, onProgress)
        if (!digest.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            return DownloadResult.Failed(DownloadResult.Reason.HASH_MISMATCH, "SHA-256 $digest, expected ${spec.sha256}")
        }
        // A Cancel that arrived during the last chunk of the check wins over moving the file in.
        currentCoroutineContext().ensureActive()
        store.promote(part, spec.sha256)
        return DownloadResult.Done
    }

    private fun sizeMismatch(detail: String) = DownloadResult.Failed(DownloadResult.Reason.SIZE_MISMATCH, detail)

    private suspend fun sha256(file: File, total: Long, onProgress: suspend (DownloadProgress) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            var done = 0L
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                if (done - lastReport >= REPORT_EVERY * 4) {
                    lastReport = done
                    onProgress(DownloadProgress(DownloadProgress.Phase.VERIFYING, done, total))
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** See [download]: shared by every downloader in the process, and by Cancel and Delete ([LocalReaderManager]). */
        internal val writer = Mutex()
        private const val MB = 1024L * 1024L
        private const val BUFFER = 256 * 1024
        private const val REPORT_EVERY = 4 * MB
        /** Room left over after the model, so the download never fills the phone. */
        const val FREE_SPACE_MARGIN = 300 * MB
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")
    }
}
