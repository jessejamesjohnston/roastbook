package dev.roastbook.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Settings > Appearance > Theme. SYSTEM follows the device's light or dark setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The user's choices outside backup, with the product defaults. */
data class UserSettings(
    val secondLookEnabled: Boolean = true,
    val secondLookDays: Int = DEFAULT_SECOND_LOOK_DAYS,
    val saveSquaresToPhotos: Boolean = false,
    val onboardingDone: Boolean = false,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val highContrast: Boolean = false,
    /** Settings > About > Check weekly. Off until the person turns it on. */
    val weeklyUpdateCheck: Boolean = false,
    /** The newest version the weekly check has already announced, so each is announced once. */
    val lastAnnouncedVersion: String? = null,
) {
    companion object {
        const val DEFAULT_SECOND_LOOK_DAYS = 5
        /** The cloud tier's model id; editable in Settings because Google retires these names. */
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        val SECOND_LOOK_CHOICES = listOf(3, 5, 7, 10)
    }
}

/**
 * The backed-up settings store (DataStore, under files/datastore/). [settings] always emits a full
 * [UserSettings], with product defaults for anything never set, and re-emits on every change; each
 * setter is one atomic DataStore edit. Device-scoped reader state lives in ReaderPrefs instead.
 */
@Singleton
class UserPrefs @Inject constructor(private val store: DataStore<Preferences>) {
    private object K {
        val secondLookEnabled = booleanPreferencesKey("second_look_enabled")
        val secondLookDays = intPreferencesKey("second_look_days")
        val saveSquares = booleanPreferencesKey("save_squares_to_photos")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val geminiModel = androidx.datastore.preferences.core.stringPreferencesKey("gemini_model")
        val themeMode = androidx.datastore.preferences.core.stringPreferencesKey("theme_mode")
        val highContrast = booleanPreferencesKey("high_contrast")
        val weeklyUpdateCheck = booleanPreferencesKey("weekly_update_check")
        val lastAnnouncedVersion = androidx.datastore.preferences.core.stringPreferencesKey("last_announced_version")
    }

    val settings: Flow<UserSettings> = store.data.map { p ->
        UserSettings(
            secondLookEnabled = p[K.secondLookEnabled] ?: true,
            secondLookDays = p[K.secondLookDays] ?: UserSettings.DEFAULT_SECOND_LOOK_DAYS,
            saveSquaresToPhotos = p[K.saveSquares] ?: false,
            onboardingDone = p[K.onboardingDone] ?: false,
            geminiModel = p[K.geminiModel]?.takeIf { it.isNotBlank() } ?: UserSettings.DEFAULT_GEMINI_MODEL,
            themeMode = p[K.themeMode]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM,
            highContrast = p[K.highContrast] ?: false,
            weeklyUpdateCheck = p[K.weeklyUpdateCheck] ?: false,
            lastAnnouncedVersion = p[K.lastAnnouncedVersion],
        )
    }

    suspend fun current(): UserSettings = settings.first()
    suspend fun setSecondLookEnabled(on: Boolean) = store.edit { it[K.secondLookEnabled] = on }
    suspend fun setSecondLookDays(days: Int) = store.edit { it[K.secondLookDays] = days }
    suspend fun setSaveSquares(on: Boolean) = store.edit { it[K.saveSquares] = on }
    suspend fun setOnboardingDone(done: Boolean) = store.edit { it[K.onboardingDone] = done }
    suspend fun setGeminiModel(model: String) = store.edit { it[K.geminiModel] = model.trim() }
    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[K.themeMode] = mode.name }
    suspend fun setHighContrast(on: Boolean) = store.edit { it[K.highContrast] = on }
    suspend fun setWeeklyUpdateCheck(on: Boolean) = store.edit { it[K.weeklyUpdateCheck] = on }
    suspend fun setLastAnnouncedVersion(version: String) = store.edit { it[K.lastAnnouncedVersion] = version }
}
