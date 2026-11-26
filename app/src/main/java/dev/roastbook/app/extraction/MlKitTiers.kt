package dev.roastbook.app.extraction

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [OcrTier] over ML Kit's on-device Latin text recognizer. Each call uses its own recognizer and
 * releases it when the call completes or is canceled.
 */
@Singleton
class MlKitOcrTier @Inject constructor() : OcrTier {
    override suspend fun recognize(image: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        cont.invokeOnCancellation { recognizer.close() } // a timed-out call releases the recognizer too
        recognizer.process(InputImage.fromBitmap(image, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
            .addOnCompleteListener { recognizer.close() }
    }
}

/**
 * Gemini Nano through the ML Kit GenAI Prompt API; status and generate calls are serialized. Each
 * read gets a client of its own, closed when the read ends, so nothing a client might keep (a
 * session, an implicit cache) can carry one bag's answer into the next; status checks and the model
 * download share one long-lived client. The download runs detached from the caller and is shared: a
 * read that stops waiting (the extractor bounds the wait) leaves it running, and the next read joins
 * it instead of starting another, so a download that never reports back cannot hold the lock other
 * calls need.
 */
@Singleton
class GeminiNanoTier internal constructor(private val newClient: () -> GenerativeModel) : NanoTier {
    @Inject constructor() : this(Generation::getClient)

    private val mutex = Mutex()
    private var model: GenerativeModel? = null
    private var name: String = "nano"
    private val downloads = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var download: Deferred<Unit>? = null

    @Synchronized
    private fun client(): GenerativeModel = model ?: newClient().also { model = it }

    override fun modelName(): String = name

    override suspend fun status(): NanoStatus = mutex.withLock {
        when (client().checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADABLE
            else -> NanoStatus.UNAVAILABLE
        }
    }

    override suspend fun prepare() {
        val d = synchronized(this) {
            download?.takeIf { it.isActive } ?: downloads.async {
                try {
                    client().download().collect { s -> if (s is DownloadStatus.DownloadFailed) throw s.e }
                } catch (e: CancellationException) {
                    // The library cancels its own job when the flow ends; only propagate OUR cancellation.
                    if (!currentCoroutineContext().isActive) throw e
                }
            }.also { download = it }
        }
        d.await()
    }

    override suspend fun generate(images: List<Bitmap>, prompt: String): String = mutex.withLock {
        val m = newClient()
        try { read(m, images, prompt) } finally { m.close() }
    }

    private suspend fun read(m: GenerativeModel, images: List<Bitmap>, prompt: String): String {
        runCatching { name = m.getBaseModelName() }
        val content = Content.builder().apply {
            images.forEach { addPart(ImagePart(it)) }
            addPart(TextPart(prompt))
        }.build()
        val request = generateContentRequest(content) {
            temperature = 0.2f
            maxOutputTokens = 1024
        }
        return try {
            m.generateContent(request).candidates.firstOrNull()?.text ?: ""
        } catch (e: GenAiException) {
            // 30 = background usage blocked; treat as transient so the row is retried in the foreground.
            if (e.errorCode == 30) throw TransientExtractionException("background blocked") else throw e
        }
    }
}
