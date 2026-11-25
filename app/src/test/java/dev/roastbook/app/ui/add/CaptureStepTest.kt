package dev.roastbook.app.ui.add

import dev.roastbook.app.data.db.PhotoRole
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStepTest {
    @Test fun frontThenBackThenAnythingElse() {
        assertEquals(CaptureStep.FRONT, CaptureStep.of(emptyList()))
        assertEquals(CaptureStep.BACK, CaptureStep.of(listOf(PhotoRole.FRONT)))
        assertEquals(CaptureStep.MORE, CaptureStep.of(listOf(PhotoRole.FRONT, PhotoRole.BACK)))
        assertEquals(CaptureStep.MORE, CaptureStep.of(listOf(PhotoRole.FRONT, PhotoRole.BACK, PhotoRole.PAMPHLET)))
    }

    @Test fun theNextPhotoGetsTheRoleTheTitleAsksFor() {
        assertEquals(PhotoRole.FRONT, CaptureStep.of(emptyList()).nextRole)
        assertEquals(PhotoRole.BACK, CaptureStep.of(listOf(PhotoRole.FRONT)).nextRole)
        assertEquals(PhotoRole.OTHER, CaptureStep.of(listOf(PhotoRole.FRONT, PhotoRole.BACK)).nextRole)
    }

    @Test fun followsTheLabelsNotTheCount() {
        // The second photo was relabeled Pamphlet: the back is still to come.
        assertEquals(CaptureStep.BACK, CaptureStep.of(listOf(PhotoRole.FRONT, PhotoRole.PAMPHLET)))
        // The first photo was relabeled Box: the front is still to come.
        assertEquals(CaptureStep.FRONT, CaptureStep.of(listOf(PhotoRole.BOX)))
        // A back without a front: ask for the front.
        assertEquals(CaptureStep.FRONT, CaptureStep.of(listOf(PhotoRole.BACK)))
    }
}
