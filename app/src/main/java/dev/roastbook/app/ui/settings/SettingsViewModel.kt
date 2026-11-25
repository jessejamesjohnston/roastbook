package dev.roastbook.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.R
import dev.roastbook.app.data.backup.BackupManager
import dev.roastbook.app.data.backup.BackupManifest
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.backup.BackupState
import dev.roastbook.app.data.backup.RestoreError
import dev.roastbook.app.data.backup.isOutOfSpace
import dev.roastbook.app.data.prefs.UserPrefs
import dev.roastbook.app.data.prefs.UserSettings
import dev.roastbook.app.cloud.GeminiClient
import dev.roastbook.app.cloud.SecretStore
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.reminders.ReminderScheduler
import dev.roastbook.app.update.UpdateCheckResult
import dev.roastbook.app.update.UpdateChecker
import dev.roastbook.app.update.WeeklyUpdateCheck
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/**
 * The About card's update row: whether a check is running, what the last one found, and whether the
 * release page should open now (set once per check that found a newer version).
 */
data class UpdateUi(val checking: Boolean = false, val result: UpdateCheckResult? = null, val openPage: Boolean = false)

/** What Settings shows about backups beyond the stored state: folder name, in-flight work, one-shot results. */
data class BackupUi(
    val folderName: String? = null,
    val working: Working = Working.NONE,
    val message: Message? = null,
    val pendingRestore: PendingRestore? = null,
) {
    enum class Working { NONE, BACKUP, RESTORE }
    /** A resource id plus its format args; the screen renders it. */
    data class Message(val res: Int, val args: List<Any> = emptyList(), val isError: Boolean = false)
    /** [sha256] is the digest of the file as it was when the dialog opened; the restore rechecks it. */
    data class PendingRestore(val uri: Uri, val manifest: BackupManifest, val sha256: String)
}

