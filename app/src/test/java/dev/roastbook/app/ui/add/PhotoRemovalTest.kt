package dev.roastbook.app.ui.add

import dev.roastbook.app.data.db.PhotoRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoRemovalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun photo(name: String, role: PhotoRole) = StagedPhoto(tmp.newFile(name), role)

    @Test fun removeKeepsTheFileUntilTheWindowEnds() {
        val deleted = mutableListOf<File>()
        val r = PhotoRemoval { deleted += it }
        val front = photo("a.jpg", PhotoRole.FRONT)
        val back = photo("b.jpg", PhotoRole.BACK)
        assertEquals(listOf(back), r.remove(listOf(front, back), 0))
        assertEquals(listOf(RemovedPhoto(0, front)), r.pending)
        assertTrue(deleted.isEmpty())
        r.commit()
        assertEquals(listOf(front.file), deleted)
        assertTrue(r.pending.isEmpty())
    }

    @Test fun undoPutsThePhotoBackWhereItWas() {
        val r = PhotoRemoval { error("nothing is deleted on undo") }
        val list = listOf(photo("a.jpg", PhotoRole.FRONT), photo("b.jpg", PhotoRole.BACK), photo("c.jpg", PhotoRole.OTHER))
        val after = r.remove(list, 1)!!
        assertEquals(list, r.undo(after))
        assertTrue(r.pending.isEmpty())
        assertNull("one undo per window", r.undo(list))
    }

    @Test fun removalsInARowAreUndoneTogether() {
        // TalkBack's focus moves to the next remove button, so a second double-tap removes the next photo too.
        val r = PhotoRemoval { error("nothing is deleted on undo") }
        val list = listOf(photo("a.jpg", PhotoRole.FRONT), photo("b.jpg", PhotoRole.BACK), photo("c.jpg", PhotoRole.OTHER))
        val once = r.remove(list, 0)!!
        val twice = r.remove(once, 0)!!
        assertEquals(listOf(list[2]), twice)
        assertEquals(2, r.pending.size)
        assertEquals(list, r.undo(twice))
    }

    @Test fun commitDeletesEveryPendingFile() {
        val deleted = mutableListOf<File>()
        val r = PhotoRemoval { deleted += it }
        val a = photo("a.jpg", PhotoRole.FRONT)
        val b = photo("b.jpg", PhotoRole.BACK)
        r.remove(r.remove(listOf(a, b), 1)!!, 0)
        r.commit()
        assertEquals(listOf(b.file, a.file), deleted)
        assertNull(r.undo(emptyList()))
    }

    @Test fun undoClampsToAShorterListAndSkipsAMissingFile() {
        val r = PhotoRemoval()
        val a = photo("a.jpg", PhotoRole.FRONT)
        val b = photo("b.jpg", PhotoRole.BACK)
        r.remove(listOf(a, b), 1)
        assertEquals(listOf(b), r.undo(emptyList()))
        r.remove(listOf(a), 0)
        a.file.delete()
        assertEquals(emptyList<StagedPhoto>(), r.undo(emptyList()))
    }

    @Test fun anIndexNotInTheListChangesNothing() {
        val r = PhotoRemoval { error("nothing to delete") }
        assertNull(r.remove(listOf(photo("a.jpg", PhotoRole.FRONT)), 3))
        assertTrue(r.pending.isEmpty())
    }
}
