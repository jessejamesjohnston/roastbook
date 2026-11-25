package dev.roastbook.app.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The notification permission, for the screens that ask for it. Android 13 added it; Android 12 has
 * no such permission and lets apps post notifications without one. The name is a compile-time
 * constant, so passing it on Android 12 cannot crash, and callers need no version check of their own.
 */
@SuppressLint("InlinedApi")
const val NOTIFICATION_PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

/**
 * Where a runtime permission stands, from the person's side: already allowed, Android will show its
 * dialog if asked, or refused for good (only Android's Settings can change it now).
 */
enum class PermissionAsk { GRANTED, FIRST_ASK, ASK_AGAIN, BLOCKED }

/**
 * What this install knows about its permission asks. Kept in `shared_prefs/`, which is outside the
 * backup include list, so a restored or moved install starts with no record and explains again
 * rather than guessing that the person refused.
 *
 * "Refused for good" is recorded only from a dialog's answer, never guessed from Android's rationale
 * flag on its own (that flag is also false before the first ask and after a one-time grant expires):
 * a refusal with no rationale, after an earlier refusal, is Android's "don't ask again" (Android 11+
 * stops showing the dialog after the second refusal). Dismissing the dialog without an answer is not
 * a refusal for good.
 */
object PermissionAsks {
    private fun prefs(c: Context) = c.getSharedPreferences("permission_asks", Context.MODE_PRIVATE)
    fun asked(c: Context, permission: String): Boolean = prefs(c).getBoolean(permission, false)
    fun markAsked(c: Context, permission: String) = prefs(c).edit { putBoolean(permission, true) }

    /** Where things stand, from what is stored: granted wins, then a recorded refusal for good. */
    fun decide(granted: Boolean, asked: Boolean, blocked: Boolean): PermissionAsk = when {
        granted -> PermissionAsk.GRANTED
        blocked -> PermissionAsk.BLOCKED
        asked -> PermissionAsk.ASK_AGAIN
        else -> PermissionAsk.FIRST_ASK
    }

    /** After a dialog's answer: is it now refused for good, and has it been refused before? */
    fun afterAnswer(granted: Boolean, rationale: Boolean, refusedBefore: Boolean): Pair<Boolean, Boolean> = when {
        granted -> false to false
        rationale -> false to true // refused once; Android will ask once more
        else -> refusedBefore to refusedBefore // no rationale: "don't ask again" only after an earlier refusal
    }

    fun state(c: Context, permission: String): PermissionAsk = decide(
        granted = ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED,
        asked = asked(c, permission),
        blocked = prefs(c).getBoolean("$permission.blocked", false),
    )

    fun recordAnswer(c: Context, activity: Activity?, permission: String, granted: Boolean) {
        val (blocked, refused) = afterAnswer(
            granted, activity?.shouldShowRequestPermissionRationale(permission) == true, prefs(c).getBoolean("$permission.refused", false))
        prefs(c).edit { putBoolean("$permission.blocked", blocked).putBoolean("$permission.refused", refused) }
    }

    /**
     * Seen allowed (in Android's Settings, say) after a refusal for good: that refusal no longer
     * holds. The earlier-refusal note stays, so if Android still won't show its dialog after a later
     * revoke, the next answer records the refusal for good again.
     */
    fun clearBlocked(c: Context, permission: String) {
        if (prefs(c).getBoolean("$permission.blocked", false)) prefs(c).edit { putBoolean("$permission.blocked", false) }
    }

    /** Android's settings page for this app, where a refused permission can be turned on. */
    fun openAppSettings(c: Context) {
        runCatching {
            c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null)))
        }
    }
}

/** A permission's [PermissionAsk], refreshed whenever the screen comes back (from Settings, say). */
class PermissionAskState(private val context: Context, private val activity: Activity?, val permission: String) {
    var value by mutableStateOf(PermissionAsks.state(context, permission))
        private set
    fun refresh() {
        value = PermissionAsks.state(context, permission)
        if (value == PermissionAsk.GRANTED) PermissionAsks.clearBlocked(context, permission)
    }
    fun markAsked() { PermissionAsks.markAsked(context, permission) }
    /** Call from the dialog's result callback, before [refresh]. */
    fun answered(granted: Boolean) { PermissionAsks.recordAnswer(context, activity, permission, granted); refresh() }
}

/** Remembers a [PermissionAskState] for [permission], refreshed each time the screen resumes. */
@Composable
fun rememberPermissionAsk(permission: String): PermissionAskState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember(permission) { PermissionAskState(context, activity, permission) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) state.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}
