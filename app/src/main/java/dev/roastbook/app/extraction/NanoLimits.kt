package dev.roastbook.app.extraction

import com.google.mlkit.genai.common.GenAiException

/**
 * The built-in AI's per-app usage limits. AICore meters each app: after several reads back to back
 * it refuses the next ones for a while (seen on a tablet after about four reads, clearing within 30
 * to 90 seconds), and it can refuse an app that has used too much battery. Neither says anything
 * about whether the model works, so a refused read waits a little and is tried again.
 */
object NanoLimits {
    /** Which limit refused the read; each has its own sentence on the card. */
    enum class Kind { READS, BATTERY }

    /** A usage-limit refusal: which limit, and how long AICore asked to wait (0 when it named no time). */
    data class Refusal(val kind: Kind, val waitMs: Long)

    /** The refusal [e] stands for, or null for any other failure. */
    fun refusal(e: Throwable): Refusal? {
        val refused = e as? GenAiException ?: return null
        val kind = when (refused.errorCode) {
            GenAiException.ErrorCode.BUSY -> Kind.READS
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> Kind.BATTERY
            else -> return null
        }
        // Declared non-null, but a refusal built without a delay may still carry none.
        val wait = runCatching { refused.retryDelay.toMillis() }.getOrNull()?.coerceAtLeast(0L) ?: 0L
        return Refusal(kind, wait)
    }

    /** How long AICore asked to wait, in milliseconds, when [e] is a usage-limit refusal; null otherwise. */
    fun waitMs(e: Throwable): Long? = refusal(e)?.waitMs
}
