package dev.roastbook.app.cloud

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The key file is replaced in one step, so a process killed mid-save leaves the old key or the new one. */
class SecretFileTest {
    private val dir: File = Files.createTempDirectory("secrets").toFile()
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun aSaveReplacesTheOldContentAndLeavesNoTempFile() {
        val f = File(dir, "gemini.bin").apply { writeText("old") }
        writeReplacing(f, "new")
        assertEquals("new", f.readText())
        assertEquals(listOf("gemini.bin"), dir.list()!!.toList())
    }

    @Test fun theOldContentStaysUntilTheNewOneIsComplete() {
        val f = File(dir, "gemini.bin").apply { writeText("old") }
        // A save that dies while writing (here: the temp name is a directory, so writing it fails).
        File(dir, "gemini.bin.tmp").mkdir()
        runCatching { writeReplacing(f, "new") }
        assertEquals("old", f.readText())
    }

    @Test fun aFirstSaveCreatesTheFile() {
        val f = File(dir, "gemini.bin")
        writeReplacing(f, "first")
        assertEquals("first", f.readText())
        assertFalse(File(dir, "gemini.bin.tmp").exists())
    }
}
