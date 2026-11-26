package dev.roastbook.app.reader

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.roastbook.app.extraction.LocalAvailability
import dev.roastbook.app.extraction.LocalTier
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The on-device reader: Gemma 4 E2B through LiteRT-LM, on the CPU for both the decoder and the
 * vision encoder. The GPU backend is not used: with prompts past about 2k tokens (ours, with
 * images, always are) it corrupts output on current phone GPUs (digits dropped, keys mangled;
 * LiteRT-LM issue #3012). Re-test on each LiteRT-LM release.
 *
 * One engine per process, created on first use and closed after a minute idle: it holds about
 * 4 GB while loaded. Calls are serialized. A canceled read asks the runtime to stop and waits for
 * it to return before the engine can be used or closed again.
 */
@Singleton
class LiteRtLocalReader @Inject constructor(private val manager: LocalReaderManager) : LocalTier {
    private val lock = Mutex()
    private var engine: Engine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleClose: Job? = null

    override fun modelName(): String = "gemma-4-e2b-cpu"

    override suspend fun availability(): LocalAvailability = when {
        manager.eligibility != Eligibility.ELIGIBLE -> LocalAvailability.UNSUPPORTED
        !withContext(Dispatchers.IO) { manager.store.isReady() } -> LocalAvailability.NOT_DOWNLOADED
        // Loaded only once there is a model to run.
        !nativeLibraryPresent -> LocalAvailability.UNSUPPORTED
        else -> LocalAvailability.READY
    }

    override suspend fun generate(images: List<Bitmap>, prompt: String): String = lock.withLock {
        idleClose?.cancel()
        try {
            val e = withContext(Dispatchers.IO) { engine ?: load().also { engine = it } }
            val parts = mutableListOf<Content>()
            // The engine is configured for at most MAX_IMAGES; the OCR text still covers every photo.
            images.take(MAX_IMAGES).forEach { parts += Content.ImageBytes(jpeg(it)) }
            parts += Content.Text(prompt)
            val conversation = e.createConversation(ConversationConfig(maxOutputToken = MAX_OUTPUT_TOKENS))
            val done = CompletableDeferred<Result<String>>()
            thread(name = "litertlm-read", isDaemon = true) {
                done.complete(runCatching { conversation.sendMessage(Contents.of(parts)).toString() })
            }
            try {
                done.await().getOrThrow()
            } catch (c: CancellationException) {
                // Stop the native call and wait for it: the engine must not be reused or closed under it.
                runCatching { conversation.cancelProcess() }
                withContext(NonCancellable) { done.await() }
                throw c
            } finally {
                runCatching { conversation.close() }
            }
        } finally {
            idleClose = scope.launch { delay(IDLE_CLOSE_MS); lock.withLock { closeEngine() } }
        }
    }

    private fun load(): Engine {
        val store = manager.store
        store.ensureDirs()
        val t0 = System.nanoTime()
        return Engine(
            EngineConfig(
                modelPath = store.model.path,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                maxNumImages = MAX_IMAGES,
                cacheDir = store.cacheDir.path,
            ),
        ).also {
            it.initialize()
            Log.i(TAG, "engine loaded in %.1f s".format(java.util.Locale.US, (System.nanoTime() - t0) / 1e9))
            // The first load writes the runtime's cache: Settings' size on disk follows at once.
            manager.onDiskChanged()
        }
    }

    /** Before the model is deleted from Settings: waits for a read in progress, then unloads. */
    suspend fun release() { lock.withLock { closeEngine() } }

    private fun closeEngine() {
        engine?.let { runCatching { it.close() } }
        engine = null
    }

    private fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        b.compress(Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray()
    }

    private val nativeLibraryPresent: Boolean by lazy {
        // The runtime loads its own library on first use; check it can, so an ABI we do not ship for
        // reads as "unsupported" instead of crashing mid-read.
        runCatching { System.loadLibrary("litertlm_jni"); true }.getOrElse { Log.w(TAG, "LiteRT-LM native library unavailable", it); false }
    }

    private companion object {
        const val TAG = "LocalReader"
        const val MAX_IMAGES = 4
        const val MAX_TOKENS = 4096
        const val MAX_OUTPUT_TOKENS = 1024
        const val IDLE_CLOSE_MS = 60_000L
    }
}
