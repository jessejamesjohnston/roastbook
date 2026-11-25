package dev.roastbook.app.util

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for suspend code: a coroutine cancellation is control flow, not a failure, and
 * must reach the parent. Every `runCatching` around a suspend call in this app uses this instead.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
