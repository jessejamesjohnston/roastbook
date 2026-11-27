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
        assertEquals("720dp", layout!!.getAttributeNS(ns, "defaultWidth"))
        assertEquals("640dp", layout.getAttributeNS(ns, "defaultHeight"))
        assertEquals("320dp", layout.getAttributeNS(ns, "minWidth"))
        assertEquals("320dp", layout.getAttributeNS(ns, "minHeight"))
    }

    @Test fun noOrientationOrAspectRatioIsLocked() {
        val a = mainActivity()
        for (attr in listOf("screenOrientation", "maxAspectRatio", "minAspectRatio")) {
            assertNull(attr, a.getAttributeNodeNS(ns, attr))
        }
    }
}
