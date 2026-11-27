package dev.roastbook.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every notification uses the dedicated one-color status icon, never the launcher artwork (app-standard section 12, rule 28). */
class NotificationIconTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test fun everySmallIconIsTheStatusIcon() {
        val uses = main.walk().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""setSmallIcon\(([^)]*)\)""").findAll(f.readText()).map { "${f.name}: ${it.groupValues[1]}" } }.toList()
        assertTrue("the app posts notifications", uses.isNotEmpty())
        assertEquals(emptyList<String>(), uses.filterNot { it.endsWith("R.drawable.ic_stat_roastbook") })
    }

    @Test fun theStatusIconIsOneColor() {
        val xml = File(main, "res/drawable/ic_stat_roastbook.xml").readText()
        val colors = Regex("""fillColor="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("#FFFFFFFF"), colors)
    }
}
