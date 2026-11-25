package dev.roastbook.app.update

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** One GET to GitHub's latest-release endpoint, what each answer means, and what the request carries. */
class UpdateCheckerTest {
    private val server = MockWebServer().apply { start() }
    private val checker = UpdateChecker().apply { apiBase = server.url("/").toString() }

    @After fun tearDown() { server.shutdown() }

    private fun release(tag: String) = MockResponse().setBody("""{"tag_name":"$tag","name":"Roastbook $tag","assets":[]}""")

    @Test fun aNewerReleaseIsAvailableWithoutItsV() = runBlocking {
        server.enqueue(release("v0.4.0"))
        assertEquals(UpdateCheckResult.Available("0.4.0"), checker.check("0.3.5"))
    }

    @Test fun theInstalledVersionOrAnOlderReleaseIsUpToDate() = runBlocking {
        server.enqueue(release("v0.3.5"))
        server.enqueue(release("v0.3.4"))
        assertEquals(UpdateCheckResult.UpToDate, checker.check("0.3.5"))
        assertEquals(UpdateCheckResult.UpToDate, checker.check("0.3.5"))
    }

    @Test fun aRepositoryWithNoReleaseAnswers404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertEquals(UpdateCheckResult.NoRelease, checker.check("0.3.5"))
    }

    @Test fun aRateLimitOrServerErrorIsAFailedCheck() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateCheckResult.Failed, checker.check("0.3.5"))
        assertEquals(UpdateCheckResult.Failed, checker.check("0.3.5"))
    }

    @Test fun aTagThatIsNotAVersionOrABodyThatIsNotJsonIsAFailedCheck() = runBlocking {
        server.enqueue(release("nightly"))
        server.enqueue(MockResponse().setBody("<html>busy</html>"))
        server.enqueue(MockResponse().setBody("""{"name":"no tag"}"""))
        assertEquals(UpdateCheckResult.Failed, checker.check("0.3.5"))
        assertEquals(UpdateCheckResult.Failed, checker.check("0.3.5"))
        assertEquals(UpdateCheckResult.Failed, checker.check("0.3.5"))
    }

    @Test fun noConnectionIsOffline() = runBlocking {
        server.shutdown()
        assertEquals(UpdateCheckResult.Offline, checker.check("0.3.5"))
    }

    @Test fun theRequestAsksForTheLatestReleaseAndCarriesNothingAboutTheUserOrDevice() = runBlocking {
        server.enqueue(release("v0.4.0"))
        checker.check("0.3.5")
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", r.method)
        assertEquals("/repos/jessejamesjohnston/roastbook/releases/latest", r.path)
        assertEquals(0L, r.bodySize)
        assertNull(r.getHeader("Authorization"))
        assertNull(r.getHeader("Cookie"))
        assertEquals(UpdateChecker.USER_AGENT, r.getHeader("User-Agent"))
        val headers = r.headers.joinToString("\n") { "${it.first}: ${it.second}" }
        assertFalse("no app version in any header: $headers", headers.contains("0.3.5"))
        val names = r.headers.names().map { it.lowercase() }.toSet()
        assertEquals("only transport headers and the two we set", emptySet<String>(), names - setOf("accept", "user-agent", "host", "connection", "accept-encoding"))
    }

    @Test fun theReleasePageIsBuiltByTheAppNotTakenFromTheResponse() {
        assertEquals("https://github.com/jessejamesjohnston/roastbook/releases/latest", UpdateChecker.RELEASES_PAGE)
    }
}
