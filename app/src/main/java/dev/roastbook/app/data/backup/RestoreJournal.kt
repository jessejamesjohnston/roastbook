package dev.roastbook.app.data.backup

import android.content.Context
import android.database.Cursor
import android.util.Log
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.photo.PhotoStore
import java.io.File

/**
 * A restore replaces the photo tree and then the rows. Between those two steps the phone holds the
 * new photos next to the old rows, and process death there left that mismatch for good: every row
 * pointed at a file that was no longer on disk.
 *
 * The marker names the directory the old tree was set aside in, so the next start (before Room
 * opens) can put it back. It is written before the swap and removed only once the rows have
 * committed, which makes the whole restore all-or-nothing from the user's side.
 */
object RestoreJournal {
    const val MARKER = "restore.inprogress"
    private const val TAG = "Restore"

    fun marker(context: Context): File = File(context.filesDir, MARKER)

    /**
     * [restored] is [fingerprint] of the library being restored. Without it the next start always
     * rolls back; with it, a start that finds the live rows already ARE that library (the kill came
     * after the rows committed) keeps the restored photos instead of pairing them with old ones.
     */
    fun begin(context: Context, setAside: File, restored: String? = null) {
        marker(context).writeText(setAside.name + (restored?.let { "\n$it" } ?: ""))
    }

    /**
     * One value for a library: each coffee's id and last change, each photo's id and the files it
     * names. Ids alone are not enough: an older backup of the same coffees has the same ids as the
     * live rows before the commit, and those rows can name files (a square written since) that the
     * archive's photo tree does not have.
     */
    fun fingerprint(coffees: List<CoffeeEntity>, photos: List<CoffeePhotoEntity>): String =
        digest(coffees.map { coffeeKey(it.id, it.updatedAt) }, photos.map { photoKey(it.id, it.original, it.square, it.thumb) })

    private fun coffeeKey(id: String, updatedAt: Long) = "$id\u0001$updatedAt"
    private fun photoKey(id: String, original: String?, square: String?, thumb: String?) =
        listOf(id, original, square, thumb).joinToString("\u0001") { it ?: "\u0002" }

    private fun digest(coffeeKeys: List<String>, photoKeys: List<String>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        coffeeKeys.sorted().forEach { md.update(it.toByteArray()); md.update(0) }
        md.update(1)
        photoKeys.sorted().forEach { md.update(it.toByteArray()); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** [fingerprint] of the live database, read before Room opens; null when it can't be read. */
    private fun liveFingerprint(context: Context, dbName: String): String? = runCatching {
        val path = context.getDatabasePath(dbName)
        if (!path.exists()) return@runCatching null
        android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { d ->
            fun <T> rows(sql: String, key: (Cursor) -> T) = d.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(key(c)) } }
            fun Cursor.text(i: Int): String? = if (isNull(i)) null else getString(i)
            digest(
                rows("SELECT id, updated_at FROM coffee") { coffeeKey(it.getString(0), it.getLong(1)) },
                rows("SELECT id, original, square, thumb FROM coffee_photo") { photoKey(it.getString(0), it.text(1), it.text(2), it.text(3)) },
            )
        }
    }.getOrNull()

    private val SET_ASIDE = Regex("""photos\.old-\d+""")

    fun clear(context: Context) {
        marker(context).delete()
    }

    /**
     * Called before Room opens, with the live database's name. An unfinished restore rolls back to
     * the photos the user had, which are the ones the rows that survived still name; when the rows
     * had already committed (the live library is the restored one), the restored photos stay.
     */
    fun recoverIfNeeded(context: Context, dbName: String? = null) {
        val m = marker(context)
        if (!m.exists()) return
        val root = File(context.filesDir, PhotoStore.ROOT)
        val lines = runCatching { m.readLines().map { it.trim() } }.getOrDefault(emptyList())
        // Only a tree [begin] could have named: a direct child of filesDir called photos.old-<millis>.
        val setAside = File(context.filesDir, lines.firstOrNull().orEmpty())
            .takeIf { SET_ASIDE.matches(it.name) && it.parentFile?.canonicalFile == context.filesDir.canonicalFile }
            ?: File(context.filesDir, "photos.old-invalid")
        val restored = lines.getOrNull(1)?.takeIf { it.isNotEmpty() }
        if (restored != null && dbName != null && liveFingerprint(context, dbName) == restored) {
            setAside.takeIf { SET_ASIDE.matches(it.name) }?.deleteRecursively()
            Log.i(TAG, "an unfinished restore had committed; kept the restored photos")
            m.delete()
            return
        }
        val ours = SET_ASIDE.matches(setAside.name)
        val rolledBack = runCatching {
            if (ours && setAside.isDirectory) {
                root.deleteRecursively()
                if (!setAside.renameTo(root)) setAside.copyRecursively(root, overwrite = true)
                Log.i(TAG, "rolled an unfinished restore back to the previous photos")
            }
        }.onFailure { Log.w(TAG, "could not roll the unfinished restore back; the next start tries again", it) }.isSuccess
        // A failed rollback keeps both recovery inputs: the marker and the set-aside tree.
        if (!rolledBack) return
        // The marker goes before the copy's source: a kill while that tree is being deleted must not
        // leave a partial tree that the next start would put in place of the complete copy.
        m.delete()
        if (ours) setAside.deleteRecursively()
    }
}
