package dev.roastbook.app.ui.add

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.data.repo.NewPhoto
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A photo taken or picked on the Add screen, in the staging cache until the coffee is saved. */
data class StagedPhoto(val file: File, val role: PhotoRole)

/**
 * State for the Add screen: the staged photos and their roles, camera captures and library imports
 * in flight, undoable removals, and the save. The staged list survives process death through saved
 * state. Save waits until no capture or import is running, freezes the list while it runs, and on
 * failure keeps every staged photo so the user can try again.
 */
@HiltViewModel
class AddViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: CoffeeRepository,
    val photoStore: PhotoStore,
    private val saved: SavedStateHandle,
) : ViewModel() {
    // The staged list is kept in saved state (paths and roles), so photos already taken survive the
    // app being killed in the background; the files themselves sit in the cache until saved.
    private val _photos = MutableStateFlow(restoreStaged())
    val photos: StateFlow<List<StagedPhoto>> = _photos
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed
    /** Library photos still being copied in; Done waits for them. */
    private val _importing = MutableStateFlow(0)
    val importing: StateFlow<Int> = _importing
    private val _importFailed = MutableStateFlow(false)
    val importFailed: StateFlow<Boolean> = _importFailed
    private val removal = PhotoRemoval()
    /** Photos removed while they can still be put back ("Photo removed · Undo"); empty when there are none. */
    private val _removed = MutableStateFlow<List<RemovedPhoto>>(emptyList())
    val removed: StateFlow<List<RemovedPhoto>> = _removed
    /** Shutter presses whose photo the camera is still writing; Done waits for them. */
    private val inFlight = mutableSetOf<File>()
    private val _capturing = MutableStateFlow(0)
    val capturing: StateFlow<Int> = _capturing
    private val _captureFailed = MutableStateFlow(false)
    val captureFailed: StateFlow<Boolean> = _captureFailed
    /** Whether the camera started: null until it reports, then true, or false when there is no usable camera. */
    private val _cameraReady = MutableStateFlow<Boolean?>(null)
    val cameraReady: StateFlow<Boolean?> = _cameraReady
    /** Set once the screen is left: a shot that finishes afterwards belongs to nobody. */
    private var closed = false

    fun cameraStarted(ready: Boolean?) { _cameraReady.value = ready }

    /** A shutter press: the file the camera writes to. Each one ends in [addCaptured] or [captureFailed]. */
    fun newCaptureFile(): File {
        val file = photoStore.newStagingFile()
        inFlight += file
        _capturing.value = inFlight.size
        _captureFailed.value = false
        return file
    }

    private fun captureEnded(file: File) {
        if (inFlight.remove(file)) _capturing.value = inFlight.size
    }

    /** The camera could not take the shot: whatever it wrote goes, and the screen says so. */
    fun captureFailed(file: File) {
        captureEnded(file)
        file.delete()
        if (!closed) _captureFailed.value = true
    }

    /**
     * The role the screen's title asks for ([CaptureStep]): front, then back, then Other. The user
     * can change any of them, and the photos can come in any order.
     */
    private fun nextRole(): PhotoRole = CaptureStep.of(_photos.value.map { it.role }).nextRole

    private fun setPhotos(transform: (List<StagedPhoto>) -> List<StagedPhoto>) {
        _photos.update(transform)
        saved[KEY_PATHS] = ArrayList(_photos.value.map { it.file.path })
        saved[KEY_ROLES] = ArrayList(_photos.value.map { it.role.name })
    }

    private fun restoreStaged(): List<StagedPhoto> {
        val paths = saved.get<ArrayList<String>>(KEY_PATHS) ?: return emptyList()
        val roles = saved.get<ArrayList<String>>(KEY_ROLES).orEmpty()
        return paths.mapIndexedNotNull { i, path ->
            val f = File(path).takeIf { it.isFile } ?: return@mapIndexedNotNull null
            StagedPhoto(f, roles.getOrNull(i)?.let { r -> PhotoRole.entries.firstOrNull { it.name == r } } ?: PhotoRole.OTHER)
        }
    }

    // While the photos are being saved the list is frozen: a removal would delete a file the import
    // is about to read, and anything added would be dropped when the saved photos leave the list.
    fun addCaptured(file: File) {
        captureEnded(file)
        if (_saving.value || closed) { file.delete(); return }
        endUndo()
        if (file.exists()) setPhotos { it + StagedPhoto(file, nextRole()) }
    }

    fun addFromLibrary(uris: List<Uri>) {
        if (uris.isEmpty() || _saving.value) return
        endUndo()
        _importFailed.value = false
        _importing.update { it + 1 }
        viewModelScope.launch {
            try {
                for (uri in uris) {
                    // One unreadable photo (a cloud copy that can't be fetched, a full disk) is
                    // skipped and reported; the others still come in.
                    val f = try {
                        photoStore.stageFromStream { context.contentResolver.openInputStream(uri) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("Add", "could not copy a picked photo: ${e::class.simpleName}")
                        _importFailed.value = true
                        null
                    } ?: continue
                    setPhotos { it + StagedPhoto(f, nextRole()) }
                }
            } finally {
                _importing.update { it - 1 }
            }
        }
    }

    fun setRole(index: Int, role: PhotoRole) {
        if (_saving.value) return
        endUndo()
        setPhotos { list -> list.mapIndexed { i, p -> if (i == index) p.copy(role = role) else p } }
    }

    /** Takes the photo off the list; its file stays until the undo window ends ([PhotoRemoval]). */
    fun remove(index: Int) {
        if (_saving.value) return
        val next = removal.remove(_photos.value, index) ?: return
        setPhotos { next }
        _removed.value = removal.pending
    }

    fun undoRemove() {
        if (_saving.value) return
        val next = removal.undo(_photos.value)
        _removed.value = emptyList()
        if (next != null) setPhotos { next }
    }

    /** The undo offer timed out. [which] guards against ending a window a newer removal extended. */
    fun undoExpired(which: List<RemovedPhoto>) { if (removal.pending == which) endUndo() }

    private fun endUndo() {
        removal.commit()
        _removed.value = emptyList()
    }

    fun save(onCreated: (String) -> Unit) {
        if (_photos.value.isEmpty() || _saving.value || _importing.value > 0 || _capturing.value > 0) return
        endUndo()
        _saving.value = true
        _saveFailed.value = false
        val batch = _photos.value
        viewModelScope.launch {
            try {
                // The repository finishes the import even if this scope is canceled meanwhile. It
                // deletes the staged files only once the coffee and all its photos are stored.
                val id = repo.create(batch.map { NewPhoto(it.file, it.role) })
                setPhotos { it - batch.toSet() }
                onCreated(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Add", "save failed", e)
                _saveFailed.value = true // staged photos are kept so the user can try again
            } finally {
                _saving.value = false
            }
        }
    }

    fun discard() { closed = true; endUndo(); _photos.value.forEach { it.file.delete() }; setPhotos { emptyList() } }

    override fun onCleared() { closed = true; endUndo() }

    private companion object {
        const val KEY_PATHS = "staged_paths"
        const val KEY_ROLES = "staged_roles"
    }
}
