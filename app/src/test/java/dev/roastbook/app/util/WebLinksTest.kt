package dev.roastbook.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only web pages leave the app as links, whatever wrote the stored value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WebLinksTest {
    @Test fun webPagesOpen() {
        assertEquals("https://larkmoor.example/beans", WebLinks.uri(" https://larkmoor.example/beans ").toString())
        assertEquals("http://larkmoor.example", WebLinks.uri("http://larkmoor.example").toString())
        assertEquals("https://larkmoor.example", WebLinks.uri("larkmoor.example").toString())
    }

    @Test fun nothingElseDoes() {
        for (bad in listOf("tel:5551234", "market://details?id=x", "intent://x#Intent;end", "file:///data/x", "javascript:alert(1)", "content://x/y", "https://", "")) {
            assertNull(bad, WebLinks.uri(bad))
        }
        assertNull(WebLinks.uri(null))
    }
}
