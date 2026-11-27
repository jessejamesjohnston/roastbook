package dev.roastbook.app.ui.settings

import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One block of a license file: a "==== Title ====" or "---- Title ----" heading, or a paragraph.
 * Long paragraphs are split so no single Text holds more than [MAX_BLOCK] characters.
 */
data class LicenseBlock(val text: String, val heading: Boolean)

private const val MAX_BLOCK = 4_000
private val HEADING = Regex("""^(====|----) (.+) \1$""")

/**
 * Splits a license file into [LicenseBlock]s at blank lines, in order. Line endings are normalized,
 * blank paragraphs are dropped, and a paragraph longer than [MAX_BLOCK] is cut, at a line break
 * where there is one.
 */
fun parseLicenseText(raw: String): List<LicenseBlock> {
    val out = mutableListOf<LicenseBlock>()
    raw.replace("\r\n", "\n").split(Regex("""\n[ \t]*\n""")).forEach { para ->
        val p = para.trimEnd()
        if (p.isBlank()) return@forEach
        val h = HEADING.matchEntire(p.trim())
        if (h != null) {
            out += LicenseBlock(h.groupValues[2], heading = true)
            return@forEach
        }
        var rest = p
        while (rest.length > MAX_BLOCK) {
            val cut = rest.lastIndexOf('\n', MAX_BLOCK).takeIf { it > 0 } ?: MAX_BLOCK
            out += LicenseBlock(rest.substring(0, cut), heading = false)
            rest = rest.substring(cut).trimStart('\n')
        }
        out += LicenseBlock(rest, heading = false)
    }
    return out
}

/** One row of the licenses screen. [sections] name the parts of `res/raw/licenses.txt` its page shows. */
data class LicenseComponent(val name: String, val version: String, val license: String, val sections: List<String>)

/** Reads `R.array.license_components` ("name|version|license|section;section"). */
fun parseLicenseComponents(items: Array<String>): List<LicenseComponent> = items.map { item ->
    val f = item.split('|')
    require(f.size == 4) { "license_components item needs 4 fields: $item" }
    LicenseComponent(f[0].trim(), f[1].trim(), f[2].trim(), f[3].split(';').map { it.trim() }.filter { it.isNotEmpty() })
}

/**
 * The "==== [heading] ====" sections of a license file, in the order asked, each from its heading
 * up to the next top-level heading. A heading that is not in the file is left out.
 */
fun licenseSections(raw: String, headings: List<String>): String {
    val text = raw.replace("\r\n", "\n")
    return headings.mapNotNull { h ->
        val start = text.indexOf("==== $h ====").takeIf { it >= 0 } ?: return@mapNotNull null
        val next = text.indexOf("\n==== ", start + 1).takeIf { it >= 0 } ?: text.length
        text.substring(start, next).trim()
    }.joinToString("\n\n")
}

/** The sections Roastbook's own row opens: the NOTICE file's lines on top, then its license. */
val OWN_LICENSE_SECTIONS = listOf("NOTICE", "Apache License 2.0")

/** Opens a license page: [sections] of the license texts, or the third-party notices when empty. */
typealias OpenLicense = (title: String, sections: List<String>) -> Unit

/**
 * Settings › About › Open-source notices: Roastbook's own license first, then every bundled
 * component with its version and license, then the notices the libraries ship with. Each opens
 * its text as a page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpen: OpenLicense) {
    val components = parseLicenseComponents(stringArrayResource(R.array.license_components))
    val appName = stringResource(R.string.app_name)
    val own = stringResource(R.string.licenses_own_license)
    val notices = stringResource(R.string.licenses_notices)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { BackBar(stringResource(R.string.settings_licenses), onBack) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                ReadingColumn {
                    LinkRow(appName, own) { onOpen(appName, OWN_LICENSE_SECTIONS) }
                    Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
            items(components, key = { it.name }) { c ->
                ReadingColumn {
                    val detail = if (c.version.isEmpty()) c.license else stringResource(R.string.licenses_component_detail, c.version, c.license)
                    LinkRow(c.name, detail) { onOpen(c.name, c.sections) }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item {
                ReadingColumn {
                    LinkRow(notices, stringResource(R.string.licenses_notices_hint)) { onOpen(notices, emptyList()) }
                }
            }
        }
    }
}

/**
 * A license's full text as a page (not a dialog: long texts are cramped in one on a phone and
 * awkward on a laptop). The notices file is several megabytes, so it is read off the main thread
 * and shown as a lazy list of paragraphs. Text is selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseTextScreen(title: String, sections: List<String>, onBack: () -> Unit) {
    val resources = LocalResources.current
    var blocks by remember(sections) { mutableStateOf<List<LicenseBlock>?>(null) }
    LaunchedEffect(sections) {
        blocks = withContext(Dispatchers.IO) {
            val file = if (sections.isEmpty()) R.raw.third_party_notices else R.raw.licenses
            val raw = resources.openRawResource(file).bufferedReader().use { it.readText() }
            parseLicenseText(if (sections.isEmpty()) raw else licenseSections(raw, sections))
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { BackBar(title, onBack) },
    ) { padding ->
        val list = blocks
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Text(stringResource(R.string.licenses_loading), Modifier.padding(top = 72.dp), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                    items(list) { b ->
                        ReadingColumn(Modifier.padding(horizontal = 20.dp)) {
                            if (b.heading) {
                                Text(
                                    b.text,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
                                )
                            } else {
                                Text(
                                    b.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = { IconButton(modifier = Modifier.focusRing(CircleShape), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.detail_back)) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

/** Centers content in the 720dp reading column large screens use. */
@Composable
private fun ReadingColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().then(modifier)) { content() }
}

/** A tappable row: title, then a muted subtitle; at least 56dp tall, 16dp side padding. */
@Composable
fun LinkRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRing().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
