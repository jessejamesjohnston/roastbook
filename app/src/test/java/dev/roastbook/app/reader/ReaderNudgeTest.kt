package dev.roastbook.app.reader

import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.extraction.ExtractionTrail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a coffee's page offers the on-device reader after a poor built-in reading. */
class ReaderNudgeTest {
    private fun reader(phase: DownloadPhase = DownloadPhase.NONE, eligibility: Eligibility = Eligibility.ELIGIBLE) = ReaderState(
        eligibility = eligibility, phase = phase, downloadedBytes = 0, totalBytes = 100, sizeOnDisk = 0,
        allowMetered = false, nanoFailing = false, failure = null, modelName = "m",
    )
    private val poorNano = ExtractionTrail.decode("nano=read;nanoq=poor")
    private fun offer(
        source: ExtractionSource = ExtractionSource.NANO, trail: ExtractionTrail = poorNano, state: CoffeeState = CoffeeState.LOGGED,
        r: ReaderState? = reader(), s: ReaderSettings? = ReaderSettings(), id: String = "c1",
    ) = ReaderNudge.shouldOffer(id, state, source, trail, r, s)

    @Test fun aPoorBuiltInReadingOnADeviceThatCanRunTheReaderIsOffered() {
        assertTrue(offer())
        // A built-in read that failed outright (timeout, threw, nothing parsed) and fell to the guess.
        assertTrue(offer(ExtractionSource.OCR, ExtractionTrail.decode("nano=failed;local=absent;cloud=off;guess=0")))
        assertTrue(offer(ExtractionSource.NONE, ExtractionTrail.decode("nano=empty;local=absent")))
        // A failed earlier download can be offered again.
        assertTrue(offer(r = reader(DownloadPhase.FAILED)))
    }

    @Test fun aGoodOrNonBuiltInReadingIsNotOffered() {
        assertFalse(offer(trail = ExtractionTrail.decode("nano=read")))
        assertFalse(offer(ExtractionSource.LOCAL, ExtractionTrail.decode("nano=empty;nanoq=poor;local=read")))
        assertFalse(offer(ExtractionSource.CLOUD, ExtractionTrail.decode("nano=failed;local=absent;cloud=read")))
        // No built-in AI at all: Settings and the card's hint already cover it.
        assertFalse(offer(ExtractionSource.OCR, ExtractionTrail.decode("nano=unsupported;local=absent;cloud=off;guess=2")))
        assertFalse(offer(state = CoffeeState.EXTRACTING))
    }

    @Test fun onlyWhereTheReaderCanRunAndIsNotAlreadyComing() {
        assertFalse(offer(r = reader(eligibility = Eligibility.LOW_MEMORY)))
        assertFalse(offer(r = reader(eligibility = Eligibility.UNSUPPORTED_CPU)))
        for (p in listOf(DownloadPhase.READY, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.WAITING_FOR_NETWORK)) {
            assertFalse(p.name, offer(r = reader(p)))
        }
        assertFalse(offer(r = null))
        assertFalse(offer(s = null))
    }

    @Test fun notNowIsRespectedPerBagAndTwiceStopsItEverywhere() {
        assertFalse(offer(s = ReaderSettings(offerDeclines = 1, offerDeclinedIds = setOf("c1"))))
        assertTrue(offer(s = ReaderSettings(offerDeclines = 1, offerDeclinedIds = setOf("c1")), id = "c2"))
        assertFalse(offer(s = ReaderSettings(offerDeclines = 2, offerDeclinedIds = setOf("c1", "c3")), id = "c2"))
    }

    @Test fun afterAYesTheBagWaitsForTheReaderInsteadOfBeingOfferedAgain() {
        val yes = ReaderSettings(preferReader = true, pendingRereads = setOf("c1"))
        assertFalse(offer(s = yes))
        // Every phase, READY included: until its read starts, the bag's page says it is being read again.
        assertTrue(ReaderNudge.waitingForReader("c1", yes))
        assertFalse(ReaderNudge.waitingForReader("c2", yes))
        assertFalse(ReaderNudge.waitingForReader("c1", null))
        assertFalse("a cancel empties the list, and the card goes", ReaderNudge.waitingForReader("c1", yes.copy(pendingRereads = emptySet())))
    }
}
