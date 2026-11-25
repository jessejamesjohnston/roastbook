package dev.roastbook.app.ui.detail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A closed "what happened" note stays closed across restarts until that coffee is read again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReadNotesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun aClosedNoteStaysClosedAfterTheAppRestarts() {
        ReadNotes(context).dismiss("c1")
        val next = ReadNotes(context)
        assertTrue("c1" in next.dismissed.value)
        assertFalse("c2" in next.dismissed.value)
    }

    @Test fun readingAgainBringsTheNoteBackForThatCoffeeOnly() {
        val notes = ReadNotes(context)
        notes.dismiss("c1")
        notes.dismiss("c2")
        notes.clear("c1")
        assertEquals(setOf("c2"), notes.dismissed.value)
        assertEquals(setOf("c2"), ReadNotes(context).dismissed.value)
    }

    @Test fun readingACoffeeWhoseNoteWasNeverClosedChangesNothing() {
        val notes = ReadNotes(context)
        notes.dismiss("c2")
        notes.clear("c1")
        assertEquals(setOf("c2"), notes.dismissed.value)
    }
}
