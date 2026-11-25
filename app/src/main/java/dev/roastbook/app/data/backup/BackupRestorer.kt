package dev.roastbook.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import dev.roastbook.app.data.backup.BackupManager.Companion.DB_ENTRY
import dev.roastbook.app.data.backup.BackupManager.Companion.EXPANSION_SLACK
import dev.roastbook.app.data.backup.BackupManager.Companion.MANIFEST_ENTRY
import dev.roastbook.app.data.backup.BackupManager.Companion.MAX_ENTRIES
import dev.roastbook.app.data.backup.BackupManager.Companion.MAX_EXPANSION
import dev.roastbook.app.data.backup.BackupManager.Companion.MAX_MANIFEST_BYTES
import dev.roastbook.app.data.backup.BackupManager.Companion.SAFE_ENTRY
import dev.roastbook.app.data.backup.BackupManager.Companion.SPACE_RESERVE
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.db.SourceEntity
import dev.roastbook.app.data.photo.PhotoStore
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads archives back: [inspect] for the confirmation dialog, [restore] to replace the library.
 * Everything is staged under cache and verified before the photo directory is swapped and the rows
 * replaced in one transaction, with a [RestoreJournal] marker so a kill between those two steps
 * rolls back at the next start. Callers of [restore] hold [BackupManager]'s lock.
 */
