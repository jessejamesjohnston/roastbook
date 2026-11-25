package dev.roastbook.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.roastbook.app.cloud.CloudTier
import dev.roastbook.app.cloud.GeminiCloudTier
import dev.roastbook.app.cloud.KeystoreSecretStore
import dev.roastbook.app.cloud.SecretStore
import dev.roastbook.app.data.photo.MediaStoreSquareExporter
import dev.roastbook.app.data.photo.SquareExporter
import dev.roastbook.app.extraction.GeminiNanoTier
import dev.roastbook.app.extraction.LocalTier
import dev.roastbook.app.extraction.MlKitOcrTier
import dev.roastbook.app.extraction.NanoHealth
import dev.roastbook.app.extraction.NanoTier
import dev.roastbook.app.extraction.OcrTier
import dev.roastbook.app.reader.LiteRtLocalReader
import dev.roastbook.app.reader.ReaderPrefs
import dev.roastbook.app.vision.AndroidPaletteExtractor
import dev.roastbook.app.vision.MlKitSubjectCropper
import dev.roastbook.app.vision.PaletteExtractor
import dev.roastbook.app.vision.SubjectCropper

/** The swappable tiers: production bindings here, fakes in tests. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TierModule {
    @Binds abstract fun nano(impl: GeminiNanoTier): NanoTier
    @Binds abstract fun ocr(impl: MlKitOcrTier): OcrTier
    @Binds abstract fun local(impl: LiteRtLocalReader): LocalTier
    @Binds abstract fun nanoHealth(impl: ReaderPrefs): NanoHealth
    @Binds abstract fun cropper(impl: MlKitSubjectCropper): SubjectCropper
    @Binds abstract fun palette(impl: AndroidPaletteExtractor): PaletteExtractor
    @Binds abstract fun squareExporter(impl: MediaStoreSquareExporter): SquareExporter
    @Binds abstract fun cloud(impl: GeminiCloudTier): CloudTier
    @Binds abstract fun secrets(impl: KeystoreSecretStore): SecretStore
}
