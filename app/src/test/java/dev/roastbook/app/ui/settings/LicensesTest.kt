package dev.roastbook.app.ui.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.ui.theme.RoastbookTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The licenses screen must carry every bundled component's license text and notices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LicensesTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun raw(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    @Test fun headingsAndParagraphsAreSeparated() {
        val blocks = parseLicenseText("Intro\n\n==== Apache License 2.0 ====\n\nline one\nline two\n\n---- Abseil ----\n\nbody\n")
        assertEquals(
            listOf(
                LicenseBlock("Intro", false),
                LicenseBlock("Apache License 2.0", true),
                LicenseBlock("line one\nline two", false),
                LicenseBlock("Abseil", true),
                LicenseBlock("body", false),
            ),
            blocks,
        )
    }

    @Test fun longParagraphsAreSplitAtLineBreaks() {
        val para = (1..2_000).joinToString("\n") { "line $it" }
        val blocks = parseLicenseText(para)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.text.length <= 4_000 })
        assertEquals(para, blocks.joinToString("\n") { it.text })
    }

    @Test fun licenseTextsCoverEveryLicenseNamedInTheList() {
        val texts = raw(R.raw.licenses)
        for (h in listOf("Apache License 2.0", "BSD 3-Clause License", "MIT License", "Mozilla Public License 2.0", "CC0 1.0 Universal", "SIL Open Font License 1.1")) {
            assertTrue("missing $h", texts.contains("==== $h ===="))
        }
        assertTrue(texts.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(texts.contains("Bricolage Grotesque Project Authors"))
        assertTrue(texts.contains("Figtree Project Authors"))
    }

    @Test fun noticesIncludeTheBundledNoticeFiles() {
        val notices = raw(R.raw.third_party_notices)
        assertTrue(notices.contains("==== LiteRT-LM"))
        assertTrue(notices.contains("==== Google Play services and ML Kit: third-party components ===="))
        assertTrue(notices.contains("Eclipse Jakarta Dependency Injection"))
        assertTrue(notices.contains("Public Suffix List"))
        assertFalse(parseLicenseText(notices).isEmpty())
    }

    private val components get() = parseLicenseComponents(context.resources.getStringArray(R.array.license_components))

    @Test fun theDownloadableModelIsCredited() {
        val gemma = components.single { it.name.contains("Gemma 4 E2B") }
        assertTrue(gemma.name.contains("huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"))
        assertEquals("Apache License 2.0", gemma.license)
        assertTrue(context.getString(R.string.reader_model_credit).contains("Apache License 2.0"))
        assertTrue(components.none { it.name.contains('—') || it.license.contains('—') })
    }

    @Test fun copyrightLineNamesTheHolderAndThePublisher() {
        val line = "Copyright © 2026 Jesse Johnston. Published by Merriment Labs."
        assertEquals(line, context.getString(R.string.settings_copyright))
        // The licenses screen no longer repeats it (it lives at the foot of About); the texts still do.
        assertTrue(raw(R.raw.licenses).contains(line))
    }

    @Test fun everyComponentOpensTextThatExists() {
        val texts = raw(R.raw.licenses)
        assertTrue(components.size >= 15)
        for (c in components) {
            assertTrue("${c.name} opens nothing", c.sections.isNotEmpty())
            for (h in c.sections) assertTrue("${c.name}: no section '$h'", texts.contains("==== $h ===="))
            val page = licenseSections(texts, c.sections)
            assertTrue(page.startsWith("==== ${c.sections.first()} ===="))
        }
        // The page for OkHttp says where the MPL-licensed list's source is, at its top.
        val okhttp = licenseSections(texts, components.single { it.name == "OkHttp" }.sections)
        assertTrue(okhttp.substringBefore("==== Apache").contains("https://github.com/publicsuffix/list"))
        // One section stops at the next.
        assertFalse(licenseSections(texts, listOf("MIT License")).contains("==== Mozilla"))
    }

    /** Roastbook's own row: "Apache License 2.0", and its page carries the repo's NOTICE lines on top, verbatim. */
    @Test fun ownRowShowsTheNoticeThenTheLicense() {
        assertEquals("Apache License 2.0", context.getString(R.string.licenses_own_license))
        val texts = raw(R.raw.licenses)
        val notice = File("../NOTICE").readText().trim()
        assertEquals("Roastbook\nCopyright 2026 Jesse Johnston\nPublished by Merriment Labs (https://merrimentlabs.com)", notice)
        val page = licenseSections(texts, OWN_LICENSE_SECTIONS)
        assertTrue(page.startsWith("==== NOTICE ====\n\n$notice\n\n==== Apache License 2.0 ===="))
        assertTrue(page.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        // The page shows a NOTICE heading, the three lines as one block, then the license.
        val blocks = parseLicenseText(page)
        assertEquals(LicenseBlock("NOTICE", true), blocks[0])
        assertEquals(LicenseBlock(notice, false), blocks[1])
        assertEquals(LicenseBlock("Apache License 2.0", true), blocks[2])
    }

    /** The repo's LICENSE is the Apache License 2.0 text exactly as published, with no edits. */
    @Test fun licenseFileIsTheVerbatimApacheText() {
        val bytes = File("../LICENSE").readBytes()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", sha)
    }

    /** A version shown on the screen is one the build actually uses, so the two can't drift apart. */
    @Test fun shownVersionsMatchTheVersionCatalog() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        val versions = Regex("""^\s*[A-Za-z0-9_-]+\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).findAll(catalog.substringAfter("[versions]").substringBefore("[libraries]"))
            .map { it.groupValues[1] }.toSet()
        val expected = mapOf(
            "kotlinx.coroutines" to "kotlinxCoroutines", "kotlinx.serialization" to "kotlinxSerialization",
            "Jetpack Compose (bill of materials)" to "composeBom", "Dagger and Hilt" to "hilt", "Coil" to "coil", "OkHttp" to "okhttp",
            "ML Kit Text Recognition" to "mlkitTextRecognition", "ML Kit Subject Segmentation" to "mlkitSubjectSegmentation",
            "ML Kit GenAI Prompt API" to "genaiPrompt",
        )
        for ((name, key) in expected) {
            val want = Regex("""^\s*$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(catalog)?.groupValues?.get(1)
            assertEquals(name, want, components.single { it.name == name }.version)
        }
        for (c in components) if (c.version.isNotEmpty()) assertTrue("${c.name} ${c.version} is not in the catalog", c.version in versions)
    }

    /**
     * The catalog's `kotlin` version is the compiler's. The standard library the app runs with is
     * the newer one its dependencies resolve to, so its row shows no version rather than the
     * compiler's.
     */
    @Test fun theStandardLibraryRowShowsNoVersion() {
        assertEquals("", components.single { it.name == "Kotlin standard library" }.version)
    }

    private val opened = mutableListOf<Pair<String, List<String>>>()
    private var backs = 0
    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showList() = compose.setContent {
        RoastbookTheme { LicensesScreen(onBack = { backs++ }, onOpen = { title, sections -> opened += title to sections }) }
    }

    @Test fun theOwnRowOpensTheNoticeAndLicense() {
        showList()
        compose.onNodeWithText(context.getString(R.string.settings_licenses)).assertExists()
        compose.onNode(hasText(context.getString(R.string.app_name)) and hasText(context.getString(R.string.licenses_own_license))).performClick()
        assertEquals(listOf(context.getString(R.string.app_name) to OWN_LICENSE_SECTIONS), opened)
        assertTrue(shown(context.getString(R.string.licenses_intro)))
    }

    @Test fun aComponentRowShowsItsVersionAndOpensItsSections() {
        showList()
        val versioned = components.first { it.version.isNotEmpty() }
        val detail = context.getString(R.string.licenses_component_detail, versioned.version, versioned.license)
        compose.onNode(hasText(versioned.name) and hasText(detail)).performClick()
        // A component without a version shows only its license.
        val bare = components.first { it.version.isEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(bare.name))
        compose.onNode(hasText(bare.name) and hasText(bare.license)).assertExists()
        compose.onNode(hasText(bare.name) and hasText(" · ", substring = true)).assertDoesNotExist()
        compose.onNode(hasText(bare.name)).performClick()
        assertEquals(listOf(versioned.name to versioned.sections, bare.name to bare.sections), opened)
    }

    @Test fun theNoticesRowIsLastAndOpensTheNoticesFile() {
        showList()
        val notices = context.getString(R.string.licenses_notices)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(notices))
        compose.onNode(hasText(notices) and hasText(context.getString(R.string.licenses_notices_hint))).performClick()
        assertEquals(listOf(notices to emptyList<String>()), opened)
        compose.onNodeWithContentDescription(context.getString(R.string.detail_back)).performClick()
        assertEquals(1, backs)
    }

    @Test fun aLicensePageShowsItsSectionsWithHeadingsOnceLoaded() {
        compose.setContent { RoastbookTheme { LicenseTextScreen("Stillwick Roasters", OWN_LICENSE_SECTIONS, onBack = { backs++ }) } }
        compose.waitUntil(10_000) { shown(OWN_LICENSE_SECTIONS[0]) }
        assertFalse(shown(context.getString(R.string.licenses_loading)))
        compose.onNode(hasText("Stillwick Roasters") and isHeading).assertExists()
        compose.onNode(hasText(OWN_LICENSE_SECTIONS[0]) and isHeading).assertExists()
        // The NOTICE lines come right after their heading, as one plain block.
        val blocks = parseLicenseText(licenseSections(raw(R.raw.licenses), OWN_LICENSE_SECTIONS))
        compose.onNodeWithText(blocks[1].text).assertExists()
        compose.onNode(hasText(blocks[1].text) and isHeading).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.detail_back)).performClick()
        assertEquals(1, backs)
    }

    @Test fun thePageWithNoSectionsShowsTheThirdPartyNotices() {
        compose.setContent { RoastbookTheme { LicenseTextScreen("Notices", emptyList(), onBack = {}) } }
        val first = parseLicenseText(raw(R.raw.third_party_notices)).first()
        compose.waitUntil(10_000) { shown(first.text) }
        // Not the license texts: their NOTICE section is not on this page.
        assertFalse(shown(OWN_LICENSE_SECTIONS[0]))
    }
}
