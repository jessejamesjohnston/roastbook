package dev.roastbook.app.extraction

import dev.roastbook.app.R
import dev.roastbook.app.cloud.CloudFailure
import dev.roastbook.app.data.db.ExtractionSource

/**
 * The calm "what happened" note on a coffee whose read fell back: which tier read it, why the
 * better ones didn't, whether the fields are a guess, and what would read bags like this one.
 * String resources only; the card renders them. Null when there is nothing worth saying (the
 * phone's own AI read it, as it should).
 */
data class WhatHappened(
    val line: Int,
    val guess: Int? = null,
    val hint: Int? = null,
    val offerReread: Boolean = true,
    val poor: Boolean = false,
    val lineArg: String? = null,
)

/**
 * Decides the [WhatHappened] note from a row's source tier and [ExtractionTrail] plus what is set
 * up on the device. Pure, so every combination is unit-testable.
 */
object WhatHappenedRules {
    /**
     * [readerOffered]: the on-device reader could run here but is not downloaded.
     * [nanoFailing]: the device's own AI has been failing here (Settings shows the reader then).
     * [hasKey]: a Gemini key is saved.
     *
     * The download hint points at Settings, so it appears only where Settings shows the reader:
     * Nano was unavailable for this read, or has been failing. A read that fell back because Nano
     * was still downloading gets no reader hint (Settings hides the reader there).
     */
    fun explain(
        source: ExtractionSource,
        trail: ExtractionTrail,
        hasOcrText: Boolean,
        readerOffered: Boolean,
        hasKey: Boolean,
        nanoFailing: Boolean = false,
    ): WhatHappened? {
        val tierOfSource = when (source) {
            ExtractionSource.NANO -> Tier.NANO
            ExtractionSource.LOCAL -> Tier.LOCAL
            ExtractionSource.CLOUD -> Tier.CLOUD
            else -> null
        }
        // A model read the bag earlier and the last reread got nothing better: the reading stays.
        if (tierOfSource != null && trail.outcomes.isNotEmpty() && trail[tierOfSource] != TierOutcome.READ) {
            return WhatHappened(R.string.what_kept_earlier)
        }
        val hint = when {
            readerOffered && (trail[Tier.NANO] == TierOutcome.UNSUPPORTED || nanoFailing) -> R.string.what_hint_download
            !hasKey -> R.string.what_hint_key
            else -> null
        }
        val nanoMissed = trail[Tier.NANO].let {
            it == TierOutcome.UNSUPPORTED || it == TierOutcome.FAILED ||
                it == TierOutcome.NOT_READY || it == TierOutcome.SKIPPED
        }
        // A poor read: no model's reading was kept, and one that could have read it failed this time
        // (a timeout, an empty answer, not ready yet), so reading it again may well do better.
        val poor = trail[Tier.NANO].let { it == TierOutcome.FAILED || it == TierOutcome.EMPTY || it == TierOutcome.NOT_READY } ||
            trail[Tier.LOCAL] == TierOutcome.FAILED || trail[Tier.CLOUD] == TierOutcome.FAILED
        // The model Google didn't know, named while the key that tried it is still saved.
        val unknownModel = trail.cloudModel?.takeIf { hasKey && trail[Tier.CLOUD] == TierOutcome.FAILED && trail.cloudFailure == CloudFailure.MODEL }
        return when (source) {
            ExtractionSource.NANO -> null
            ExtractionSource.LOCAL -> WhatHappened(R.string.what_local_read, offerReread = false)
            ExtractionSource.CLOUD -> if (nanoMissed) WhatHappened(R.string.what_cloud_read, offerReread = false) else null
            ExtractionSource.OCR -> WhatHappened(
                line = when {
                    trail.outcomes.isEmpty() -> R.string.what_ocr_generic
                    unknownModel != null -> R.string.what_ocr_cloud_model_named
                    trail[Tier.CLOUD] == TierOutcome.FAILED -> cloudFailedLine(trail.cloudFailure, hasKey)
                    trail[Tier.LOCAL] == TierOutcome.FAILED -> R.string.what_ocr_local_failed
                    // The reader was chosen over the built-in AI and found nothing: not "no built-in AI".
                    trail[Tier.NANO] == TierOutcome.SKIPPED && trail[Tier.LOCAL] == TierOutcome.EMPTY -> R.string.what_ocr_local_failed
                    trail[Tier.NANO] == TierOutcome.NOT_READY -> R.string.what_ocr_nano_not_ready
                    trail[Tier.NANO] == TierOutcome.FAILED || trail[Tier.NANO] == TierOutcome.EMPTY -> R.string.what_ocr_nano_failed
                    else -> R.string.what_ocr_nano_unsupported
                },
                guess = if (trail.guessedFields > 0) R.string.what_guess_filled else if (trail.outcomes.isEmpty()) null else R.string.what_guess_none,
                hint = hint,
                poor = poor,
                lineArg = unknownModel,
            )
            // Nothing kept: say what was found (no text, or text that matched no field) and offer a reread.
            ExtractionSource.NONE -> WhatHappened(if (hasOcrText) R.string.what_guess_none else R.string.what_nothing_readable, poor = poor)
        }
    }

    /**
     * Why the Gemini call failed, and what fixes it. A row from before the kind was recorded, a
     * timeout, or a key that has since been removed (its advice no longer applies) gets the
     * general line.
     */
    private fun cloudFailedLine(kind: CloudFailure?, hasKey: Boolean): Int = when (kind.takeIf { hasKey }) {
        CloudFailure.KEY -> R.string.what_ocr_cloud_key
        CloudFailure.MODEL -> R.string.what_ocr_cloud_model
        CloudFailure.LIMIT -> R.string.what_ocr_cloud_limit
        CloudFailure.UNUSABLE_KEY -> R.string.what_ocr_cloud_unusable
        CloudFailure.NETWORK -> R.string.what_ocr_cloud_network
        CloudFailure.OTHER, null -> R.string.what_ocr_cloud_failed
    }
}
