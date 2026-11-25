package dev.roastbook.app.ui.add

import java.io.File

/** A photo taken off the camera screen's list, with the place it came from. */
data class RemovedPhoto(val index: Int, val photo: StagedPhoto)

/**
 * The removals that can still be undone. Removing a photo takes it off the list but keeps its file
 * until the undo window ends: the window ends when the user changes the list in any other way (a
 * new photo, a label change, saving, leaving) or lets "Undo" time out. Only then are the files
 * deleted. Removals in a row share one window, and Undo puts them all back: with TalkBack, focus
 * moves to the next photo's remove button, so a second double-tap is an easy slip.
 */
class PhotoRemoval(private val deleteFile: (File) -> Unit = { it.delete() }) {
    /** Oldest first; each index is the photo's place in the list at the moment it was removed. */
    var pending: List<RemovedPhoto> = emptyList()
        private set

    /** Takes [index] out of [list]; returns the new list, or null for an index not in it. */
    fun remove(list: List<StagedPhoto>, index: Int): List<StagedPhoto>? {
        val photo = list.getOrNull(index) ?: return null
        pending = pending + RemovedPhoto(index, photo)
        return list.filterIndexed { i, _ -> i != index }
    }

    /** Puts every pending photo back where it was, newest removal first; null if there is nothing to undo. */
    fun undo(list: List<StagedPhoto>): List<StagedPhoto>? {
        if (pending.isEmpty()) return null
        val out = list.toMutableList()
        pending.asReversed().forEach { r -> if (r.photo.file.isFile) out.add(r.index.coerceIn(0, out.size), r.photo) }
        pending = emptyList()
        return out
    }

    /** Ends the undo window: the pending photos' files are deleted. */
    fun commit() {
        pending.forEach { deleteFile(it.photo.file) }
        pending = emptyList()
    }
}
