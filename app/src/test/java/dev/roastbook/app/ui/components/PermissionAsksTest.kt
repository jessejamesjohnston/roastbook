package dev.roastbook.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** When to explain, ask again, or send the person to Android's Settings. */
class PermissionAsksTest {
    private fun decide(granted: Boolean = false, asked: Boolean = false, blocked: Boolean = false) = PermissionAsks.decide(granted, asked, blocked)

    @Test fun neverAskedExplainsFirst() = assertEquals(PermissionAsk.FIRST_ASK, decide())

    @Test fun grantedWinsOverAnyRecord() = assertEquals(PermissionAsk.GRANTED, decide(granted = true, asked = true, blocked = true))

    @Test fun askedAndNotRefusedForGoodAsksAgain() {
        // Covers a one-time grant that expired: asked before, not granted now, never refused for good.
        assertEquals(PermissionAsk.ASK_AGAIN, decide(asked = true))
    }

    @Test fun refusedForGoodOpensSettings() = assertEquals(PermissionAsk.BLOCKED, decide(asked = true, blocked = true))

    @Test fun aFirstRefusalIsNotForGood() =
        assertEquals(false to true, PermissionAsks.afterAnswer(granted = false, rationale = true, refusedBefore = false))

    @Test fun aSecondRefusalWithoutRationaleIsForGood() =
        assertEquals(true to true, PermissionAsks.afterAnswer(granted = false, rationale = false, refusedBefore = true))

    @Test fun aDismissedFirstDialogIsNotForGood() {
        // Tapping outside the dialog: denied, no rationale, but nothing was refused before.
        assertEquals(false to false, PermissionAsks.afterAnswer(granted = false, rationale = false, refusedBefore = false))
    }

    @Test fun aDismissedSecondDialogIsNotForGood() =
        assertEquals(false to true, PermissionAsks.afterAnswer(granted = false, rationale = true, refusedBefore = true))

    @Test fun allowingClearsTheRecord() =
        assertEquals(false to false, PermissionAsks.afterAnswer(granted = true, rationale = false, refusedBefore = true))
}
