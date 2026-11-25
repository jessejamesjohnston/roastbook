package dev.roastbook.app.testutil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.cloud.SecretStore
import dev.roastbook.app.color.RowColors
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.photo.SquareExporter
import dev.roastbook.app.data.prefs.UserPrefs
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.reminders.ReminderScheduler
import dev.roastbook.app.vision.CropResult
import dev.roastbook.app.vision.HeroProcessor
import dev.roastbook.app.vision.PaletteExtractor
import dev.roastbook.app.vision.SubjectCropper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's data graph as Hilt would build it, on an in-memory database, for Robolectric tests
 * (`@Config(sdk = [35], application = android.app.Application::class)`). Build one per test and
 * [close] it in `@After`. Image work is stubbed: the crop is the whole bitmap and the palette is
 * the neutral fallback.
 */
class TestGraph(loadPhotos: Boolean = false) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: RoastbookDatabase = Room.inMemoryDatabaseBuilder(context, RoastbookDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val dao get() = db.coffeeDao()

    val photos: PhotoStore = if (loadPhotos) PhotoStore(context) else object : PhotoStore(context) {
        override suspend fun load(relative: String, maxEdge: Int): Bitmap? = null
    }
    val userPrefs = UserPrefs(PreferenceDataStoreFactory.create { File(context.filesDir, "test-${UUID.randomUUID()}.preferences_pb") })
    val reminders = ReminderScheduler(context, dao)
    val hero = HeroProcessor(dao, photos, WholeBitmapCrop, NeutralPalette, NoExport)
    val repo = CoffeeRepository(context, dao, photos, hero, reminders, userPrefs)
    val secrets = FakeSecretStore()

    init { photos.root.deleteRecursively() }

    /** Inserts a coffee row directly, bypassing the add flow. */
    suspend fun insertCoffee(
        id: String,
        state: CoffeeState = CoffeeState.LOGGED,
        at: Long = 1_000,
        build: CoffeeEntity.() -> CoffeeEntity = { this },
    ): CoffeeEntity {
        val e = CoffeeEntity(id, at, at, at, state).build()
        dao.insert(e)
        return e
    }

    /** A small real JPEG in the cache directory, as the camera or picker would stage it. */
    fun stagedJpeg(name: String = "staged-${UUID.randomUUID()}.jpg", size: Int = 8): File =
        File(context.cacheDir, name).apply {
            outputStream().use { Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }

    fun close() {
        db.close()
        photos.root.deleteRecursively()
    }

    object WholeBitmapCrop : SubjectCropper {
        override suspend fun crop(bitmap: Bitmap) = CropResult(Rect(0, 0, bitmap.width, bitmap.height), null, false)
    }
    object NeutralPalette : PaletteExtractor {
        override fun extract(subject: Bitmap) = RowColors(0, 0, 0, 0, fallback = true)
    }
    object NoExport : SquareExporter {
        override suspend fun maybeExport(square: File, displayName: String) {}
    }
}

/**
 * A [SecretStore] held in memory (no Keystore). Saving cleans the key as the real store does;
 * [initial] is kept as given, like a key an older version saved before cleaning existed.
 */
class FakeSecretStore(initial: String? = null) : SecretStore {
    var key: String? = initial
        private set
    private val _has = MutableStateFlow(initial != null)
    override val hasGeminiKey: StateFlow<Boolean> = _has
    override suspend fun geminiKey(): String? = key
    override suspend fun setGeminiKey(key: String?) {
        this.key = dev.roastbook.app.cloud.cleanApiKey(key)
        _has.value = this.key != null
    }
}
