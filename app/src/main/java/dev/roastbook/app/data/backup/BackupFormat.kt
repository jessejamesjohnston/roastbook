package dev.roastbook.app.data.backup

import android.system.ErrnoException
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One file in the archive: its path inside the zip, lowercase-hex SHA-256 and size in bytes. */
@Serializable
data class ManifestFile(val path: String, val sha256: String, val size: Long)

/** Sits in every zip as `manifest.json`; the restore refuses anything it cannot verify against it. */
@Serializable
data class BackupManifest(
    val format: Int = FORMAT,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val coffees: Int,
    val photos: Int,
    val files: List<ManifestFile>,
) {
    companion object { const val FORMAT = 1 }
}

/** What a finished backup wrote: the zip's final name, size, contents and time. */
data class BackupSummary(val name: String, val bytes: Long, val coffees: Int, val photos: Int, val at: Long)

/**
 * What a look at an archive tells us: the manifest for the confirmation dialog, plus a digest of
 * every byte of the file. The restore recomputes the digest and refuses to go on if it differs, so
 * a file edited or re-synced between the dialog and the tap cannot land silently.
 */
data class BackupInspection(val manifest: BackupManifest, val sha256: String)

/** Marks a recorded backup error as the device or folder running out of space. */
const val NO_SPACE_ERROR = "no space: "

/** True when [t], or anything that caused it, is the device or folder running out of space. */
fun isOutOfSpace(t: Throwable): Boolean = generateSequence(t) { it.cause }.any { c ->
    (c is ErrnoException && c.errno == OsConstants.ENOSPC) ||
        c.message?.let { "ENOSPC" in it || "No space left" in it } == true
}

/** A backup that did not land. Nothing usable was left in the folder. */
open class BackupError(message: String) : Exception(message)

/** The folder's storage app would not give the file its final name; trying the same folder again won't help. */
class BackupFolderRefused(finalName: String) : BackupError("the backup folder refused to name the file $finalName")

/** Why a restore refused an archive. Each is raised before any row or photo on the phone changes. */
sealed class RestoreError(message: String) : Exception(message) {
    class BadArchive(why: String) : RestoreError("Not a Roastbook backup: $why")
    class NewerSchema(found: Int, supported: Int) :
        RestoreError("Backup is from a newer Roastbook (schema $found, this app reads up to $supported)")
    class Corrupt(path: String) : RestoreError("Backup is damaged: $path failed its checksum")
    class UnsafePath(path: String) : RestoreError("Backup contains an unexpected path: $path")
    class Changed : RestoreError("The file changed after it was picked")
    class NoSpace(need: Long, free: Long) : RestoreError("Not enough room to restore: needs $need bytes, $free free")
}

/** The JSON settings the manifest is written and read with. */
internal val manifestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Decodes a manifest, turning anything unreadable into [RestoreError.BadArchive]. */
internal fun parseManifest(bytes: ByteArray): BackupManifest =
    runCatching { manifestJson.decodeFromString(BackupManifest.serializer(), bytes.decodeToString()) }
        .getOrElse { throw RestoreError.BadArchive("unreadable manifest") }

/** At most [max] bytes of one entry; a manifest bigger than that is not one of ours. */
internal fun readBounded(input: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
        if (out.size() > max) throw RestoreError.BadArchive("the manifest is larger than $max bytes")
    }
    return out.toByteArray()
}

/** Reads [stream] to its end so every byte passes through any digest wrapped around it. */
internal fun drain(stream: InputStream) {
    val buf = ByteArray(8 * 1024)
    while (stream.read(buf) > 0) { /* bytes only have to pass through the digest */ }
}

/** Lowercase hex, as the manifest stores digests. */
internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/** Counts the bytes read from the archive file itself (before inflation). */
internal class CountingInputStream(inner: InputStream) : FilterInputStream(inner) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
