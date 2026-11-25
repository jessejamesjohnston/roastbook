package dev.roastbook.app.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving a card writes only what the user typed over, never what a read filled in meanwhile. */
class EditSessionTest {
    private val opened = EditSession(coffeeId = "c", section = "facts", drafts = mapOf("country" to "", "roaster" to "Larkmoor"))

    @Test fun aFieldAReadFilledWhileTheCardWasOpenIsNotTheUsersEdit() {
        // The card opened with no origin; a read then filled it in. The user never touched it.
        assertFalse(userChanged(opened, "country", current = "Ethiopia"))
    }

    @Test fun aFieldTheUserTypedOverIsTheUsersEdit() {
        val typed = opened.copy(drafts = opened.drafts + ("roaster" to "Larkmoor Coffee"))
        assertTrue(userChanged(typed, "roaster", current = "Larkmoor"))
        // Even when a read changed the same field meanwhile, the user's text wins.
        assertTrue(userChanged(typed, "roaster", current = "Larkmoore"))
    }

    @Test fun aClearedFieldIsTheUsersEdit() {
        val cleared = opened.copy(drafts = opened.drafts + ("roaster" to ""))
        assertTrue(userChanged(cleared, "roaster", current = "Larkmoor"))
    }
}
