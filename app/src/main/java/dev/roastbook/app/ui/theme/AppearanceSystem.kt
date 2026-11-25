package dev.roastbook.app.ui.theme

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import dev.roastbook.app.data.prefs.ThemeMode
import java.util.Locale

/**
 * The app's own night mode. UiModeManager keeps it per app across restarts and applies it to the
 * configuration, so the window background, status bar icons and every `-night` resource follow the
 * choice too, not only Compose. SYSTEM hands control back to the device setting.
 */
object NightMode {
    fun apply(context: Context, mode: ThemeMode) {
        val ui = context.getSystemService(UiModeManager::class.java) ?: return
        val value = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        runCatching { ui.setApplicationNightMode(value) }
    }
}

/**
 * Per-app language (Android 13+). The list must match `res/xml/locales_config.xml` and the
 * translated `values-*` folders; LanguagesTest holds the three together. Adding a language is:
 * translate strings.xml into `values-<tag>`, add the tag here and to locales_config.xml.
 */
object AppLanguage {
    val supported: List<String> = listOf("en")

    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The chosen language tag, or null when the app follows the device language. */
    fun current(context: Context): String? {
        if (!available) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (locales.isEmpty) null else locales[0].toLanguageTag()
    }

    /** [tag] null = follow the device. The system restarts the activity with the new locale. */
    fun set(context: Context, tag: String?) {
        if (!available) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        lm.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /** A language's own name for itself, capitalized for a list ("English", "Español"). */
    fun displayName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
    }
}