internal class BackupRestorer(
    private val context: Context,
    private val db: RoastbookDatabase,
    private val photos: PhotoStore,
    private val snapshots: AutoSnapshot,
) {
    /** Reads the manifest and digests the whole file, for the confirmation dialog. */
    suspend fun inspect(open: () -> InputStream?): BackupInspection = withContext(Dispatchers.IO) {
        val whole = MessageDigest.getInstance("SHA-256")
        var manifest: BackupManifest? = null
        val seen = mutableSetOf<String>()
        var entries = 0
        var expanded = 0L
        val raw = CountingInputStream(open() ?: throw RestoreError.BadArchive("could not open"))
        DigestInputStream(raw, whole).buffered().use { stream ->
            val zip = ZipInputStream(stream)
            val buf = ByteArray(64 * 1024)
            generateSequence { zip.nextEntry }.forEach { e ->
                ensureActive()
                if (++entries > MAX_ENTRIES) throw RestoreError.BadArchive("more than $MAX_ENTRIES entries")
                if (e.isDirectory) throw RestoreError.UnsafePath(e.name) // we never write directory entries
                if (!seen.add(e.name)) throw RestoreError.BadArchive("two entries named ${e.name}")
                if (e.name == MANIFEST_ENTRY) { manifest = parseManifest(readBounded(zip, MAX_MANIFEST_BYTES)); return@forEach }
                // Every other entry is read through here (not skipped inside the zip reader), so a
                // small entry that inflates without end is stopped: photos and the database never
                // expand to more than a small multiple of what they take in the file.
                while (true) {
                    val n = zip.read(buf)
                    if (n <= 0) break
                    expanded += n
                    if (expanded > raw.count * MAX_EXPANSION + EXPANSION_SLACK) {
                        throw RestoreError.BadArchive("an entry expands far past its size in the file")
                    }
                }
            }
            drain(stream) // the central directory sits past the last entry and is part of the file
        }
        BackupInspection(manifest ?: throw RestoreError.BadArchive("no manifest"), whole.digest().toHex())
    }

    /**
     * Replace everything on this phone with the archive. Verifies before it touches anything, and
     * restoring the same archive twice ends in the same state. [expectedSha] is the digest from
     * [inspect]: a file that changed in between is refused before a single row moves.
     */
    suspend fun restore(open: () -> InputStream?, expectedSha: String?): BackupManifest {
        val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        var swapped = false
        var hadPhotos = true
        var setAside: File? = null
        try {
            val manifest = extractAndVerify(open, stage, expectedSha)
            if (manifest.schemaVersion > db.openHelper.readableDatabase.version) {
                throw RestoreError.NewerSchema(manifest.schemaVersion, db.openHelper.readableDatabase.version)
            }
            val (coffees, photoRows, sources) = readStagedDatabase(File(stage, DB_ENTRY))
            // The rows name directories and files the app will later read, delete and copy into
            // the Tier 0 snapshot; only ids and paths of the shape the app writes are accepted.
            checkRestoredRows(coffees, photoRows)
            // The Tier 0 snapshot still describes the library we are replacing. Drop it here: a
            // kill anywhere below must not leave a reinstall adopting the pre-restore rows.
            AutoBackupAdopter.discardSnapshot(context)
            val aside = File(photos.root.parentFile, "photos.old-${System.currentTimeMillis()}")
            // The marker goes down first and names both the set-aside tree and the library being
            // restored: a start that finds it can tell from the live rows whether they committed.
            RestoreJournal.begin(context, aside, RestoreJournal.fingerprint(coffees, photoRows))
            // The swap and the rows go in under the capture lock with a new generation, so a
            // hero crop that read the old library drops its result instead of writing it here.
            photos.captureLock.withLock {
                photos.newGeneration()
                swapped = true
                hadPhotos = photos.root.exists()
                setAside = aside
                swapPhotoRoot(File(stage, "photos"), aside)
                db.withTransaction {
                    val dao = db.coffeeDao()
                    dao.deleteAllCoffees()
                    dao.deleteAllSources()
                    dao.insertSources(sources)
                    dao.insertCoffees(coffees)
                    dao.insertPhotos(photoRows)
                }
            }
            // Rows and photos now agree: the old tree is no longer a recovery input.
            RestoreJournal.clear(context)
            setAside?.deleteRecursively()
            setAside = null
            swapped = false
            // The snapshot a reinstall would adopt is now the restored library, rebuilt here and
            // not by the caller: a restore that returns without one can be undone by a reinstall.
            // The restore itself has landed, though: a rebuild that fails (a full disk) leaves no
            // snapshot (the old one went above), the next refresh builds it, and the caller still
            // hears success and arms the restored reminders.
            try {
                snapshots.refresh(force = true)
            } catch (e: Exception) {
                Log.w(TAG, "restore landed, but its Tier 0 snapshot could not be rebuilt yet", e)
            }
            Log.i(TAG, "restored ${manifest.coffees} coffees from a backup of ${Date(manifest.createdAt)}")
            return manifest
        } catch (t: Throwable) {
            if (swapped) rollbackPhotoRoot(setAside, hadPhotos)
            throw t
        } finally {
            // Only the staging copy: the set-aside photo tree and the marker are recovery inputs.
            stage.deleteRecursively()
        }
    }

    // usableSpace, not getAllocatableBytes: this is a conservative guard with its own 32 MiB reserve,
    // and the allocatable figure counts cache the system would have to evict to hand us the room.
    @SuppressLint("UsableSpace")
    private fun extractAndVerify(open: () -> InputStream?, stage: File, expectedSha: String?): BackupManifest {
        val written = mutableMapOf<String, Pair<String, Long>>() // entry -> (sha256, size)
        var manifest: BackupManifest? = null
        val seen = mutableSetOf<String>()
        val whole = MessageDigest.getInstance("SHA-256")
        var entries = 0
        var expanded = 0L
        // Until the manifest is read, the disk is the only bound there is; filling it is the failure
        // being avoided. Once the manifest is read, the archive's own declaration is the tighter one.
        var budget = (stage.usableSpace - SPACE_RESERVE).coerceAtLeast(0L)
        DigestInputStream(open() ?: throw RestoreError.BadArchive("could not open"), whole).buffered().use { stream ->
            val zip = ZipInputStream(stream)
            generateSequence { zip.nextEntry }.forEach { e ->
                if (++entries > MAX_ENTRIES) throw RestoreError.BadArchive("more than $MAX_ENTRIES entries")
                if (e.isDirectory) throw RestoreError.UnsafePath(e.name) // we never write directory entries
                if (!seen.add(e.name)) throw RestoreError.BadArchive("two entries named ${e.name}")
                if (e.name == MANIFEST_ENTRY) {
                    val m = parseManifest(readBounded(zip, MAX_MANIFEST_BYTES))
                    val declared = m.files.sumOf { it.size }
                    // What is already staged is part of the declared total and already on disk.
                    val stillNeeded = (declared - expanded).coerceAtLeast(0L)
                    if (stillNeeded + SPACE_RESERVE > stage.usableSpace) throw RestoreError.NoSpace(stillNeeded, stage.usableSpace)
                    budget = minOf(budget, declared + declared / 10)
                    if (expanded > budget) throw RestoreError.BadArchive("the archive expands past what it declares")
                    manifest = m
                    return@forEach
                }
                if (!SAFE_ENTRY.matches(e.name)) throw RestoreError.UnsafePath(e.name)
                val dest = File(stage, e.name)
                dest.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                dest.outputStream().use { out ->
                    val sink = DigestOutputStream(out, digest)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        expanded += n
                        // Checked per chunk, not per entry: one declared-small entry must not be able
                        // to write gigabytes before anyone looks.
                        if (expanded > budget) throw RestoreError.BadArchive("the archive expands past what it declares")
                        sink.write(buf, 0, n)
                        size += n
                    }
                }
                written[e.name] = digest.digest().toHex() to size
            }
            drain(stream)
        }
        if (expectedSha != null && whole.digest().toHex() != expectedSha) throw RestoreError.Changed()
        val m = manifest ?: throw RestoreError.BadArchive("no manifest")
        checkAgainstManifest(m, written)
        return m
    }

    /** Every listed file arrived intact, and nothing arrived that the manifest does not list. */
    private fun checkAgainstManifest(m: BackupManifest, written: Map<String, Pair<String, Long>>) {
        if (m.format > BackupManifest.FORMAT) throw RestoreError.BadArchive("format ${m.format}")
        if (written[DB_ENTRY] == null) throw RestoreError.BadArchive("no database")
        for (f in m.files) {
            val got = written[f.path] ?: throw RestoreError.Corrupt("${f.path} (missing)")
            if (got.first != f.sha256 || got.second != f.size) throw RestoreError.Corrupt(f.path)
        }
        val listed = m.files.map { it.path }.toSet()
        written.keys.firstOrNull { it !in listed }?.let { throw RestoreError.UnsafePath(it) }
    }

    /** Open the staged copy through Room so an older schema is migrated exactly like a live one. */
    private suspend fun readStagedDatabase(file: File): Triple<List<CoffeeEntity>, List<CoffeePhotoEntity>, List<SourceEntity>> {
        val staged = Room.databaseBuilder(context, RoastbookDatabase::class.java, file.absolutePath)
            .addMigrations(*RoastbookDatabase.MIGRATIONS)
            .build()
        try {
            val dao = staged.coffeeDao()
            return Triple(dao.allCoffees(), dao.allPhotos(), dao.allSources())
        } finally {
            staged.close()
        }
    }

    /**
     * The staged photo tree becomes `files/photos`; the old one is set aside at [aside] (already named
     * in the journal) and kept until the rows commit.
     */
    private fun swapPhotoRoot(stagedPhotos: File, aside: File) {
        val root = photos.root
        if (root.exists()) check(root.renameTo(aside)) { "could not set the current photos aside" }
        if (stagedPhotos.exists()) {
            if (!stagedPhotos.renameTo(root)) { // cache and files may sit on different mounts: copy instead
                stagedPhotos.copyRecursively(root, overwrite = true)
            }
        } else root.mkdirs()
    }

    /**
     * The in-process half of what [RestoreJournal] does at the next start. The marker stays when the
     * photos could not be put back, so the next start tries again instead of forgetting them.
     */
    private fun rollbackPhotoRoot(setAside: File?, hadPhotos: Boolean) {
        val ok = runCatching {
            if (setAside != null && setAside.isDirectory) {
                photos.root.deleteRecursively()
                if (!setAside.renameTo(photos.root)) setAside.copyRecursively(photos.root, overwrite = true)
            } else if (!hadPhotos) {
                photos.root.deleteRecursively() // there were none before; drop a half-copied tree
            }
        }.onFailure { Log.w(TAG, "could not roll the photos back", it) }.isSuccess
        if (!ok) return
        // Marker first, then the copy's source (see RestoreJournal.recoverIfNeeded).
        RestoreJournal.clear(context)
        setAside?.deleteRecursively()
    }

    /** Ids and paths in restored rows must be the ones the app itself writes (see [PhotoStore.isPhotoPath]). */
    private fun checkRestoredRows(coffees: List<CoffeeEntity>, photoRows: List<CoffeePhotoEntity>) {
        coffees.firstOrNull { !UUID_ONLY.matches(it.id) }?.let { throw RestoreError.UnsafePath("coffee ${it.id}") }
        for (p in photoRows) {
            val base = "${PhotoStore.ROOT}/${p.coffeeId}/${p.id}"
            val ok = UUID_ONLY.matches(p.id) && UUID_ONLY.matches(p.coffeeId) &&
                p.original == "$base.jpg" && (p.square == null || p.square == "$base.square.jpg") &&
                (p.thumb == null || p.thumb == "$base.thumb.jpg")
            if (!ok) throw RestoreError.UnsafePath("photo ${p.id}")
        }
    }

    private companion object {
        const val TAG = "Backup"
        val UUID_ONLY = Regex(BackupManager.UUID_RE)
    }
}
