package dev.roastbook.app.ui.detail

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.roastbook.app.R
import dev.roastbook.app.cloud.Enricher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Where to get it again": the roaster's links, the web search, and the keyed lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h2600dp")
class DetailScreenRoasterTest : DetailScreenTestBase(key = "FAKETestKey123") {
    private val server = MockWebServer()

    @Before fun startServer() {
        server.start()
        f.client.baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun stopServer() = server.shutdown()

    private fun answer(json: String) = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":${org.json.JSONObject.quote(json)}}]}}]}""",
    )

    /** The URL the last Custom Tab (or browser) was opened on. */
    private fun opened(): Uri? = shadowOf(compose.activity).nextStartedActivity?.data

    @Test fun aCoffeeWithNoRoasterHasNoWhereToGetItCard() {
        insert { copy(coffeeName = "Cloud Terrace") }
        show()
        compose.onNodeWithText(str(R.string.detail_roaster_links)).assertDoesNotExist()
    }

    @Test fun suggestedLinksShowWithTheCityAndOpenWhenTapped() {
        insert {
            copy(roaster = "Northwind", roasterUrl = "https://www.northwind.example/", productUrl = "https://northwind.example/cloud-terrace",
                roasterCity = "Portland, OR", enrichmentStatus = Enricher.STATUS_SUGGESTED)
        }
        show()
        compose.onNodeWithText(str(R.string.detail_roaster_links)).assertIsDisplayed()
        compose.onNodeWithText("northwind.example").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_roaster_city, "Portland, OR")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_links_suggested)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.detail_roaster_site)).performClick()
        assertEquals(Uri.parse("https://www.northwind.example/"), opened())
        compose.onNodeWithText(str(R.string.detail_product_page)).performClick()
        assertEquals(Uri.parse("https://northwind.example/cloud-terrace"), opened())
    }

    @Test fun wrongRoasterForgetsTheSuggestions() {
        insert { copy(roaster = "Northwind", roasterUrl = "https://northwind.example", enrichmentStatus = Enricher.STATUS_SUGGESTED_UNVERIFIED) }
        show()
        compose.onNodeWithText(str(R.string.detail_links_unverified)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_wrong_roaster)).performClick()
        compose.awaitUntil { row()?.roasterUrl == null }
        assertNull(row()!!.enrichmentStatus)
        compose.awaitUntil { compose.onNodeWithText(str(R.string.detail_look_up_roaster)).exists() }
    }

    @Test fun aLinkTheUserTypedShowsWithoutTheSuggestionNote() {
        insert { copy(roaster = "Northwind", roasterUrl = "https://northwind.example") }
        show()
        compose.onNodeWithText(str(R.string.detail_roaster_site)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_wrong_roaster)).assertDoesNotExist()
    }

    @Test fun findRoasterSearchesTheWebForTheRoasterAndCoffee() {
        insert { copy(roaster = "Northwind", coffeeName = "Cloud Terrace") }
        show()
        compose.onNodeWithText(str(R.string.detail_find_roaster)).performClick()
        val uri = opened()!!
        assertEquals("www.google.com", uri.host)
        assertEquals("Northwind Cloud Terrace coffee", uri.getQueryParameter("q"))
        assertEquals(0, server.requestCount)
    }

    @Test fun lookUpLandsTheLinksOnTheCard() {
        insert { copy(roaster = "Northwind") }
        server.enqueue(answer("""{"roaster_url":"https://northwind.example","product_url":null,"roaster_city":"Portland, OR"}"""))
        show()
        compose.onNodeWithText(str(R.string.detail_look_up_roaster)).performClick()
        compose.awaitUntil { compose.onNodeWithText(str(R.string.detail_roaster_site)).exists() }
        compose.onNodeWithText(str(R.string.detail_roaster_city, "Portland, OR")).assertIsDisplayed()
        // No search evidence came back, so the links are marked as unchecked.
        compose.onNodeWithText(str(R.string.detail_links_unverified)).assertIsDisplayed()
        assertEquals(1, server.requestCount)
    }

    @Test fun aLookUpThatFindsNothingSaysSo() {
        insert { copy(roaster = "Northwind") }
        server.enqueue(answer("""{"roaster_url":null,"product_url":null,"roaster_city":null}"""))
        show()
        compose.onNodeWithText(str(R.string.detail_look_up_roaster)).performClick()
        compose.awaitUntil { compose.onNodeWithText(str(R.string.detail_lookup_nothing)).exists() }
        assertEquals(Enricher.STATUS_NOTHING, row()!!.enrichmentStatus)
    }

    @Test fun anEarlierLookUpThatFoundNothingIsStillReported() {
        insert { copy(roaster = "Northwind", enrichmentStatus = Enricher.STATUS_NOTHING) }
        show()
        compose.onNodeWithText(str(R.string.detail_lookup_nothing)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_look_up_roaster)).assertIsDisplayed()
    }

    @Test fun aFailedLookUpShowsWhyAndKeepsTheButton() {
        insert { copy(roaster = "Northwind") }
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"backend error"}}"""))
        show()
        compose.onNodeWithText(str(R.string.detail_look_up_roaster)).performClick()
        compose.awaitUntil { compose.onNodeWithText(str(R.string.cloud_err_other)).exists() }
        compose.onNodeWithText(str(R.string.detail_look_up_roaster)).assertIsDisplayed()
        assertNull(row()!!.enrichmentStatus)
        assertTrue(server.requestCount >= 1)
    }
}
