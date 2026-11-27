package dev.roastbook.app.ui.settings

import dev.roastbook.app.ui.components.focusRing
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.roastbook.app.R
import dev.roastbook.app.util.ExternalLinks
import dev.roastbook.app.ui.components.NOTIFICATION_PERMISSION
import dev.roastbook.app.ui.components.NotificationReason
import dev.roastbook.app.ui.components.PermissionAsk
import dev.roastbook.app.ui.components.PermissionAskState
import dev.roastbook.app.ui.components.PermissionReasonDialog
import dev.roastbook.app.ui.components.rememberPermissionAsk
import dev.roastbook.app.ui.theme.RowTitleStyle
import dev.roastbook.app.update.UpdateCheckResult
import dev.roastbook.app.update.UpdateChecker

/**
 * The About card and the copyright line under it. The first block is shared by every Merriment Labs
 * app, in this order: the app and its version, the maker, source code, privacy, open-source notices,
 * the license, and the update check. Rows only this app needs (Help, Contact) come after it. Pages
 * open in a Custom Tab; when nothing can open one, an inline line says so.
 */
@Composable
internal fun AboutCard(
    version: String,
    onLicenses: () -> Unit,
    update: UpdateUi = UpdateUi(),
    weeklyCheck: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    onUpdatePageOpened: () -> Unit = {},
    onWeeklyCheck: (Boolean) -> Unit = {},
    // Shared with Settings' notifications row, so a Not now here shows there at once.
    notifAsk: PermissionAskState = rememberPermissionAsk(NOTIFICATION_PERMISSION),
) {
    val context = LocalContext.current
    // An inline line under a row when nothing on the device can open its page or email.
    // (string, its argument): kept as ids so the line follows a language or configuration change.
    var aboutMessage by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun openPage(url: String) {
        aboutMessage = null
        val opened = ExternalLinks.open(context, url.toUri())
        if (!opened) aboutMessage = R.string.about_no_browser to url
    }
    // Turning on the weekly check asks for the notification permission it needs to tell anyone
    // anything (Android 13+), unless Android would no longer show the dialog. The switch turns on
    // whatever the answer.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifAsk.answered(granted) }
    // The reason shows first, in the same dialog as every other ask; Android's question follows Continue.
    var explainWeekly by rememberSaveable { mutableStateOf(false) }
    fun setWeekly(on: Boolean) {
        onWeeklyCheck(on)
        if (!on || Build.VERSION.SDK_INT < 33) return
        notifAsk.refresh()
        val askable = notifAsk.value == PermissionAsk.FIRST_ASK || notifAsk.value == PermissionAsk.ASK_AGAIN
        // After Not now the reason doesn't come back; Settings' notifications row says what can't show.
        if (askable && !notifAsk.declined(NotificationReason.UPDATE)) explainWeekly = true
    }
    if (explainWeekly) {
        PermissionReasonDialog(
            title = stringResource(R.string.notif_reason_update_title), reason = stringResource(R.string.notif_why_update),
            onContinue = {
                explainWeekly = false
                notifAsk.markAsked()
                notifPermission.launch(NOTIFICATION_PERMISSION)
            },
            onNotNow = { explainWeekly = false; notifAsk.decline(NotificationReason.UPDATE) },
        )
    }
    // A check that found a newer version opens the release page once.
    LaunchedEffect(update.openPage) {
        if (update.openPage) {
            onUpdatePageOpened()
            openPage(UpdateChecker.RELEASES_PAGE)
        }
    }

    RowsCard(title = stringResource(R.string.settings_about)) {
        AboutHeader(stringResource(R.string.settings_version, version), stringResource(R.string.app_subtitle))
        RowDivider()
        val makerUrl = stringResource(R.string.about_maker_url)
        AboutRow(Icons.Outlined.Person, stringResource(R.string.about_maker), stringResource(R.string.about_maker_hint),
            AboutRowKind.BROWSER) { openPage(makerUrl) }
        RowDivider()
        val sourceUrl = stringResource(R.string.about_source_url)
        AboutRow(Icons.Outlined.Code, stringResource(R.string.about_source), stringResource(R.string.about_source_hint),
            AboutRowKind.BROWSER) { openPage(sourceUrl) }
        RowDivider()
        // The policy is one tap away inside the app, not only on the website.
        val privacyUrl = stringResource(R.string.privacy_policy_url)
        AboutRow(Icons.Outlined.Shield, stringResource(R.string.settings_privacy_policy), stringResource(R.string.about_privacy_hint),
            AboutRowKind.BROWSER) { openPage(privacyUrl) }
        RowDivider()
        AboutRow(Icons.Outlined.Description, stringResource(R.string.settings_licenses), stringResource(R.string.about_licenses_hint),
            AboutRowKind.IN_APP, onLicenses)
        RowDivider()
        val licenseUrl = stringResource(R.string.about_license_url)
        AboutRow(Icons.Outlined.Gavel, stringResource(R.string.about_license), stringResource(R.string.about_license_hint),
            AboutRowKind.BROWSER) { openPage(licenseUrl) }
        RowDivider()
        AboutRow(Icons.Outlined.SystemUpdate, stringResource(R.string.update_check), updateSubtitle(update),
            AboutRowKind.ACTION, onClick = { if (!update.checking) onCheckForUpdates() })
        AboutSwitchRow(Icons.Outlined.Schedule, stringResource(R.string.update_weekly), stringResource(R.string.update_weekly_hint),
            weeklyCheck, ::setWeekly)
        RowDivider()
        val supportUrl = stringResource(R.string.support_url)
        AboutRow(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.about_help), stringResource(R.string.about_help_hint),
            AboutRowKind.BROWSER) { openPage(supportUrl) }
        RowDivider()
        val address = stringResource(R.string.about_contact_address)
        val subject = stringResource(R.string.settings_version, version)
        AboutRow(Icons.Outlined.Email, stringResource(R.string.about_contact), address, AboutRowKind.EMAIL) {
            aboutMessage = null
            if (!ExternalLinks.email(context, address, subject)) aboutMessage = R.string.about_contact_no_app to ""
        }
        aboutMessage?.let { (res, arg) ->
            Text(
                stringResource(res, arg), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    // Below the card, outside it, like every Merriment Labs app; TalkBack reads it after the last row.
    Text(
        stringResource(R.string.settings_copyright), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
    )
}

/**
 * Getting started: the intro again. Its own section above About, so the About block keeps the
 * family's fixed rows and order.
 */
@Composable
internal fun ReplayIntroCard(onReplayIntro: () -> Unit) {
    RowsCard(title = stringResource(R.string.settings_getting_started)) {
        AboutRow(Icons.Outlined.School, stringResource(R.string.settings_replay_intro), stringResource(R.string.settings_replay_intro_hint),
            AboutRowKind.IN_APP, onReplayIntro)
    }
}

/** The update row's second line: what it does, that it is checking, or what the last check found. */
@Composable
private fun updateSubtitle(update: UpdateUi): String = when {
    update.checking -> stringResource(R.string.update_checking)
    else -> when (val r = update.result) {
        null -> stringResource(R.string.update_check_hint)
        is UpdateCheckResult.Available -> stringResource(R.string.update_available_hint, r.version)
        UpdateCheckResult.UpToDate -> stringResource(R.string.update_latest)
        UpdateCheckResult.NoRelease -> stringResource(R.string.update_no_release)
        UpdateCheckResult.Offline -> stringResource(R.string.update_offline)
        UpdateCheckResult.Failed -> stringResource(R.string.update_failed)
    }
}

/** An About row with a switch: the whole row toggles it, and TalkBack reads it as one switch. */
@Composable
private fun AboutSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .focusRing().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitleStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        Spacer(Modifier.width(8.dp))
        // The row carries the toggle; the switch only shows its state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A section whose card is made of tappable rows that run edge to edge (the ripple fills the row). */
@Composable
private fun RowsCard(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        content()
    }
}

private enum class AboutRowKind { BROWSER, EMAIL, IN_APP, ACTION }

/**
 * An About row: a leading outline icon, title and subtitle, and a trailing sign of where it goes
 * (an arrow out of the app for a web page or an email, a chevron for a screen inside the app).
 * TalkBack reads "title, subtitle, opens in browser" (or "opens email"), or "title, subtitle,
 * button" for a screen inside the app or an action in place (the update check).
 */
@Composable
private fun AboutRow(icon: ImageVector, title: String, subtitle: String, kind: AboutRowKind, onClick: () -> Unit) {
    // A subtitle that is a sentence ends in a period; drop it so the spoken list doesn't read ".,".
    val spoken = subtitle.removeSuffix(".")
    val description = when (kind) {
        AboutRowKind.BROWSER -> stringResource(R.string.about_opens_browser, title, spoken)
        AboutRowKind.EMAIL -> stringResource(R.string.about_opens_email, title, spoken)
        AboutRowKind.IN_APP, AboutRowKind.ACTION -> stringResource(R.string.about_row, title, spoken)
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .focusRing().clickable(role = if (kind == AboutRowKind.IN_APP || kind == AboutRowKind.ACTION) Role.Button else null, onClick = onClick)
            // The children's own text is replaced by one description; click and role stay on the row.
            .clearAndSetSemantics { contentDescription = description }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitleStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        Spacer(Modifier.width(8.dp))
        when (kind) {
            AboutRowKind.ACTION -> Unit // an action in place: no sign of going anywhere
            AboutRowKind.IN_APP ->
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = muted, modifier = Modifier.size(24.dp))
            AboutRowKind.BROWSER, AboutRowKind.EMAIL -> {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** The app icon, then "Roastbook <version>" and the one-line description. */
@Composable
private fun AboutHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(colorResource(R.color.ic_launcher_background))) {
            // The launcher shows the middle 72 of the foreground's 108 units; scaling by 1.5 shows the same.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(title, style = RowTitleStyle)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
