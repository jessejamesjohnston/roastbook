package dev.roastbook.app.data.photo

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.data.prefs.UserPrefs
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Optional side effect of the hero crop: a copy of the square in the user's photo library. */
interface SquareExporter {
    suspend fun maybeExport(square: File, displayName: String)
}

/** Off by default (a surprise for strangers). Writes to Pictures/Roastbook through MediaStore; no permission needed on API 29+. */
@Singleton
class MediaStoreSquareExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPrefs,
) : SquareExporter {
    override suspend fun maybeExport(square: File, displayName: String) = withContext(Dispatchers.IO) {
        if (!prefs.current().saveSquaresToPhotos || !square.exists()) return@withContext
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Roastbook")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the insert")
            uri = inserted
            resolver.openOutputStream(inserted)!!.use { out -> square.inputStream().use { it.copyTo(out) } }
            resolver.update(inserted, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { e ->
            Log.w("Hero", "saving the square to Photos failed", e)
            // The hidden, half-written image goes too, rather than waiting for MediaStore to expire it.
            uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
        Unit
    }
}
