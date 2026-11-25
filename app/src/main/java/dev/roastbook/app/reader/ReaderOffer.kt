package dev.roastbook.app.reader

import dev.roastbook.app.extraction.NanoStatus
import dev.roastbook.app.extraction.NanoTier
import dev.roastbook.app.util.runCatchingCancellable
import kotlinx.coroutines.withTimeoutOrNull

/** What the Settings section (and onboarding) says about the on-device reader on this device. */
enum class ReaderOffer {
    /**
     * Nothing to show: the device's own AI (Gemini Nano) reads bags here, or is on its way, and no
     * reader files are on disk. A row that only says "nothing to download" is a no-op, so it isn't drawn.
     */
    HIDDEN,
    /** No working Nano (or reader files already on disk), and this device can run the reader: show the download and its controls. */
    OFFER,
    /** No working Nano, and too little memory for the reader: point to the cloud key and the guess. */
    CANT_MEMORY,
    /** No working Nano, and an unsupported CPU. */
    CANT_CPU;

    companion object {
        /**
         * How the ML Kit GenAI status maps (see [dev.roastbook.app.extraction.GeminiNanoTier.status]):
         * - AVAILABLE: Nano reads here. Hidden.
         * - DOWNLOADABLE (ML Kit's DOWNLOADABLE and DOWNLOADING): Nano is on its way, the extractor
         *   waits for it. Hidden.
         * - UNAVAILABLE (also a status call that threw): the reader is the fallback. Shown.
         * - null: the status call didn't answer in time. That is not "unavailable": the status call
         *   waits behind a read in progress, so a device with Nano would otherwise flash the row
         *   whenever Settings opens during a read. Hidden, like a working Nano.
         *
         * Nano that reported available but then failed here ([nanoFailing], recorded by the
         * extractor and cleared by its next success) always shows the reader, so a device whose Nano
         * stops working later still has a way to it. Reader files already on disk (a finished model,
         * a download in progress or paused, a partial file) always show the controls on a device
         * that can run the reader, so they can be canceled or deleted and nothing is stranded.
         */
        fun of(eligibility: Eligibility, nano: NanoStatus?, nanoFailing: Boolean, hasReaderFiles: Boolean): ReaderOffer {
            if (eligibility == Eligibility.ELIGIBLE && hasReaderFiles) return OFFER
            val nanoUsable = !nanoFailing && (nano == null || nano == NanoStatus.AVAILABLE || nano == NanoStatus.DOWNLOADABLE)
            if (nanoUsable) return HIDDEN
            return when (eligibility) {
                Eligibility.ELIGIBLE -> OFFER
                Eligibility.LOW_MEMORY -> CANT_MEMORY
                Eligibility.UNSUPPORTED_CPU -> CANT_CPU
            }
        }

        /** Anything of the reader's on this device: a model, a download job that isn't idle, a partial file, a runtime cache. */
        fun hasReaderFiles(s: ReaderState): Boolean = s.phase != DownloadPhase.NONE || s.downloadedBytes > 0 || s.sizeOnDisk > 0

        /** Nano's status for the reader's visibility: a call that throws is UNAVAILABLE, one that takes over [timeoutMs] is null (unknown). */
        suspend fun probe(nano: NanoTier, timeoutMs: Long = 5_000): NanoStatus? =
            runCatchingCancellable { withTimeoutOrNull(timeoutMs) { nano.status() } }.getOrElse { NanoStatus.UNAVAILABLE }
    }
}
