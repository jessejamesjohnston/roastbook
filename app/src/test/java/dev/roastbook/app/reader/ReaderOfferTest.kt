package dev.roastbook.app.reader

import dev.roastbook.app.extraction.NanoStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderOfferTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun eightGigabytePhonesQualifyAndSixDoNot() {
        // What totalMem reports, not the number on the box.
        assertEquals(Eligibility.ELIGIBLE, DeviceProfile.eligibility((7.3 * gib).toLong(), listOf("arm64-v8a")))
        assertEquals(Eligibility.ELIGIBLE, DeviceProfile.eligibility((7.1 * gib).toLong(), listOf("arm64-v8a")))
        assertEquals(Eligibility.ELIGIBLE, DeviceProfile.eligibility((11.2 * gib).toLong(), listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(Eligibility.LOW_MEMORY, DeviceProfile.eligibility((5.6 * gib).toLong(), listOf("arm64-v8a")))
        assertEquals(Eligibility.LOW_MEMORY, DeviceProfile.eligibility((3.7 * gib).toLong(), listOf("arm64-v8a")))
    }

    @Test fun onlyShippedAbisQualify() {
        assertEquals(Eligibility.UNSUPPORTED_CPU, DeviceProfile.eligibility(12 * gib, listOf("armeabi-v7a", "armeabi")))
        assertEquals(Eligibility.ELIGIBLE, DeviceProfile.eligibility(12 * gib, listOf("x86_64", "x86")))
    }

    private val E = Eligibility.ELIGIBLE

    @Test fun hiddenWhereTheDevicesOwnAiReadsBags() {
        // A device with Nano ready (a Pixel with AICore), or with Nano on its way: no reader row.
        assertEquals(ReaderOffer.HIDDEN, ReaderOffer.of(E, NanoStatus.AVAILABLE, nanoFailing = false, hasReaderFiles = false))
        assertEquals(ReaderOffer.HIDDEN, ReaderOffer.of(E, NanoStatus.DOWNLOADABLE, nanoFailing = false, hasReaderFiles = false))
        // Small and unsupported devices with Nano don't get the "can't run it" card either.
        assertEquals(ReaderOffer.HIDDEN, ReaderOffer.of(Eligibility.LOW_MEMORY, NanoStatus.AVAILABLE, nanoFailing = false, hasReaderFiles = false))
        assertEquals(ReaderOffer.HIDDEN, ReaderOffer.of(Eligibility.UNSUPPORTED_CPU, NanoStatus.DOWNLOADABLE, nanoFailing = false, hasReaderFiles = false))
    }

    @Test fun aStatusThatDidNotAnswerInTimeIsNotUnavailable() {
        // The status call waits behind a read in progress; that must not flash the row on a Nano device.
        assertEquals(ReaderOffer.HIDDEN, ReaderOffer.of(E, null, nanoFailing = false, hasReaderFiles = false))
        // But a recorded failure still wins.
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, null, nanoFailing = true, hasReaderFiles = false))
    }

    @Test fun offeredWhereNanoIsMissingOrFailing() {
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, NanoStatus.UNAVAILABLE, nanoFailing = false, hasReaderFiles = false))
        // Nano reported available, then failed here: the reader is the way out.
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, NanoStatus.AVAILABLE, nanoFailing = true, hasReaderFiles = false))
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, NanoStatus.DOWNLOADABLE, nanoFailing = true, hasReaderFiles = false))
    }

    @Test fun readerFilesOnDiskAlwaysShowTheirControls() {
        // Downloaded (or partly downloaded) on a device where Nano works: still shown, so it can be deleted.
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, NanoStatus.AVAILABLE, nanoFailing = false, hasReaderFiles = true))
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, NanoStatus.DOWNLOADABLE, nanoFailing = false, hasReaderFiles = true))
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, null, nanoFailing = false, hasReaderFiles = true))
    }

    @Test fun whatCountsAsReaderFiles() {
        fun state(phase: DownloadPhase, downloaded: Long = 0, onDisk: Long = 0) = ReaderState(
            eligibility = E, phase = phase, downloadedBytes = downloaded, totalBytes = 100, sizeOnDisk = onDisk,
            allowMetered = false, nanoFailing = false, failure = null, modelName = "m",
        )
        assertEquals(false, ReaderOffer.hasReaderFiles(state(DownloadPhase.NONE)))
        assertEquals(true, ReaderOffer.hasReaderFiles(state(DownloadPhase.READY, onDisk = 100)))
        assertEquals(true, ReaderOffer.hasReaderFiles(state(DownloadPhase.WAITING_FOR_NETWORK)))
        assertEquals(true, ReaderOffer.hasReaderFiles(state(DownloadPhase.DOWNLOADING, downloaded = 10, onDisk = 10)))
        assertEquals(true, ReaderOffer.hasReaderFiles(state(DownloadPhase.FAILED)))
        // A paused partial whose job record was pruned reports NONE but still holds the file.
        assertEquals(true, ReaderOffer.hasReaderFiles(state(DownloadPhase.NONE, downloaded = 40, onDisk = 40)))
        // A leftover runtime cache counts too.
        assertEquals(true, ReaderOffer.hasReaderFiles(state(DownloadPhase.NONE, onDisk = 5)))
    }

    @Test fun smallDevicesGetThePlainExplanation() {
        assertEquals(ReaderOffer.CANT_MEMORY, ReaderOffer.of(Eligibility.LOW_MEMORY, NanoStatus.UNAVAILABLE, nanoFailing = false, hasReaderFiles = false))
        assertEquals(ReaderOffer.CANT_MEMORY, ReaderOffer.of(Eligibility.LOW_MEMORY, NanoStatus.AVAILABLE, nanoFailing = true, hasReaderFiles = false))
        assertEquals(ReaderOffer.CANT_CPU, ReaderOffer.of(Eligibility.UNSUPPORTED_CPU, NanoStatus.UNAVAILABLE, nanoFailing = false, hasReaderFiles = false))
    }

    @Test fun probeMapsThrowsToUnavailableAndSlownessToUnknown() = kotlinx.coroutines.test.runTest {
        fun tier(block: suspend () -> NanoStatus) = object : dev.roastbook.app.extraction.NanoTier {
            override suspend fun status(): NanoStatus = block()
            override suspend fun prepare() {}
            override suspend fun generate(images: List<android.graphics.Bitmap>, prompt: String): String = ""
            override fun modelName(): String = "t"
        }
        assertEquals(NanoStatus.AVAILABLE, ReaderOffer.probe(tier { NanoStatus.AVAILABLE }))
        assertEquals(NanoStatus.UNAVAILABLE, ReaderOffer.probe(tier { error("no AICore") }))
        assertEquals(null, ReaderOffer.probe(tier { kotlinx.coroutines.delay(10_000); NanoStatus.AVAILABLE }, timeoutMs = 1_000))
    }

    @Test fun settingsOffersTheReaderWhereverItCanRun() {
        // Settings asks for the reader everywhere, so a device whose own AI works can still choose it.
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, NanoStatus.AVAILABLE, nanoFailing = false, hasReaderFiles = false, everywhere = true))
        assertEquals(ReaderOffer.OFFER, ReaderOffer.of(E, null, nanoFailing = false, hasReaderFiles = false, everywhere = true))
        // A device that can't run it, and whose own AI works, still shows nothing.
        assertEquals(ReaderOffer.HIDDEN, ReaderOffer.of(Eligibility.LOW_MEMORY, NanoStatus.AVAILABLE, nanoFailing = false, hasReaderFiles = false, everywhere = true))
    }
}
