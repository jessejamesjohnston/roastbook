package dev.roastbook.app.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The app's Room database (coffee, coffee_photo, source). Upgrades keep every row: each schema
 * version has an auto-migration or a hand-written one in [MIGRATIONS], and there is no destructive
 * fallback. The schema is exported because the auto-migrations are generated from it.
 */
@Database(
    entities = [CoffeeEntity::class, CoffeePhotoEntity::class, SourceEntity::class],
    version = 4,
    exportSchema = true,
    // v4: next_attempt_at + extraction_trail (parked retries and the per-tier record of a read).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 3, to = 4)],
)
@TypeConverters(Converters::class)
abstract class RoastbookDatabase : RoomDatabase() {
    abstract fun coffeeDao(): CoffeeDao

    companion object {
        /** v3: per-row record of hand-edited extracted fields (see [CoffeeEntity.userEdited]). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `coffee` ADD COLUMN `user_edited` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3)
    }
}
