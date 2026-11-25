package dev.roastbook.app.ui.add

import dev.roastbook.app.data.db.PhotoRole

/**
 * Where the camera screen is in "front, back, anything else". The screen's title is this step's
 * instruction, and the next photo gets the role the instruction asks for, so the title and the
 * new photo's label never disagree. Driven by the labels, not the count: a photo relabeled from
 * Front to Box puts the screen back on the front step.
 */
enum class CaptureStep(val nextRole: PhotoRole) {
    FRONT(PhotoRole.FRONT),
    BACK(PhotoRole.BACK),
    MORE(PhotoRole.OTHER);

    companion object {
        fun of(roles: List<PhotoRole>): CaptureStep = when {
            PhotoRole.FRONT !in roles -> FRONT
            PhotoRole.BACK !in roles -> BACK
            else -> MORE
        }
    }
}
