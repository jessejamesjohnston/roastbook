package dev.roastbook.app.cloud

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The API refused the request in a way a retry will not fix (bad key, unknown model, quota).
 * [reason] is the machine-readable reason Google attaches to some errors (`API_KEY_INVALID`).
 */
class GeminiRequestException(val code: Int, message: String, val reason: String? = null) : IOException("HTTP $code: $message")

/** The saved key has characters no API key has; nothing was sent. The message never holds the key. */
class UnusableKeyException : IOException("the saved key has characters an API key can't contain; paste it again")

/** Text about a failed call that is safe to show or log: never the key, at most 160 characters. */
fun safeFailureReason(e: Throwable?, key: String?): String {
    val raw = e?.message ?: e?.let { it::class.simpleName } ?: "failed"
    return (if (key.isNullOrEmpty()) raw else raw.replace(key, "[key]")).take(160)
}

/**
 * What went wrong with a cloud call, in the person's terms. The screen shows [message], never the
 * exception text; [safeFailureReason] stays for logs.
 */
enum class CloudFailure {
    /** Google refused the key (bad, revoked, or not allowed for this API). */
    KEY,
    /** The model name in Settings isn't one Google knows (retired or mistyped). */
    MODEL,
    /** Rate limit or quota. */
    LIMIT,
    /** The saved key has characters no API key has; nothing was sent. */
    UNUSABLE_KEY,
    /** No connection, DNS, timeout. */
    NETWORK,
    /** Anything else: a server error or an answer that couldn't be used. */
    OTHER;

    /** The sentence to show; [MODEL]'s takes the model name as its argument. */
    val message: Int get() = when (this) {
        KEY -> dev.roastbook.app.R.string.cloud_err_key
        MODEL -> dev.roastbook.app.R.string.cloud_err_model
        LIMIT -> dev.roastbook.app.R.string.cloud_err_limit
        UNUSABLE_KEY -> dev.roastbook.app.R.string.cloud_err_unusable
        NETWORK -> dev.roastbook.app.R.string.cloud_err_network
        OTHER -> dev.roastbook.app.R.string.cloud_err_other
    }

    companion object {
        fun of(e: Throwable?): CloudFailure = when (e) {
            is UnusableKeyException -> UNUSABLE_KEY
            is GeminiRequestException -> when (e.code) {
                // Google answers a bad key with 400 and the reason API_KEY_INVALID, but it also uses 400
                // for a request it can't use and for unmet account preconditions; only the first is the key.
                400 -> if (e.reason?.startsWith("API_KEY_") == true) KEY else OTHER
                401, 403 -> KEY
                404 -> MODEL
                429 -> LIMIT
                else -> OTHER
            }
            is IOException -> NETWORK
            else -> OTHER
        }
    }
}

/** A model answer, and whether the response shows a Google Search actually ran for it. */
data class GeminiAnswer(val text: String, val searched: Boolean)

/**
 * The thinnest useful client for `generateContent`. The key travels in a header, never a URL, so
 * it cannot land in a log line; responses are not logged either. No SDK: one endpoint, one shape.
 */
@Singleton
class GeminiClient @Inject constructor() {
    var baseUrl: String = "https://generativelanguage.googleapis.com"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Images + text in, the first candidate's text out. [grounded] adds Google Search as a tool. */
    suspend fun generate(
        apiKey: String,
        model: String,
        images: List<Bitmap>,
        prompt: String,
        grounded: Boolean = false,
        jsonOut: Boolean = true,
    ): String =
        generateAnswer(apiKey, model, images, prompt, grounded, jsonOut).text

    /** As [generate], plus whether the response carries search evidence (grounding metadata). */
    suspend fun generateAnswer(
        apiKey: String,
        model: String,
        images: List<Bitmap>,
        prompt: String,
        grounded: Boolean = false,
        jsonOut: Boolean = true,
    ): GeminiAnswer =
        withContext(Dispatchers.IO) {
            checkKeyChars(apiKey)
            val body = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("parts", buildJsonArray {
                            images.forEach { bmp ->
                                add(buildJsonObject {
                                    put("inline_data", buildJsonObject { put("mime_type", "image/jpeg"); put("data", bmp.toJpegBase64()) })
                                })
                            }
                            add(buildJsonObject { put("text", prompt) })
                        })
                    })
                })
                if (grounded) put("tools", buildJsonArray { add(buildJsonObject { put("google_search", buildJsonObject {}) }) })
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 1024)
                    // Search grounding and a forced JSON MIME type do not combine; the prompt asks for JSON instead.
                    if (jsonOut && !grounded) put("responseMimeType", "application/json")
                })
            }
            val request = Request.Builder()
                .url("$baseUrl/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
                .build()
            val (code, text) = http.newCall(request).await()
            if (code !in 200..299) throw GeminiRequestException(code, errorMessage(text), errorReason(text))
            GeminiAnswer(parseText(text), searched = grounded && hasSearchEvidence(text))
        }

    /** Cheap key check: does the model exist for this key? */
    suspend fun checkKey(apiKey: String, model: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            checkKeyChars(apiKey)
            val request = Request.Builder().url("$baseUrl/v1beta/models/$model").header("x-goog-api-key", apiKey).get().build()
            val (code, text) = http.newCall(request).await()
            if (code !in 200..299) throw GeminiRequestException(code, errorMessage(text), errorReason(text))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * OkHttp's header check throws with the whole header value in its message for a character a
     * header can't carry (a zero-width space pasted with the key, say), and that message would end
     * up on screen or in a log. Refuse such a key before it reaches a header.
     */
    private fun checkKeyChars(apiKey: String) {
        if (apiKey.isEmpty() || apiKey.any { it !in '!'..'~' }) throw UnusableKeyException()
    }

    /**
     * Runs the call off the caller's thread and cancels it when the caller is canceled (leaving a
     * screen, removing the key), instead of letting a blocking call finish and its answer land.
     */
    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                runCatching { response.use { it.code to (it.body?.string() ?: "") } }
                    .fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }

    /** A grounded answer counts as searched only when the response says a search ran. */
    internal fun hasSearchEvidence(responseJson: String): Boolean = runCatching {
        val meta = json.parseToJsonElement(responseJson).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("groundingMetadata")?.jsonObject
            ?: return@runCatching false
        listOf("groundingChunks", "webSearchQueries", "groundingSupports").any { (meta[it] as? JsonArray)?.isNotEmpty() == true }
    }.getOrDefault(false)

    internal fun parseText(responseJson: String): String {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val candidates = root["candidates"] as? JsonArray ?: return ""
        val first = candidates.firstOrNull()?.jsonObject ?: return ""
        val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray ?: return ""
        return parts.mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.content }.joinToString("\n")
    }

    private fun errorMessage(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(200)

    /** The first `reason` in the error's details (Google's ErrorInfo), if any. */
    private fun errorReason(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("details")?.jsonArray
            ?.firstNotNullOfOrNull { (it.jsonObject["reason"] as? JsonPrimitive)?.content }
    }.getOrNull()

    private fun Bitmap.toJpegBase64(): String {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
