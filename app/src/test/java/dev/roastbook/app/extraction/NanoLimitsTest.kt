package dev.roastbook.app.extraction

import com.google.mlkit.genai.common.GenAiException
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only AICore's usage-limit refusals wait and try again; every other failure is a failure. */
class NanoLimitsTest {
    /** Every error code ML Kit's GenAI library declares. */
    private val allCodes = GenAiException.ErrorCode::class.java.declaredFields
        .filter { it.type == Int::class.javaPrimitiveType }.associate { it.name to it.getInt(null) }

    @Test fun theTwoUsageLimitsWaitWhatAICoreAsksOrNothingWhenItNamesNoTime() {
        for (code in listOf(GenAiException.ErrorCode.BUSY, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)) {
            assertEquals(0L, NanoLimits.waitMs(GenAiException("out of usage quota", null, code)))
            assertEquals(150_000L, NanoLimits.waitMs(GenAiException("out of usage quota", null, code, Duration.ofSeconds(150))))
            assertEquals("a negative wait is no wait", 0L, NanoLimits.waitMs(GenAiException("x", null, code, Duration.ofSeconds(-5))))
        }
    }

    @Test fun everyOtherFailureIsNotAUsageLimit() {
        assert(allCodes.size > 10) { "only ${allCodes.size} codes found" }
        val limits = setOf(GenAiException.ErrorCode.BUSY, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)
        for ((name, code) in allCodes) {
            if (code in limits) continue
            assertNull(name, NanoLimits.waitMs(GenAiException("failed", null, code, Duration.ofSeconds(30))))
        }
        assertNull(NanoLimits.waitMs(IllegalStateException("out of usage quota")))
        assertNull(NanoLimits.waitMs(TransientExtractionException("background blocked")))
    }
}
