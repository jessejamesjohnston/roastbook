package dev.roastbook.app.extraction

import com.google.mlkit.genai.prompt.GenerativeModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each built-in AI read runs on a client of its own, closed afterwards, whatever the read's outcome. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GeminiNanoTierTest {
    /** A stand-in client that records what was asked of it; its read fails as AICore's can. */
    private class Recorded {
        val calls = mutableListOf<String>()
        val client: GenerativeModel = Proxy.newProxyInstance(GenerativeModel::class.java.classLoader, arrayOf(GenerativeModel::class.java)) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "getBaseModelName" -> "nano-test"
                // A proxy can only throw unchecked exceptions; any failure ends the read the same way.
                "generateContent" -> throw IllegalStateException("refused")
                "close" -> Unit
                else -> error("unexpected call ${method.name}")
            }
        } as GenerativeModel
    }

    @Test fun everyReadOpensItsOwnClientAndClosesIt() = runBlocking {
        val made = mutableListOf<Recorded>()
        val tier = GeminiNanoTier { Recorded().also { made += it }.client }
        repeat(2) { runCatching { tier.generate(emptyList(), "prompt") }.also { assertTrue(it.exceptionOrNull() is IllegalStateException) } }
        assertEquals(2, made.size)
        assertNotSame(made[0].client, made[1].client)
        made.forEach { assertEquals(listOf("getBaseModelName", "generateContent", "close"), it.calls) }
        assertEquals("nano-test", tier.modelName())
    }
}
