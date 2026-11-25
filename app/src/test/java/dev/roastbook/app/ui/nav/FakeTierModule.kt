package dev.roastbook.app.ui.nav

import android.graphics.Bitmap
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.roastbook.app.cloud.CloudTier
import dev.roastbook.app.cloud.SecretStore
import dev.roastbook.app.data.photo.SquareExporter
import dev.roastbook.app.di.TierModule
import dev.roastbook.app.extraction.LocalAvailability
import dev.roastbook.app.extraction.LocalTier
import dev.roastbook.app.extraction.NanoHealth
import dev.roastbook.app.extraction.NanoStatus
import dev.roastbook.app.extraction.NanoTier
import dev.roastbook.app.extraction.OcrTier
import dev.roastbook.app.reader.ReaderPrefs
import dev.roastbook.app.testutil.FakeSecretStore
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.vision.PaletteExtractor
import dev.roastbook.app.vision.SubjectCropper
import javax.inject.Singleton

/**
 * Hilt tests run on the JVM, where ML Kit, AICore, LiteRT, the Keystore and the network are out of
 * reach: every swappable tier is replaced by an inert stand-in. Applies to every `@HiltAndroidTest`.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TierModule::class])
object FakeTierModule {
    @Provides fun nano(): NanoTier = object : NanoTier {
        override suspend fun status() = NanoStatus.UNAVAILABLE
        override suspend fun prepare() {}
        override suspend fun generate(images: List<Bitmap>, prompt: String): String = error("no on-device model in tests")
        override fun modelName() = "none"
    }

    @Provides fun ocr(): OcrTier = object : OcrTier {
        override suspend fun recognize(image: Bitmap) = ""
    }

    @Provides fun local(): LocalTier = object : LocalTier {
        override suspend fun availability() = LocalAvailability.UNSUPPORTED
        override suspend fun generate(images: List<Bitmap>, prompt: String): String = error("no local reader in tests")
        override fun modelName() = "none"
    }

    @Provides fun nanoHealth(impl: ReaderPrefs): NanoHealth = impl
    @Provides fun cropper(): SubjectCropper = TestGraph.WholeBitmapCrop
    @Provides fun palette(): PaletteExtractor = TestGraph.NeutralPalette
    @Provides fun squareExporter(): SquareExporter = TestGraph.NoExport

    @Provides fun cloud(): CloudTier = object : CloudTier {
        override suspend fun available() = false
        override suspend fun generate(images: List<Bitmap>, prompt: String): String = error("no cloud in tests")
        override suspend fun modelName() = "none"
    }

    @Provides @Singleton fun secrets(): SecretStore = FakeSecretStore()
}
