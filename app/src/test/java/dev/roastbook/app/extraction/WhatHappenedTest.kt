package dev.roastbook.app.extraction

import dev.roastbook.app.R
import dev.roastbook.app.data.db.ExtractionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatHappenedTest {
    private fun t(s: String) = ExtractionTrail.decode(s)
    private fun explain(src: ExtractionSource, trail: String, text: Boolean = true, offered: Boolean = false, key: Boolean = false, failing: Boolean = false) =
        WhatHappenedRules.explain(src, t(trail), text, offered, key, failing)

    @Test fun theReaderHintAppearsOnlyWhereSettingsShowsTheReader() {
        // Nano was still downloading: Settings hides the reader, so the hint points at the key instead.
        assertEquals(R.string.what_hint_key, explain(ExtractionSource.OCR, "nano=not_ready;local=absent;cloud=off;guess=1", offered = true)!!.hint)
        // Nano failed here and the device records it as failing: Settings shows the reader.
        assertEquals(R.string.what_hint_download, explain(ExtractionSource.OCR, "nano=failed;local=absent;cloud=off;guess=1", offered = true, failing = true)!!.hint)
        // Nano failed once and has worked since (no longer failing): no reader hint.
        assertEquals(R.string.what_hint_key, explain(ExtractionSource.OCR, "nano=failed;local=absent;cloud=off;guess=1", offered = true, failing = false)!!.hint)
        // Not offered (already downloaded, or the device can't run it): never the reader hint.
        assertEquals(R.string.what_hint_key, explain(ExtractionSource.OCR, "nano=unsupported;local=absent;cloud=off;guess=1", offered = false, failing = true)!!.hint)
    }

    @Test fun trailRoundTrips() {
        val trail = ExtractionTrail().with(Tier.NANO, TierOutcome.UNSUPPORTED).with(Tier.LOCAL, TierOutcome.ABSENT).with(Tier.CLOUD, TierOutcome.OFF)
            .copy(guessedFields = 4).with(Tier.GUESS, TierOutcome.READ)
        assertEquals("nano=unsupported;local=absent;cloud=off;guess=4", trail.encode())
        assertEquals(trail, ExtractionTrail.decode(trail.encode()))
        assertEquals(ExtractionTrail(), ExtractionTrail.decode(null))
        assertEquals(ExtractionTrail(), ExtractionTrail.decode("garbage;x=y"))
    }

    @Test fun nanoReadSaysNothing() {
        assertNull(explain(ExtractionSource.NANO, "nano=read"))
        assertNull(explain(ExtractionSource.NANO, ""))
    }

    @Test fun guessOnAPhoneWithoutNano() {
        val w = explain(ExtractionSource.OCR, "nano=unsupported;local=unsupported;cloud=off;guess=3")!!
        assertEquals(R.string.what_ocr_nano_unsupported, w.line)
        assertEquals(R.string.what_guess_filled, w.guess)
        assertEquals(R.string.what_hint_key, w.hint)
        assertEquals(true, w.offerReread)
    }

    @Test fun guessWhereTheReaderCouldHelpPointsToIt() {
        assertEquals(R.string.what_hint_download, explain(ExtractionSource.OCR, "nano=unsupported;local=absent;cloud=off;guess=2", offered = true)!!.hint)
    }

    @Test fun guessWithAKeyAndNoReaderHasNoHint() {
        assertNull(explain(ExtractionSource.OCR, "nano=unsupported;local=unsupported;cloud=failed;guess=2", key = true)!!.hint)
    }

    @Test fun aFailedCloudReadNamesWhatWentWrongAndTheFix() {
        fun line(why: String, key: Boolean = true) = explain(ExtractionSource.OCR, "nano=unsupported;local=unsupported;cloud=failed;${why}guess=1", key = key)!!.line
        assertEquals(R.string.what_ocr_cloud_key, line("cloudwhy=key;"))
        assertEquals(R.string.what_ocr_cloud_model, line("cloudwhy=model;"))
        assertEquals(R.string.what_ocr_cloud_limit, line("cloudwhy=limit;"))
        assertEquals(R.string.what_ocr_cloud_unusable, line("cloudwhy=unusable_key;"))
        assertEquals(R.string.what_ocr_cloud_network, line("cloudwhy=network;"))
        assertEquals(R.string.what_ocr_cloud_failed, line("cloudwhy=other;"))
        // A row from before the kind was recorded, a timeout (no kind), or a kind this build doesn't know.
        assertEquals(R.string.what_ocr_cloud_failed, line(""))
        assertEquals(R.string.what_ocr_cloud_failed, line("cloudwhy=someday;"))
        // The key was removed since: advice about it no longer applies (removing it mid-call reads as network).
        assertEquals(R.string.what_ocr_cloud_failed, line("cloudwhy=key;", key = false))
        assertEquals(R.string.what_ocr_cloud_failed, line("cloudwhy=network;", key = false))
    }

    @Test fun anUnknownModelIsNamedWhenTheReadRecordedIt() {
        fun read(why: String, key: Boolean = true) = explain(ExtractionSource.OCR, "nano=unsupported;local=unsupported;cloud=failed;${why};guess=1", key = key)!!
        read("cloudwhy=model;cloudmodel=gemini-3.8-flsh").let {
            assertEquals(R.string.what_ocr_cloud_model_named, it.line)
            assertEquals("gemini-3.8-flsh", it.lineArg)
        }
        // No name recorded (an older row, or one that wasn't a plain model id): the unnamed line.
        read("cloudwhy=model").let { assertEquals(R.string.what_ocr_cloud_model, it.line); assertNull(it.lineArg) }
        read("cloudwhy=model;cloudmodel=gemini flash").let { assertEquals(R.string.what_ocr_cloud_model, it.line); assertNull(it.lineArg) }
        // The key is gone since: the general line, no name.
        read("cloudwhy=model;cloudmodel=gemini-3.8-flsh", key = false).let { assertEquals(R.string.what_ocr_cloud_failed, it.line); assertNull(it.lineArg) }
    }

    @Test fun onlyAPlainModelIdIsWrittenIntoTheTrail() {
        val failed = ExtractionTrail().with(Tier.CLOUD, TierOutcome.FAILED).copy(cloudFailure = dev.roastbook.app.cloud.CloudFailure.MODEL)
        val named = failed.copy(cloudModel = "models/gemini-3.8-flsh")
        assertEquals("cloud=failed;cloudwhy=model;cloudmodel=models/gemini-3.8-flsh", named.encode())
        assertEquals(named, ExtractionTrail.decode(named.encode()))
        // A typed name with the trail's separators is left out, and the entries around it survive.
        assertEquals("cloud=failed;cloudwhy=model", failed.copy(cloudModel = "flash;cloud=read").encode())
        assertEquals("cloud=failed;cloudwhy=model", failed.copy(cloudModel = "x".repeat(65)).encode())
    }

    @Test fun theCloudFailureKindRoundTripsBesideTheTiers() {
        val trail = ExtractionTrail().with(Tier.NANO, TierOutcome.UNSUPPORTED).with(Tier.CLOUD, TierOutcome.FAILED)
            .copy(cloudFailure = dev.roastbook.app.cloud.CloudFailure.UNUSABLE_KEY)
        assertEquals("nano=unsupported;cloud=failed;cloudwhy=unusable_key", trail.encode())
        assertEquals(trail, ExtractionTrail.decode(trail.encode()))
        // Recording a later tier keeps it.
        assertEquals(dev.roastbook.app.cloud.CloudFailure.UNUSABLE_KEY, trail.with(Tier.GUESS, TierOutcome.EMPTY).cloudFailure)
    }

    @Test fun theMostSpecificReasonIsNamed() {
        assertEquals(R.string.what_ocr_cloud_failed, explain(ExtractionSource.OCR, "nano=failed;local=failed;cloud=failed;guess=0")!!.line)
        assertEquals(R.string.what_ocr_local_failed, explain(ExtractionSource.OCR, "nano=unsupported;local=failed;cloud=off;guess=0")!!.line)
        assertEquals(R.string.what_ocr_nano_not_ready, explain(ExtractionSource.OCR, "nano=not_ready;local=unsupported;cloud=off;guess=1")!!.line)
        assertEquals(R.string.what_ocr_nano_failed, explain(ExtractionSource.OCR, "nano=failed;local=unsupported;cloud=off;guess=1")!!.line)
    }

    @Test fun nothingGuessedSaysSo() {
        assertEquals(R.string.what_guess_none, explain(ExtractionSource.OCR, "nano=unsupported;cloud=off;guess=0")!!.guess)
    }

    @Test fun rowsReadBeforeTheTrailExistedGetTheGenericLine() {
        val w = explain(ExtractionSource.OCR, "")!!
        assertEquals(R.string.what_ocr_generic, w.line)
        assertNull(w.guess)
    }

    @Test fun localAndCloudReadsExplainThemselvesWithoutAReread() {
        assertEquals(R.string.what_local_read, explain(ExtractionSource.LOCAL, "nano=unsupported;local=read")!!.line)
        assertEquals(false, explain(ExtractionSource.LOCAL, "nano=unsupported;local=read")!!.offerReread)
        assertEquals(R.string.what_cloud_read, explain(ExtractionSource.CLOUD, "nano=failed;local=absent;cloud=read")!!.line)
        assertNull(explain(ExtractionSource.CLOUD, "nano=empty;local=absent;cloud=read"))
    }

    @Test fun aFailedRereadOfAModelReadKeepsTheEarlierReading() {
        assertEquals(R.string.what_kept_earlier, explain(ExtractionSource.NANO, "nano=failed;local=unsupported;cloud=off;guess=0")!!.line)
    }

    @Test fun nothingReadable() {
        val w = explain(ExtractionSource.NONE, "nano=unsupported;guess=0", text = false)!!
        assertEquals(R.string.what_nothing_readable, w.line)
        assertEquals(true, w.offerReread)
        assertEquals(false, w.poor)
    }

    @Test fun textThatMatchedNoFieldStillOffersAReread() {
        // Every coffee that couldn't be read keeps its one Read again.
        val w = explain(ExtractionSource.NONE, "nano=unsupported;guess=0", text = true)!!
        assertEquals(R.string.what_guess_none, w.line)
        assertEquals(true, w.offerReread)
    }

    @Test fun aReadThatFellBackAfterAFailureIsPoor() {
        assertEquals(true, explain(ExtractionSource.OCR, "nano=failed;local=absent;cloud=off;guess=2")!!.poor)
        assertEquals(true, explain(ExtractionSource.OCR, "nano=empty;local=absent;cloud=off;guess=0")!!.poor)
        assertEquals(true, explain(ExtractionSource.OCR, "nano=not_ready;local=unsupported;cloud=off;guess=1")!!.poor)
        assertEquals(true, explain(ExtractionSource.OCR, "nano=unsupported;local=failed;cloud=off;guess=1")!!.poor)
        assertEquals(true, explain(ExtractionSource.OCR, "nano=unsupported;local=unsupported;cloud=failed;guess=1")!!.poor)
        assertEquals(true, explain(ExtractionSource.NONE, "nano=failed;local=absent;cloud=off;guess=0", text = false)!!.poor)
    }

    @Test fun aDeviceWithoutAnyReaderIsNotCalledPoorEveryTime() {
        // Reading again can't do better until a reader or a key is added, so no "didn't read well".
        assertEquals(false, explain(ExtractionSource.OCR, "nano=unsupported;local=unsupported;cloud=off;guess=3")!!.poor)
        assertEquals(false, explain(ExtractionSource.OCR, "")!!.poor)
    }

    @Test fun modelReadsAreNeverPoor() {
        assertEquals(false, explain(ExtractionSource.LOCAL, "nano=failed;local=read")!!.poor)
        assertEquals(false, explain(ExtractionSource.CLOUD, "nano=failed;local=absent;cloud=read")!!.poor)
        assertEquals(false, explain(ExtractionSource.NANO, "nano=failed;local=unsupported;cloud=off;guess=0")!!.poor)
    }

    @Test fun nanoIsAssumedHealthyUnlessSaidOtherwise() {
        val trail = t("nano=failed;local=absent;cloud=off;guess=1")
        assertEquals(
            WhatHappenedRules.explain(ExtractionSource.OCR, trail, true, true, false, nanoFailing = false),
            WhatHappenedRules.explain(ExtractionSource.OCR, trail, hasOcrText = true, readerOffered = true, hasKey = false),
        )
    }
}
