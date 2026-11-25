package dev.roastbook.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.roastbook.app.data.backup.AutoBackupAdopter
import dev.roastbook.app.data.backup.RestoreJournal
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.extraction.Clock
import dev.roastbook.app.extraction.ExtractionTimeouts
import dev.roastbook.app.reader.ReaderPrefs
import java.io.File
import javax.inject.Singleton

/** The database, the preference stores and the plain values the rest of the graph needs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    const val DB_NAME = "roastbook.db"

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): RoastbookDatabase {
        // A restore that was killed between the photo swap and the row commit: put the old photos
        // back before anything reads them.
        RestoreJournal.recoverIfNeeded(context, DB_NAME)
        // A fresh install on a signed-in device: Auto Backup restored our snapshot; adopt it before Room opens.
        AutoBackupAdopter.adoptIfNeeded(context, DB_NAME)
        return Room.databaseBuilder(context, RoastbookDatabase::class.java, DB_NAME)
            .addMigrations(*RoastbookDatabase.MIGRATIONS)
            .build()
    }

    @Provides
    fun coffeeDao(db: RoastbookDatabase): CoffeeDao = db.coffeeDao()

    /** Preferences live under files/datastore/, which the Tier 0 backup rules include. */
    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    /**
     * Device-scoped reader state, in its own store under no_backup/ (never backed up; see
     * [ReaderPrefs]). Not a DataStore<Preferences> binding, so it cannot be confused with the
     * backed-up settings store above.
     */
    @Provides @Singleton
    fun readerPrefs(@ApplicationContext context: Context): ReaderPrefs =
        ReaderPrefs(PreferenceDataStoreFactory.create { File(context.noBackupFilesDir, "reader-settings.preferences_pb") })

    @Provides
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides
    fun extractionTimeouts(): ExtractionTimeouts = ExtractionTimeouts()
}
