package dev.roastbook.app.cloud

import android.util.Log
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.prefs.UserPrefs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How a lookup ended, so the screen can tell "nothing on the web" from "the call failed". */
sealed class LookupResult {
    object Found : LookupResult()
    object Nothing : LookupResult()
    object Skipped : LookupResult()
    data class Failed(val failure: CloudFailure, val model: String) : LookupResult()
}

/** What a grounded lookup may fill in. Suggestions, editable, never authoritative. */
data class Enrichment(val roasterUrl: String?, val productUrl: String?, val roasterCity: String?) {
    val isEmpty get() = roasterUrl == null && productUrl == null && roasterCity == null
}

/**
 * "Look up roaster": with a key, Gemini with Google Search grounding is asked for the roaster's
 * homepage, this coffee's product page, and the roaster's city. Tap-only: [lookUp] is called from
 * the button on Detail and nowhere else, so no roaster or coffee name leaves the device unless the
 * user asks. "Not this roaster" clears the suggestions so the button comes back.
 */
@Singleton
class Enricher @Inject constructor(
    private val dao: CoffeeDao,
    private val client: GeminiClient,
    private val secrets: SecretStore,
    private val prefs: UserPrefs,
) {
    /** The user tapped "Look up roaster": always asks, whatever an earlier lookup found. */
    suspend fun lookUp(coffeeId: String): LookupResult {
        val key = secrets.geminiKey() ?: return LookupResult.Skipped
        val c = dao.get(coffeeId) ?: return LookupResult.Skipped
        val roaster = c.roaster?.takeIf { it.isNotBlank() } ?: return LookupResult.Skipped
        return try {
            val model = prefs.current().geminiModel
            // Google Search grounding is a paid feature; a free-tier key gets 429 on it. Fall back to
            // the model's own knowledge and say so in the status, because that answer is unverified.
            // A grounded call that returns without search evidence counts as unverified too.
            var grounded = false
            // Removing the key in Settings cancels the call in flight; the lookup then ends quietly.
            val answer = try {
                secrets.cancelIfKeyRemoved {
                    try {
                        val first = client.generateAnswer(key, model, emptyList(), prompt(roaster, c.coffeeName), grounded = true, jsonOut = false)
                        grounded = first.searched
                        first.text
                    } catch (e: GeminiRequestException) {
                        if (e.code != 429 && e.code != 400 && e.code != 403) throw e
                        Log.i(TAG, "grounded lookup refused (HTTP ${e.code}); asking without search")
                        grounded = false
                        // A key removed between the two calls sends nothing more.
                        val stillKey = secrets.geminiKey() ?: throw KeyRemovedException()
                        client.generate(
                            stillKey,
                            model,
                            emptyList(),
                            prompt(roaster, c.coffeeName, grounded = false),
                            grounded = false,
                            jsonOut = true,
                        )
                    }
                }
            } catch (e: KeyRemovedException) {
                return LookupResult.Skipped
            }
            if (dev.roastbook.app.BuildConfig.DEBUG) Log.i(TAG, "answer (${answer.length} chars): ${answer.take(400).replace('\n', ' ')}")
            // No candidate, a blocked prompt or a broken answer is a failed lookup, not "nothing found".
            val e = parse(answer) ?: run {
                Log.w(TAG, "enrichment for $coffeeId got no usable answer (${answer.length} chars)")
                return LookupResult.Failed(CloudFailure.OTHER, model)
            }
            val status = when { e.isEmpty -> STATUS_NOTHING; grounded -> STATUS_SUGGESTED; else -> STATUS_SUGGESTED_UNVERIFIED }
            // Merge into the row as it is NOW: a link the user typed during the lookup stays, and an
            // answer about a roaster or coffee the user has since renamed is dropped.
            val landed = dao.mutate(coffeeId) { now ->
                if (now.roaster != c.roaster || now.coffeeName != c.coffeeName) null
                else now.copy(
                    roasterUrl = now.roasterUrl ?: e.roasterUrl, productUrl = now.productUrl ?: e.productUrl,
                    roasterCity = now.roasterCity ?: e.roasterCity, enrichmentStatus = status,
                )
            }
            if (landed == null) return LookupResult.Skipped
            Log.i(TAG, "enriched $coffeeId: ${if (e.isEmpty) "nothing found" else if (grounded) "suggested" else "suggested (unverified)"}")
            if (e.isEmpty) LookupResult.Nothing else LookupResult.Found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network or key trouble: leave status alone so the button stays and the user can try again.
            // Release logs carry the kind of failure only; the screen gets a message with no key in it.
            Log.w(TAG, "enrichment failed for $coffeeId: ${e::class.simpleName}${(e as? GeminiRequestException)?.let { " (HTTP ${it.code})" } ?: ""}")
            LookupResult.Failed(CloudFailure.of(e), runCatching { prefs.current().geminiModel }.getOrDefault(""))
        }
    }

    /** "Not this roaster": forget the suggestions and let the next lookup start clean. */
    suspend fun reset(coffeeId: String) = dao.setEnrichment(coffeeId, null, null, null, null)

    private fun prompt(roaster: String, coffee: String?, grounded: Boolean = true) = buildString {
        append("You help a coffee journal find where a bag came from. ")
        append(if (grounded) "Search the web for" else "From what you know, identify")
        append(" the specialty coffee roaster named \"")
        append(roaster).append("\"")
        if (!coffee.isNullOrBlank()) append(" and, if it exists, their product page for the coffee called \"").append(coffee).append("\"")
        append(". Answer with ONLY a JSON object with these keys and nothing else: ")
        append("{\"roaster_url\": the roaster's official homepage or null, \"product_url\": the product page for this exact coffee or null, ")
        append("\"roaster_city\": the city (and country if outside the United States) where the roaster is based, or null}. ")
        append("Prefer the roaster's own site over marketplaces. Use null when you are not confident.")
    }

    companion object {
        private const val TAG = "Enrich"
        const val STATUS_SUGGESTED = "suggested"
        /** Came from the model without search grounding: plausible, not checked against the web. */
        const val STATUS_SUGGESTED_UNVERIFIED = "suggested-unverified"
        const val STATUS_NOTHING = "nothing"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Tolerant of fences and prose around the object. An answer in words with no object found
         * nothing; no answer at all, or an object cut off or broken, is null: there is no usable answer.
         */
        fun parse(answer: String): Enrichment? {
            if (answer.isBlank()) return null
            val start = answer.indexOf('{'); val end = answer.lastIndexOf('}')
            if (start < 0) return Enrichment(null, null, null)
            if (end <= start) return null
            val obj = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
            fun str(k: String): String? = obj[k]
                ?.takeIf { it !is JsonNull }
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" }
            fun url(k: String): String? = str(k)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            return Enrichment(url("roaster_url"), url("product_url"), str("roaster_city"))
        }
    }
}
