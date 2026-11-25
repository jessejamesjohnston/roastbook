package dev.roastbook.app.reader

import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.extraction.ExtractionTrail
import dev.roastbook.app.extraction.Tier
import dev.roastbook.app.extraction.TierOutcome

/**
 * When a coffee's page offers the on-device reader in place, right after this device's built-in AI
 * read the bag poorly. A device whose built-in AI reads badly then gets the same way out as one
 * without it: download the reader, which reads that bag again when it arrives.
 *
 * Poor means one of: the built-in AI's answer was not the JSON asked for, or it named neither the
 * roaster nor the coffee (both recorded as `nanoq=poor` on a reading that was kept), or it timed
 * out, threw, or found nothing (the read fell through).
 *
 * Not a nag: offered only where the reader can run and is not downloaded or downloading, never on
 * a coffee where the person said "Not now" or already said yes, and not at all after two "Not now"
 * answers. Settings keeps the reader either way.
 */
object ReaderNudge {
    const val MAX_DECLINES = 2

    fun poorBuiltInRead(source: ExtractionSource, trail: ExtractionTrail): Boolean = when (source) {
        ExtractionSource.LOCAL, ExtractionSource.CLOUD -> false
        ExtractionSource.NANO -> trail.nanoPoor && trail[Tier.NANO] == TierOutcome.READ
        ExtractionSource.OCR, ExtractionSource.NONE -> trail[Tier.NANO] == TierOutcome.FAILED || trail[Tier.NANO] == TierOutcome.EMPTY
    }

    fun shouldOffer(
        coffeeId: String, state: CoffeeState, source: ExtractionSource, trail: ExtractionTrail,
        reader: ReaderState?, settings: ReaderSettings?,
    ): Boolean {
        if (state == CoffeeState.EXTRACTING || reader == null || settings == null) return false
        if (reader.eligibility != Eligibility.ELIGIBLE) return false
        if (reader.phase != DownloadPhase.NONE && reader.phase != DownloadPhase.FAILED) return false
        if (settings.offerDeclines >= MAX_DECLINES) return false
        if (coffeeId in settings.offerDeclinedIds || coffeeId in settings.pendingRereads) return false
        return poorBuiltInRead(source, trail)
    }

    /** After a yes: the bag waits for the reader, so its page shows the download's progress. */
    fun waitingForReader(coffeeId: String, reader: ReaderState?, settings: ReaderSettings?): Boolean =
        reader != null && settings != null && coffeeId in settings.pendingRereads && reader.phase != DownloadPhase.READY
}
