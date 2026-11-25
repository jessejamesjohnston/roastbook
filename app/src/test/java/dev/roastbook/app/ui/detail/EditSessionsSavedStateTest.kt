package dev.roastbook.app.ui.detail

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import dev.roastbook.app.ui.rating.RatingDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text typed into an open card outlives the process: Android saves the activity's state into a
 * Bundle, kills the app in the background, and hands the Bundle back when the user returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EditSessionsSavedStateTest {

    /** Saves [handle] the way the activity does, through a real Parcel, and builds the next process's handle. */
    private fun afterProcessDeath(handle: SavedStateHandle): SavedStateHandle {
        val saved: Bundle = handle.savedStateProvider().saveState()
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            val restored = parcel.readBundle(javaClass.classLoader)!!
            return SavedStateHandle.createHandle(restored, null)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun typedFactsSurviveProcessDeath() {
        val handle = SavedStateHandle()
        val before = EditSessions(handle)
        before.start(EditSession("c1", "facts", drafts = mapOf("roaster" to "Larkmoor", "country" to "")))
        before.setDraft("roaster", "Larkmoor Coffee Co")
        before.setDraft("country", "Ethiopia")

        val after = EditSessions(afterProcessDeath(handle)).forCoffee("c1")!!
        assertEquals("facts", after.section)
        assertEquals(mapOf("roaster" to "Larkmoor Coffee Co", "country" to "Ethiopia"), after.drafts)
        // What the card held when it opened comes back too, so saving still writes only the user's edits.
        assertEquals(mapOf("roaster" to "Larkmoor", "country" to ""), after.opened)
    }

    @Test fun aHalfEnteredRatingSurvivesProcessDeath() {
        val handle = SavedStateHandle()
        val before = EditSessions(handle)
        before.start(EditSession("c2", "rating", rating = RatingDraft()))
        before.setRating(RatingDraft(aroma = 4, flavor = null, acidity = 2, body = null, finish = 5, buyAgain = true, notes = "jammy, \"bright\""))

        val after = EditSessions(afterProcessDeath(handle)).forCoffee("c2")!!
        assertEquals(RatingDraft(4, null, 2, null, 5, true, "jammy, \"bright\""), after.rating)
    }

    @Test fun aClosedCardLeavesNothingBehind() {
        val handle = SavedStateHandle()
        val before = EditSessions(handle)
        before.start(EditSession("c3", "prose", drafts = mapOf("bagDescription" to "x")))
        before.clear()
        assertNull(EditSessions(afterProcessDeath(handle)).session.value)
    }

    @Test fun savedStateWithoutARatingStillRestoresTheTypedText() {
        val restored = EditSessions.decode("""{"coffeeId":"c4","section":"facts","drafts":{"roaster":"Stillwick Roasters"},"opened":{"roaster":""}}""")!!
        assertEquals(mapOf("roaster" to "Stillwick Roasters"), restored.drafts)
        assertEquals(mapOf("roaster" to ""), restored.opened)
        assertNull(restored.rating)
    }

    @Test fun aSavedRatingWithOutOfRangeOrMissingScoresKeepsOnlyTheValidOnes() {
        val restored = EditSessions.decode("""{"coffeeId":"c5","section":"rating","drafts":{},"opened":{},"rating":[9,3,null,0,5]}""")!!
        assertEquals(RatingDraft(null, 3, null, null, 5, false, ""), restored.rating)
        // A rating list of the wrong length is dropped rather than misread.
        assertNull(EditSessions.decode("""{"coffeeId":"c5","section":"rating","drafts":{},"opened":{},"rating":[1,2]}""")!!.rating)
    }

    @Test fun unreadableSavedStateIsIgnored() {
        assertNull(EditSessions.decode("{not json"))
        assertNull(EditSessions(SavedStateHandle(mapOf("edit_session" to "{\"coffeeId\":1}"))).session.value)
    }
}