/**
 * State and actions for Settings: preferences, the Gemini key (only its last four characters reach
 * UI state), update checks, and backup and restore. Settings writes go to [UserPrefs] and
 * [BackupPrefs]; backup and restore go through [BackupManager]. A restore that has started finishes
 * even if Settings is left, and re-arms the restored reminders and reads before it reports.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupManager,
    private val prefs: BackupPrefs,
    private val repo: CoffeeRepository,
    private val reminders: ReminderScheduler,
    private val userPrefs: UserPrefs,
    private val secrets: SecretStore,
    private val gemini: GeminiClient,
    private val updates: UpdateChecker,
    private val weeklyCheck: WeeklyUpdateCheck,
) : ViewModel() {
    private val _update = MutableStateFlow(UpdateUi())
    val update: StateFlow<UpdateUi> = _update

    /** Asks GitHub once whether a version newer than [installedVersion] is out. Ignored while a check runs. */
    fun checkForUpdates(installedVersion: String) {
        if (_update.value.checking) return
        _update.value = UpdateUi(checking = true)
        viewModelScope.launch {
            val found = updates.check(installedVersion)
            _update.value = UpdateUi(result = found, openPage = found is UpdateCheckResult.Available)
        }
    }

    /** The screen opened the release page; don't open it again on the next recomposition. */
    fun updatePageOpened() { _update.value = _update.value.copy(openPage = false) }

    /** Stores the weekly-check switch and arms or cancels the weekly job to match. */
    fun setWeeklyUpdateCheck(on: Boolean) {
        viewModelScope.launch {
            userPrefs.setWeeklyUpdateCheck(on)
            weeklyCheck.sync(on)
        }
    }

    /** Cloud key state for the card: present?, last 4 characters, test result. The key itself never sits in UI state. */
    data class CloudUi(val hasKey: Boolean = false, val tail: String? = null, val testing: Boolean = false, val message: BackupUi.Message? = null)
    private val _cloud = MutableStateFlow(CloudUi())
    val cloud: StateFlow<CloudUi> = _cloud
    init {
        viewModelScope.launch {
            secrets.hasGeminiKey.collect { has ->
                _cloud.value = _cloud.value.copy(
                    hasKey = has,
                    tail = if (has) secrets.geminiKey()?.takeLast(4) else null,
                )
            }
        }
    }
    /** The running Test key check, if any; saving or removing a key cancels it (and its HTTP call). */
    private var testJob: Job? = null

    /** A check of a key that is no longer stored would report on the wrong key: drop it and its spinner. */
    private fun dropKeyTest() {
        testJob?.cancel()
        testJob = null
        _cloud.value = _cloud.value.copy(testing = false, message = null)
    }

    // Replacing a key leaves "has a key" true, so the flow above does not fire again; refresh the
    // shown ending here.
    fun saveKey(key: String) {
        dropKeyTest()
        viewModelScope.launch {
            secrets.setGeminiKey(key)
            _cloud.value = _cloud.value.copy(tail = secrets.geminiKey()?.takeLast(4), message = null)
        }
    }
    fun removeKey() {
        dropKeyTest()
        viewModelScope.launch { secrets.setGeminiKey(null); _cloud.value = _cloud.value.copy(message = null) }
    }
    fun setModel(model: String) { viewModelScope.launch { userPrefs.setGeminiModel(model) } }
    fun testKey() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            val key = secrets.geminiKey() ?: return@launch
            val model = userPrefs.current().geminiModel
            _cloud.value = _cloud.value.copy(testing = true, message = null)
            val r = gemini.checkKey(key, model)
            _cloud.value = _cloud.value.copy(
                testing = false,
                message = if (r.isSuccess) BackupUi.Message(R.string.settings_cloud_test_ok, listOf(model))
                else dev.roastbook.app.cloud.CloudFailure.of(r.exceptionOrNull()).let { f ->
                    BackupUi.Message(f.message, if (f == dev.roastbook.app.cloud.CloudFailure.MODEL) listOf(model) else emptyList(), isError = true)
                },
            )
        }
    }

    val state: StateFlow<BackupState> = prefs.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BackupState())
    val settings: StateFlow<UserSettings> = userPrefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    fun setSecondLookEnabled(on: Boolean) { viewModelScope.launch { userPrefs.setSecondLookEnabled(on) } }
    fun setSecondLookDays(days: Int) { viewModelScope.launch { userPrefs.setSecondLookDays(days) } }
    fun setSaveSquares(on: Boolean) { viewModelScope.launch { userPrefs.setSaveSquares(on) } }
    fun setThemeMode(mode: dev.roastbook.app.data.prefs.ThemeMode) {
        // Persist first: applying the night mode recreates the activity, which re-reads the pref on start.
        viewModelScope.launch {
            userPrefs.setThemeMode(mode)
            dev.roastbook.app.ui.theme.NightMode.apply(context, mode)
        }
    }
    fun setHighContrast(on: Boolean) { viewModelScope.launch { userPrefs.setHighContrast(on) } }
    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui

    init {
        viewModelScope.launch { prefs.state.collect { s -> refreshFolder(s) } }
    }

    /** Names the picked folder; one that can no longer be used is forgotten instead, and the next state says why. */
    private suspend fun refreshFolder(s: BackupState) {
        val uri = s.folderUri
        // A folder whose storage app can't name it right now stays chosen; a backup that fails decides.
        val name = if (uri != null && backups.checkGrant(uri)) {
            backups.sinkFor(s)?.displayName() ?: context.getString(R.string.settings_backup_folder_unnamed)
        } else null
        _ui.value = _ui.value.copy(folderName = name)
    }

    /** The tree the user picked: take a persistable grant so access survives reboots. */
    fun folderPicked(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        viewModelScope.launch { prefs.setFolder(uri.toString()) }
    }

    fun setScheduled(on: Boolean) { viewModelScope.launch { prefs.setScheduled(on) } }

    fun backUpNow() {
        val s = state.value
        val sink = backups.sinkFor(s) ?: return
        _ui.value = _ui.value.copy(working = BackupUi.Working.BACKUP, message = null)
        viewModelScope.launch {
            try {
                val r = backups.backupNow(sink, "manual")
                _ui.value = _ui.value.copy(
                    working = BackupUi.Working.NONE,
                    message = BackupUi.Message(
                        R.string.settings_backup_done,
                        listOf(
                            context.resources.getQuantityString(R.plurals.coffees, r.coffees, r.coffees),
                            humanBytes(r.bytes),
                        ),
                    ),
                )
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // A failure that turned backups off is said once, by the card's "Backups are off" line.
                val off = prefs.current().folderUri != s.folderUri
                _ui.value = _ui.value.copy(
                    working = BackupUi.Working.NONE,
                    message = if (off) {
                        null
                    } else {
                        BackupUi.Message(
                            R.string.settings_backup_failed,
                            listOf(
                                context.getString(
                                    if (isOutOfSpace(e)) R.string.error_backup_full else R.string.error_backup_io,
                                ),
                            ),
                            isError = true,
                        )
                    },
                )
            }
        }
    }

    /** Step 1 of restore: read the manifest so the confirmation can say what it is about to replace. */
    fun restorePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val seen = backups.inspect { context.contentResolver.openInputStream(uri) }
                _ui.value = _ui.value.copy(pendingRestore = BackupUi.PendingRestore(uri, seen.manifest, seen.sha256), message = null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    message = BackupUi.Message(
                        R.string.settings_restore_failed,
                        listOf(context.getString(errorRes(e))),
                        isError = true,
                    ),
                )
            }
        }
    }

    fun cancelRestore() { _ui.value = _ui.value.copy(pendingRestore = null) }

    fun confirmRestore() {
        val pending = _ui.value.pendingRestore ?: return
        _ui.value = _ui.value.copy(pendingRestore = null, working = BackupUi.Working.RESTORE, message = null)
        viewModelScope.launch {
            try {
                // The restored rows carry their own reminders and half-finished reads; pick them up in
                // the same uncancellable step, so leaving Settings mid-restore can't skip the alarms.
                // The Tier 0 snapshot is the restore's own business: rebuilt before it returns, or by the next refresh if that fails.
                val m = withContext(NonCancellable) {
                    backups.restore({ context.contentResolver.openInputStream(pending.uri) }, pending.sha256).also {
                        runCatching { repo.startupMaintenance() }
                        runCatching { reminders.rescheduleAll() }
                    }
                }
                _ui.value = _ui.value.copy(
                    working = BackupUi.Working.NONE,
                    message = BackupUi.Message(
                        R.string.settings_restore_done,
                        listOf(context.resources.getQuantityString(R.plurals.coffees, m.coffees, m.coffees)),
                    ),
                )
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    working = BackupUi.Working.NONE,
                    message = BackupUi.Message(
                        R.string.settings_restore_failed,
                        listOf(context.getString(errorRes(e))),
                        isError = true,
                    ),
                )
            }
        }
    }

    private fun errorRes(e: Exception): Int = when (e) {
        is RestoreError.NewerSchema -> R.string.error_backup_newer
        is RestoreError.Corrupt -> R.string.error_backup_corrupt
        is RestoreError.UnsafePath -> R.string.error_backup_unsafe
        is RestoreError.BadArchive -> R.string.error_backup_bad_archive
        is RestoreError.Changed -> R.string.error_backup_changed
        is RestoreError.NoSpace -> R.string.error_backup_space
        else -> R.string.error_backup_io
    }

    companion object {
        fun humanBytes(b: Long): String = when {
            b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, b / (1024f * 1024f))
            b >= 1L shl 10 -> "%d KB".format(java.util.Locale.US, b / 1024)
            else -> "$b B"
        }
    }
}
