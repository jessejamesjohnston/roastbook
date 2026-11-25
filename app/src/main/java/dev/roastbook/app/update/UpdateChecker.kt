package dev.roastbook.app.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a check for a newer release found. */
sealed interface UpdateCheckResult {
    /** [version] (without its `v`) is newer than the installed one. */
    data class Available(val version: String) : UpdateCheckResult
    /** The latest release is the installed version or older. */
    data object UpToDate : UpdateCheckResult
    /** The repository has no published release yet (GitHub answers 404). */
    data object NoRelease : UpdateCheckResult
    /** No connection, or GitHub could not be reached. */
    data object Offline : UpdateCheckResult
    /** GitHub answered, but not with a release we can read (rate limit, server error, odd tag). */
    data object Failed : UpdateCheckResult
}

/**
 * Asks GitHub's public API for the latest Roastbook release and compares it with the installed
 * version. One unauthenticated GET; the request carries no cookies, no account, no device or app
 * version, only a fixed User-Agent (GitHub refuses requests without one). Nothing is downloaded:
 * when there is a newer version the app opens [RELEASES_PAGE] in the browser.
 */
@Singleton
class UpdateChecker @Inject constructor() {
    /** The API host; tests point it at a local server. */
    var apiBase: String = "https://api.github.com"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Compares the latest published release with [installedVersion]. Never throws. */
    suspend fun check(installedVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> UpdateCheckResult.NoRelease
                    !response.isSuccessful -> UpdateCheckResult.Failed
                    else -> {
                        val tag = runCatching {
                            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["tag_name"]?.jsonPrimitive?.content
                        }.getOrNull()
                        when (AppVersion.isNewer(tag, installedVersion)) {
                            true -> UpdateCheckResult.Available(AppVersion.display(tag!!))
                            false -> UpdateCheckResult.UpToDate
                            null -> UpdateCheckResult.Failed
                        }
                    }
                }
            }
        } catch (_: IOException) {
            UpdateCheckResult.Offline
        }
    }

    companion object {
        /** The public repository releases are published from. */
        const val REPO = "jessejamesjohnston/roastbook"
        /** Where a person reads about and downloads the newest release. Built here, never taken from a response. */
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
        const val USER_AGENT = "Roastbook"
    }
}
