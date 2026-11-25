package dev.roastbook.app.extraction

import dev.roastbook.app.cloud.CloudFailure

/** The tiers of the extraction chain, in the order they are tried. */
enum class Tier(val key: String) { NANO("nano"), LOCAL("local"), CLOUD("cloud"), GUESS("guess") }

/** What one tier did on one read. Stored lowercase in [ExtractionTrail]. */
enum class TierOutcome {
    /** Produced the fields that were kept. */
    READ,
    /** This device cannot run it (no on-device AI, too little memory, unsupported CPU). */
    UNSUPPORTED,
    /** The model is still downloading (Nano's first use): the row is parked for a retry. */
    PREPARING,
    /** Gave up waiting for the model download; the read fell through to the next tier. */
    NOT_READY,
    /** Available but not set up by the user (the on-device reader is not downloaded). */
    ABSENT,
    /** Not configured (no cloud key). */
    OFF,
    /** Threw, timed out, or answered with something unparseable twice. */
    FAILED,
    /** Answered, but with no fields. */
    EMPTY,
    /** Not tried on this run (a background run leaves Nano to the foreground, or an earlier tier read it). */
    SKIPPED,
}

/**
 * The per-tier record of the last read, e.g. `nano=unsupported;local=absent;cloud=off;guess=4`.
 * The guess entry carries the number of fields it filled. Kept as one short string column so the
 * "what happened" line can be derived without a column per tier.
 *
 * [cloudFailure] is why the Gemini call failed (`cloudwhy=key`), so the card can name the fix. A
 * row from before it was recorded, or a call that timed out, has none. [cloudModel] is the model
 * Google didn't know (`cloudmodel=...`), kept only when it is a plain model id: a mistyped name
 * with other characters is left out rather than allowed to break the entries around it.
 */
data class ExtractionTrail(
    val outcomes: Map<Tier, TierOutcome> = emptyMap(),
    val guessedFields: Int = 0,
    val cloudFailure: CloudFailure? = null,
    val cloudModel: String? = null,
    /** Nano's reading was kept but was poor (not the JSON asked for, or neither roaster nor coffee). */
    val nanoPoor: Boolean = false,
) {
    operator fun get(tier: Tier): TierOutcome? = outcomes[tier]

    fun with(tier: Tier, outcome: TierOutcome) = copy(outcomes = outcomes + (tier to outcome))

    fun encode(): String = buildList {
        Tier.entries.forEach { t ->
            if (t == Tier.GUESS) { if (outcomes[t] != null) add("guess=$guessedFields") }
            else outcomes[t]?.let { add("${t.key}=${it.name.lowercase()}") }
        }
        cloudFailure?.let { add("$CLOUD_WHY=${it.name.lowercase()}") }
        cloudModel?.takeIf { MODEL_ID.matches(it) }?.let { add("$CLOUD_MODEL=$it") }
        if (nanoPoor) add("$NANO_QUALITY=poor")
    }.joinToString(";")

    companion object {
        private const val CLOUD_WHY = "cloudwhy"
        private const val CLOUD_MODEL = "cloudmodel"
        private const val NANO_QUALITY = "nanoq"
        private val MODEL_ID = Regex("[A-Za-z0-9._/-]{1,64}")

        fun decode(s: String?): ExtractionTrail {
            if (s.isNullOrBlank()) return ExtractionTrail()
            var guessed = 0
            var cloudWhy: CloudFailure? = null
            var cloudModel: String? = null
            var nanoPoor = false
            val map = linkedMapOf<Tier, TierOutcome>()
            for (part in s.split(';')) {
                val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                if (k.trim() == CLOUD_WHY) { cloudWhy = CloudFailure.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) }; continue }
                if (k.trim() == NANO_QUALITY) { nanoPoor = v.trim() == "poor"; continue }
                if (k.trim() == CLOUD_MODEL) { cloudModel = v.trim().takeIf { MODEL_ID.matches(it) }; continue }
                val tier = Tier.entries.firstOrNull { it.key == k.trim() } ?: continue
                if (tier == Tier.GUESS) {
                    guessed = v.trim().toIntOrNull() ?: 0
                    map[tier] = if (guessed > 0) TierOutcome.READ else TierOutcome.EMPTY
                } else {
                    TierOutcome.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) }?.let { map[tier] = it }
                }
            }
            return ExtractionTrail(map, guessed, cloudWhy, cloudModel, nanoPoor)
        }
    }
}
