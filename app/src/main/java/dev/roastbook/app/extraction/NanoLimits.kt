package dev.roastbook.app.extraction

import com.google.mlkit.genai.common.GenAiException

/**
 * The built-in AI's per-app usage limits. AICore meters each app: after several reads back to back
 * it refuses the next ones for a while (seen on a tablet after about four reads, clearing within 30
 * to 90 seconds), and it can refuse an app that has used too much battery. Neither says anything
 * about whether the model works, so a refused read waits and is tried again.
 */
object NanoLimits {
    /**
     * How long AICore asked to wait before the next read, in milliseconds (0 when it named no time),
     * when [e] is a usage-limit refusal; null for any other failure.
     */
    fun waitMs(e: Throwable): Long? {
        val refusal = e as? GenAiException ?: return null
        if (refusal.errorCode != GenAiException.ErrorCode.BUSY &&
            refusal.errorCode != GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
        ) return null
        // Declared non-null, but a refusal built without a delay may still carry none.
        return runCatching { refusal.retryDelay.toMillis() }.getOrNull()?.coerceAtLeast(0L) ?: 0L
    }
}
