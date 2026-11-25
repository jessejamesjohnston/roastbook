package dev.roastbook.app.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the user has set up for backups plus the last outcome. Survives reinstall via Tier 0. */
data class BackupState(
    val folderUri: String? = null,
    val lastBackupAt: Long? = null,
    val lastBackupName: String? = null,
    val lastBackupBytes: Long? = null,
    val lastBackupCoffees: Int? = null,
    val lastError: String? = null,
    val keepCount: Int = DEFAULT_KEEP,
    /** Daily backup. Never on without a folder (see [BackupPrefs.state]). */
    val scheduled: Boolean = false,
    val snapshotAt: Long? = null,
    /** Why backups are off after the chosen folder stopped working; null otherwise. Choosing a folder clears it. */
    val off: BackupOff? = null,
) {
    companion object { const val DEFAULT_KEEP = 7 }
}

/** Why Roastbook forgot the chosen folder and turned backups off. */
enum class BackupOff {
    /** The grant is gone, or the folder can't be read (deleted, or its storage app is unavailable). */
    FOLDER_GONE,
    /** The folder's storage app would not give a backup file its final name. */
    FOLDER_REFUSED,
}

/**
 * Backup settings and outcomes, kept in the same backed-up DataStore as the other settings. [state]
 * never reports daily backup on without a folder, and each setter is one atomic edit. Choosing or
 * clearing a folder clears the recorded error and any reason backups are off; forgetting an
 * unusable folder clears the error and records why.
 */
@Singleton
class BackupPrefs @Inject constructor(private val store: DataStore<Preferences>) {
    private object K {
        val folder = stringPreferencesKey("backup_folder")
        val at = longPreferencesKey("backup_last_at")
        val name = stringPreferencesKey("backup_last_name")
        val bytes = longPreferencesKey("backup_last_bytes")
        val coffees = intPreferencesKey("backup_last_coffees")
        val error = stringPreferencesKey("backup_last_error")
        val keep = intPreferencesKey("backup_keep")
        val scheduled = booleanPreferencesKey("backup_scheduled")
        val snapshotAt = longPreferencesKey("backup_snapshot_at")
        val off = stringPreferencesKey("backup_off")
    }

    val state: Flow<BackupState> = store.data.map { p ->
        BackupState(
            folderUri = p[K.folder], lastBackupAt = p[K.at], lastBackupName = p[K.name], lastBackupBytes = p[K.bytes],
            lastBackupCoffees = p[K.coffees], lastError = p[K.error], keepCount = p[K.keep] ?: BackupState.DEFAULT_KEEP,
            // Off whenever there's no folder, so the switch is never on-but-unable-to-run. With a
            // folder: what the person set, or on when nothing is stored (an install that chose its
            // folder before the switch existed has no stored value and backs up daily).
            scheduled = p[K.folder] != null && (p[K.scheduled] ?: true), snapshotAt = p[K.snapshotAt],
            off = p[K.off]?.let { v -> BackupOff.entries.firstOrNull { it.name == v } ?: BackupOff.FOLDER_GONE },
        )
    }

    suspend fun current(): BackupState = state.first()

    /**
     * Choosing the first folder switches daily backup on (choosing where backups go is the consent);
     * clearing it switches daily off. Changing one folder for another leaves the switch as it was.
     */
    suspend fun setFolder(uri: String?) = store.edit { p ->
        val hadFolder = p[K.folder] != null
        if (uri == null) {
            p.remove(K.folder)
            p[K.scheduled] = false
        } else {
            p[K.folder] = uri
            if (!hadFolder) p[K.scheduled] = true
        }
        p.remove(K.error)
        p.remove(K.off)
    }

    /**
     * Forgets [uri] because it can no longer take backups, and says why. Like clearing the folder, this
     * switches daily backup off. Nothing happens if the chosen folder is no longer [uri] (the person
     * picked another while the check ran).
     */
    suspend fun folderUnusable(uri: String, why: BackupOff) = store.edit { p ->
        if (p[K.folder] != uri) return@edit
        p.remove(K.folder)
        p[K.scheduled] = false
        p.remove(K.error)
        p[K.off] = why.name
    }

    suspend fun recordSuccess(at: Long, name: String, bytes: Long, coffees: Int) = store.edit { p ->
        p[K.at] = at; p[K.name] = name; p[K.bytes] = bytes; p[K.coffees] = coffees; p.remove(K.error)
    }

    suspend fun recordError(message: String) = store.edit { p -> p[K.error] = message.take(200) }

    suspend fun setScheduled(on: Boolean) = store.edit { p -> p[K.scheduled] = on }

    suspend fun recordSnapshot(at: Long) = store.edit { p -> p[K.snapshotAt] = at }
}
