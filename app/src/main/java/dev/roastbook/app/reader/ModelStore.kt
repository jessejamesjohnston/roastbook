package dev.roastbook.app.reader

import java.io.File

/**
 * Where the reader's model lives: `no_backup/reader/`, the app-private directory Android never
 * backs up. Neither backup tier can reach it either: Tier 0 includes only `files/autobackup/` and
 * `files/datastore/`, and the Tier 1 zip carries only the database and `files/photos/`.
 *
 * The finished file is the only "ready" signal. A download writes `<name>.part`; only a file whose
 * size and SHA-256 matched is renamed to its final name, and a `.verified` stamp records the digest
 * it matched so a later check does not re-hash 2.6 GB.
 */
class ModelStore(val root: File, private val spec: ModelSpec) {
    val model: File get() = File(root, spec.fileName)
    val part: File get() = File(root, spec.fileName + ".part")
    private val stamp: File get() = File(root, spec.fileName + ".verified")
    /** LiteRT-LM's compiled-program cache; lives here so Delete and the size on disk include it. */
    val cacheDir: File get() = File(root, "cache")

    fun ensureDirs() { root.mkdirs(); cacheDir.mkdirs() }

    fun isReady(): Boolean =
        model.isFile && model.length() == spec.sizeBytes && runCatching { stamp.readText().trim() }.getOrNull() == spec.sha256

    fun partialBytes(): Long = if (part.isFile) part.length() else 0L

    /**
     * Called by the downloader after the digest matched: stamp, then rename. In this order a kill
     * between the two leaves the verified part in place (the next download re-checks it without a
     * request); the other order stranded a full-size model with no stamp, which was neither ready
     * nor resumable and needed room for a second copy.
     */
    fun promote(verifiedPart: File, sha256: String) {
        ensureDirs()
        stamp.writeText(sha256)
        model.delete()
        if (!verifiedPart.renameTo(model)) error("could not move the verified model into place")
    }

    /**
     * Everything here other than the model, its stamp and a partial download: what the runtime
     * writes when it loads the model (its cache, measured at about 1 GB beside the 2.6 GB model),
     * wherever in this folder it puts it. Counted in [sizeOnDisk] and removed by [deleteAll].
     */
    fun cacheBytes(): Long {
        if (!root.exists()) return 0L
        val own = setOf(model, part, stamp).map { it.absoluteFile }
        return root.walkBottomUp().filter { it.isFile && it.absoluteFile !in own }.sumOf { it.length() }
    }

    /** Everything the reader keeps on disk: the model, a partial download, the runtime cache. */
    fun sizeOnDisk(): Long = if (!root.exists()) 0L else root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() { root.deleteRecursively() }
}
