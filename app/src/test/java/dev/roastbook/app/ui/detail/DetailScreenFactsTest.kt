package dev.roastbook.app.ui.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.SourceEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The coffee page on a phone: what the facts and descriptions show, and what an inline edit writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h2600dp")
class DetailScreenFactsTest : DetailScreenTestBase() {
    private val facts get() = str(R.string.detail_facts)
    private val prose get() = str(R.string.detail_descriptions)

    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(str(label)))
    private fun edit(section: String) = compose.onNodeWithContentDescription(str(R.string.detail_edit_section, section)).performClick()
    private fun done(section: String) = compose.onNodeWithContentDescription(str(R.string.detail_done_section, section)).performClick()
    private fun editing(section: String) = compose.onNodeWithContentDescription(str(R.string.detail_done_section, section)).exists()

    private fun northwind() = insert { copy(roaster = "Northwind", coffeeName = "Cloud Terrace", originCountry = "Kenya", process = "Washed", tastingNotes = listOf("plum", "cocoa")) }

    @Test fun aNamedCoffeeShowsItsNameTastingNotesAndFacts() {
        northwind()
        show()
        compose.onNodeWithText("Cloud Terrace").assertIsDisplayed()
        compose.onNodeWithText("plum · cocoa").assertIsDisplayed()
        compose.onNodeWithText("Kenya").assertIsDisplayed()
        compose.onNodeWithText("Washed").assertIsDisplayed()
        // The name is the title, so the facts card does not repeat it.
        assertEquals(1, compose.onAllNodesWithText("Cloud Terrace").fetchSemanticsNodes().size)
    }

    @Test fun aCoffeeWithOnlyARoasterIsTitledByTheRoaster() {
        insert { copy(roaster = "Larkmoor") }
        show()
        assertEquals(2, compose.onAllNodesWithText("Larkmoor").fetchSemanticsNodes().size) // title and the roaster fact
        compose.onAllNodesWithText("Larkmoor")[0].assertIsDisplayed()
    }

    @Test fun aCoffeeWithNothingReadIsUntitledAndItsCardsSaySo() {
        insert()
        show()
        compose.onNodeWithText(str(R.string.detail_untitled)).assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText(str(R.string.detail_section_empty)).fetchSemanticsNodes().size)
        // Nothing was read, so nothing is attributed to a reader.
        compose.onNodeWithText(str(R.string.source_line_ocr)).assertDoesNotExist()
    }

    @Test fun aLabelTextReadShowsItsSourceLineAndTheTextItself() {
        insert { copy(roaster = "Stillwick Roasters", extractionSource = ExtractionSource.OCR, ocrText = "STILLWICK ROASTERS WASHED 250G", extractionTrail = "nano=unsupported;guess=0") }
        show()
        compose.onNodeWithText(str(R.string.source_line_ocr)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_ocr_text)).assertIsDisplayed()
        compose.onNodeWithText("STILLWICK ROASTERS WASHED 250G").assertIsDisplayed()
    }

    @Test fun editedFactsAreWrittenInTheirOwnShapes() {
        insert { copy(roaster = "Northwind", region = "Nyeri") }
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("  Larkmoor  ")
        field(R.string.field_region).performTextReplacement("")
        field(R.string.field_elevation).performTextReplacement("1,850 m")
        field(R.string.field_varietal).performTextReplacement("SL28 · Batian, Ruiru 11")
        field(R.string.field_bag_size).performTextReplacement("250 g")
        field(R.string.field_tasting_notes).performTextReplacement("plum | cocoa")
        field(R.string.field_price).performTextReplacement("18.50")
        done(facts)
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        val r = row()!!
        assertNull(r.region)
        assertEquals(1850, r.elevationM)
        assertEquals(listOf("SL28", "Batian", "Ruiru 11"), r.varietal)
        assertEquals(250, r.bagSizeG)
        assertEquals(listOf("plum", "cocoa"), r.tastingNotes)
        assertEquals(18.5f, r.priceUsd!!, 0.001f)
        assertTrue(r.userEdited.containsAll(listOf("roaster", "region")))
        assertFalse(editing(facts))
    }

    @Test fun aFieldFilledInWhileTheCardWasOpenKeepsWhatTheReadFound() {
        insert { copy(roaster = "Northwind") }
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        // A read lands the origin while the card is open; the user never touched that field.
        runBlocking { f.graph.dao.mutate("c1") { it.copy(originCountry = "Rwanda") } }
        compose.awaitUntil { vm.coffee.value?.originCountry == "Rwanda" }
        done(facts)
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        assertEquals("Rwanda", row()!!.originCountry)
        assertFalse("originCountry" in row()!!.userEdited)
    }

    @Test fun tappingAKnownSourceFillsItIn() {
        runBlocking { f.graph.dao.insertSources(listOf(SourceEntity("s1", "Sable & Rye", isSeed = false, createdAt = 1))) }
        insert { copy(roaster = "Northwind") }
        show()
        edit(facts)
        compose.onNodeWithText("Sable & Rye").performClick()
        done(facts)
        compose.awaitUntil { row()?.sourceId == "s1" }
        compose.awaitText("Sable & Rye")
        compose.onNodeWithText("Sable & Rye").assertIsDisplayed()
    }

    @Test fun clearingTheSourceRemovesIt() {
        runBlocking { f.graph.dao.insertSources(listOf(SourceEntity("s1", "Sable & Rye", isSeed = false, createdAt = 1))) }
        insert { copy(roaster = "Northwind", sourceId = "s1") }
        show()
        edit(facts)
        field(R.string.field_source).performTextReplacement("")
        done(facts)
        compose.awaitUntil { row()?.sourceId == null }
    }

    @Test fun descriptionsAreEditedAndShownUnderTheirLabels() {
        insert { copy(roaster = "Northwind", bagDescription = "Grown on volcanic soil.") }
        show()
        compose.onNodeWithText("Grown on volcanic soil.").assertIsDisplayed()
        edit(prose)
        field(R.string.detail_seller_description).performTextReplacement("A sweet, juicy cup.")
        done(prose)
        compose.awaitUntil { row()?.sellerDescription == "A sweet, juicy cup." }
        compose.awaitText(str(R.string.detail_seller_description))
        compose.awaitText("A sweet, juicy cup.")
        compose.onNodeWithText(str(R.string.detail_seller_description)).assertIsDisplayed()
        compose.onNodeWithText("A sweet, juicy cup.").assertIsDisplayed()
        assertEquals("Grown on volcanic soil.", row()!!.bagDescription)
    }

    @Test fun openingAnotherCardSavesTheFirst() {
        insert { copy(roaster = "Northwind") }
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        edit(prose)
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        assertFalse(editing(facts))
        assertTrue(editing(prose))
    }

    // The class runs in a very tall window so most tests can reach every card without scrolling;
    // this one runs at phone height, where the descriptions card starts below the fold.
    @Config(qualifiers = "w400dp-h800dp")
    @Test fun atPhoneHeightTheLowerCardsScrollIntoReachAndSave() {
        insert { copy(roaster = "Northwind", bagDescription = "Bright and sweet.") }
        show()
        val pencil = compose.onNodeWithContentDescription(str(R.string.detail_edit_section, prose))
        pencil.assertIsNotDisplayed()
        pencil.performScrollTo().assertIsDisplayed().performClick()
        field(R.string.detail_bag_description).performScrollTo().assertIsDisplayed().performTextReplacement("Stone fruit.")
        compose.onNodeWithContentDescription(str(R.string.detail_done_section, prose)).performScrollTo().performClick()
        compose.awaitUntil { row()?.bagDescription == "Stone fruit." }
        assertFalse(editing(prose))
    }

    @Test fun closingACardWithNothingChangedWritesNothing() {
        insert { copy(roaster = "Northwind", updatedAt = 1_000) }
        show()
        edit(facts)
        done(facts)
        awaitWritesDone()
        assertEquals(1_000L, row()!!.updatedAt)
        assertTrue(row()!!.userEdited.isEmpty())
    }

    @Test fun tappingElsewhereOnThePageSavesTheCard() {
        northwind()
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        compose.onNode(hasText("Cloud Terrace") and isHeading()).performTouchInput { click() }
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        assertFalse(editing(facts))
    }

    @Test fun systemBackWhileEditingSavesTheCardAndStaysOnThePage() {
        insert { copy(roaster = "Northwind") }
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        assertFalse(editing(facts))
        assertEquals(0, backs)
    }

    @Test fun theBackArrowSavesTheCardAndLeaves() {
        insert { copy(roaster = "Northwind") }
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        assertEquals(1, backs)
    }

    @Test fun leavingThePageSavesAnOpenCard() {
        insert { copy(roaster = "Northwind") }
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        shown = false
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        assertNull(sessions.session.value)
    }

    /**
     * The page's edit logic outlives the composition it was first set up in, which ran before the
     * row existed. Every save must build its field list from the row at the moment it saves: one
     * built from that first composition is empty, and turns every edit into a no-op.
     */
    @Test fun aCoffeeThatLoadsAfterThePageOpensStillSavesItsEdits() {
        show(awaitRow = false)
        assertNull(vm.coffee.value)
        northwind()
        compose.awaitUntil { vm.coffee.value != null }
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        compose.onNode(hasText("Cloud Terrace") and isHeading()).performTouchInput { click() }
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        edit(facts)
        field(R.string.field_origin_country).performTextReplacement("Rwanda")
        shown = false
        compose.awaitUntil { row()?.originCountry == "Rwanda" }
        assertTrue(row()!!.userEdited.containsAll(listOf("roaster", "originCountry")))
    }

    @Test fun anEditHandedToTheOtherLayoutIsNotSavedOnTheWayOut() {
        insert { copy(roaster = "Northwind") }
        show()
        edit(facts)
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        sessions.handingOver = true
        shown = false
        awaitWritesDone()
        assertEquals("Northwind", row()!!.roaster)
        assertEquals("Larkmoor", sessions.forCoffee("c1")!!.drafts["roaster"])
    }

    @Test fun anEditOpenFromTheOtherLayoutIsPickedUpAndSavedHere() {
        insert { copy(roaster = "Northwind") }
        show(before = {
            it.start(EditSession("c1", "facts", drafts = mapOf("roaster" to "Larkmoor"), opened = mapOf("roaster" to "Northwind")))
            it.owner = Any()
            it.handingOver = true
        })
        field(R.string.field_roaster).assertExists()
        compose.onNodeWithText("Larkmoor").assertIsDisplayed()
        assertFalse(sessions.handingOver)
        shown = false
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
    }

    @Test fun photosAreListedAndAThumbnailOnlyRestoreSaysSo() {
        insert { copy(roaster = "Northwind", coffeeName = "Cloud Terrace", heroPhotoId = "p1") }
        File(f.context.filesDir, "photos/c1").mkdirs()
        File(f.context.filesDir, "photos/c1/p1-thumb.jpg").writeBytes(f.graph.stagedJpeg().readBytes())
        runBlocking {
            f.graph.dao.insertPhotos(listOf(
                CoffeePhotoEntity("p1", "c1", PhotoRole.FRONT, 0, original = "photos/c1/p1.jpg", thumb = "photos/c1/p1-thumb.jpg", takenAt = 1, addedAt = 1),
                CoffeePhotoEntity("p2", "c1", PhotoRole.BACK, 1, original = "photos/c1/p2.jpg", takenAt = 1, addedAt = 1),
            ))
        }
        show()
        compose.awaitUntil { vm.photos.value.size == 2 }
        compose.onNodeWithText(str(R.string.detail_thumbnail_only)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.detail_hero_description, "Cloud Terrace")).assertExists()
        compose.onNodeWithText(str(R.string.detail_photos)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.detail_photo_position, 1, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.detail_photo_position, 2, 2)).assertIsDisplayed()
    }

    @Test fun atLargeFontSizesFactLabelsSitAboveTheirValues() {
        insert { copy(roaster = "Northwind", process = "Washed") }
        show(wrap = { content ->
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) { content() }
        })
        val label = compose.onNodeWithText(str(R.string.field_process), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("Washed", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("value below its label", value.top >= label.bottom)
    }

    @Test fun atNormalFontSizesFactLabelsSitBesideTheirValues() {
        insert { copy(roaster = "Northwind", process = "Washed") }
        show()
        val label = compose.onNodeWithText(str(R.string.field_process), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("Washed", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("value beside its label", value.left >= label.right && value.top < label.bottom)
    }
}
