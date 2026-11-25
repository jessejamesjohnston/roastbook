package dev.roastbook.app.cloud

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/** A failed cloud call becomes a plain reason; the exception text never reaches the screen. */
class CloudFailureTest {
    @Test fun refusedKeyCodesAreTheKey() {
        assertEquals(CloudFailure.KEY, CloudFailure.of(GeminiRequestException(400, "API key not valid", reason = "API_KEY_INVALID")))
        listOf(401, 403).forEach { assertEquals(CloudFailure.KEY, CloudFailure.of(GeminiRequestException(it, "permission denied"))) }
    }

    /** Google answers a malformed request or an unmet precondition (billing) with 400 too; that is not the key. */
    @Test fun aBadRequestWithoutAKeyReasonIsOther() {
        assertEquals(CloudFailure.OTHER, CloudFailure.of(GeminiRequestException(400, "Invalid JSON payload received")))
        assertEquals(CloudFailure.OTHER, CloudFailure.of(GeminiRequestException(400, "precondition", reason = "BILLING_DISABLED")))
    }

    @Test fun unknownModelIsTheModel() = assertEquals(CloudFailure.MODEL, CloudFailure.of(GeminiRequestException(404, "models/x is not found")))

    @Test fun rateLimitIsTheLimit() = assertEquals(CloudFailure.LIMIT, CloudFailure.of(GeminiRequestException(429, "quota")))

    @Test fun serverErrorIsOther() = assertEquals(CloudFailure.OTHER, CloudFailure.of(GeminiRequestException(503, "unavailable")))

    @Test fun badKeyCharactersAreUnusable() = assertEquals(CloudFailure.UNUSABLE_KEY, CloudFailure.of(UnusableKeyException()))

    @Test fun noConnectionIsNetwork() {
        assertEquals(CloudFailure.NETWORK, CloudFailure.of(UnknownHostException("generativelanguage.googleapis.com")))
        assertEquals(CloudFailure.NETWORK, CloudFailure.of(IOException("timeout")))
    }

    @Test fun anythingElseIsOther() {
        assertEquals(CloudFailure.OTHER, CloudFailure.of(IllegalStateException("unparseable")))
        assertEquals(CloudFailure.OTHER, CloudFailure.of(null))
    }
}
