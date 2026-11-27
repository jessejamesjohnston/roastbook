package dev.roastbook.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element

/**
 * On a laptop-class device (a Googlebook, a Chromebook) Roastbook opens as a normal, resizable
 * window of a sensible size, like the other apps, rather than full screen: it says it can resize,
 * names a default and a minimum window size, and locks no orientation or aspect ratio.
 */
class WindowDeclarationTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }

    private fun mainActivity(): Element {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        return (0 until activities.length).map { activities.item(it) as Element }.single { it.getAttributeNS(ns, "name") == ".MainActivity" }
    }

    @Test fun theMainWindowIsResizableWithADefaultAndMinimumSize() {
        val a = mainActivity()
        assertEquals("true", a.getAttributeNS(ns, "resizeableActivity"))
        val layout = a.getElementsByTagName("layout").item(0) as Element?
        assertNotNull("a <layout> with the window's default size", layout)
        assertEquals("1040dp", layout!!.getAttributeNS(ns, "defaultWidth"))
        assertEquals("700dp", layout.getAttributeNS(ns, "defaultHeight"))
        assertEquals("320dp", layout.getAttributeNS(ns, "minWidth"))
        assertEquals("320dp", layout.getAttributeNS(ns, "minHeight"))
    }

    /** Nothing in the app hides the system bars or the caption, or goes full screen (checklist rule 2). */
    @Test fun nothingHidesTheBarsOrGoesFullScreen() {
        val src = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val banned = listOf("hide(WindowInsets", "FLAG_FULLSCREEN", "windowFullscreen", "BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE")
        val hits = src.walk().filter { it.isFile && it.extension in setOf("kt", "xml") }
            .flatMap { f -> banned.filter { it in f.readText() }.map { "${f.name}: $it" } }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun noOrientationOrAspectRatioIsLocked() {
        val a = mainActivity()
        for (attr in listOf("screenOrientation", "maxAspectRatio", "minAspectRatio")) {
            assertNull(attr, a.getAttributeNodeNS(ns, attr))
        }
    }
}
