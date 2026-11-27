package dev.roastbook.app.ui.detail

import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.PhotoRole
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the photo viewer shows: this coffee's photos that have a file, by photo id, so a missing
 * file never shifts a tap onto a different photo.
 */
class ViewablePhotosTest {
    private val dir: File = Files.createTempDirectory("viewable").toFile()

    private fun photo(id: String, coffee: String = "c1", original: Boolean = true, thumb: Boolean = false): CoffeePhotoEntity {
        if (original) File(dir, "$id.jpg").writeText("x")
        if (thumb) File(dir, "$id.thumb.jpg").writeText("x")
        return CoffeePhotoEntity(id, coffee, PhotoRole.values().first(), 0, "$id.jpg", thumb = "$id.thumb.jpg", takenAt = 0, addedAt = 0)
    }

    private fun viewable(coffee: String, vararg photos: CoffeePhotoEntity) = viewablePhotos(coffee, photos.toList()) { File(dir, it) }

    @Test fun aPhotoWithNoFileIsLeftOutAndTheOthersKeepTheirIds() {
        val gone = photo("p1", original = false)
        val list = viewable("c1", gone, photo("p2"), photo("p3"))
        assertEquals(listOf("p2", "p3"), list.map { it.id })
        assertEquals(File(dir, "p2.jpg"), list.indexOfId("p2").let { list[it].file })
        assertEquals("a missing photo opens nothing", -1, list.indexOfId("p1"))
    }

    @Test fun theThumbStandsInWhenTheOriginalIsGone() {
        val list = viewable("c1", photo("p1", original = false, thumb = true))
        assertEquals(File(dir, "p1.thumb.jpg"), list.single().file)
    }

    @Test fun anotherCoffeesPhotoNeverShows() {
        assertEquals(listOf("p1"), viewable("c1", photo("p1"), photo("x9", coffee = "c2")).map { it.id })
    }
}
