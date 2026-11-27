package dev.roastbook.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Opens every link that leaves the app, the same way everywhere. On a laptop-class device (one that
 * reports [PackageManager.FEATURE_PC], as Chromebooks and Googlebooks do) the app is one window
 * among several, so a link opens in the default browser as a normal tab. On phones, foldables and
 * tablets it opens in an in-app browser tab (a Custom Tab), or in the browser when no installed
 * browser offers those tabs. Email leaves through [email], and a notification's page through [browserIntent].
 */
object ExternalLinks {
    /** How a link opens. */
    enum class Way { IN_APP_TAB, BROWSER }

    /** The rule, on its own: an in-app tab only off a laptop, and only where a browser offers one. */
    fun way(laptop: Boolean, inAppTabs: Boolean): Way = if (!laptop && inAppTabs) Way.IN_APP_TAB else Way.BROWSER

    /** Opens [uri]; false when nothing on the device could open it, so the caller can say so. */
    fun open(context: Context, uri: Uri): Boolean = runCatching {
        val laptop = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PC)
        // Null when no installed browser offers in-app tabs (the manifest's <queries> makes them visible).
        val inAppTabs = CustomTabsClient.getPackageName(context, null) != null
        // Started from outside an activity (a service or the application), the page needs its own task.
        val newTask = if (context.activity() == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
        when (way(laptop, inAppTabs)) {
            Way.IN_APP_TAB -> CustomTabsIntent.Builder().build().apply { intent.addFlags(newTask) }.launchUrl(context, uri)
            Way.BROWSER -> context.startActivity(browserIntent(uri).addFlags(newTask))
        }
    }.isSuccess

    /** [uri] in the default browser: the intent behind the browser case, and a notification's tap. */
    fun browserIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    /** Starts an email to [address] with [subject]; false when no mail app can take it, so the caller can say so. */
    fun email(context: Context, address: String, subject: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).putExtra(Intent.EXTRA_EMAIL, arrayOf(address)).putExtra(Intent.EXTRA_SUBJECT, subject)
                .addFlags(if (context.activity() == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0),
        )
    }.isSuccess

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
