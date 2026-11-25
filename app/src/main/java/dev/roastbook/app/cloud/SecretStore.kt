package dev.roastbook.app.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What is kept of a pasted key: printable ASCII without spaces. Gemini keys are letters, digits,
 * '-' and '_'; anything else in a paste (spaces, a zero-width space, a line break) is not part of it.
 */
internal fun cleanApiKey(raw: String?): String? = raw?.filter { it in '!'..'~' }?.takeIf { it.isNotEmpty() }

/**
 * Replaces [file]'s content in one step: the new text goes to a temp file beside it, then a rename
 * swaps it in. A process killed mid-save leaves the old content or the new, never a cut-off file.
 */
internal fun writeReplacing(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("could not replace ${file.name}") }
}

/** The user's Gemini API key: on this phone only, never logged, never in any backup. */
interface SecretStore {
    val hasGeminiKey: StateFlow<Boolean>
    suspend fun geminiKey(): String?
    suspend fun setGeminiKey(key: String?)
}

/**
 * AES-GCM under an Android Keystore key (hardware-backed where the device has it); the
 * ciphertext sits in `files/secrets/`, which neither backup tier includes (Tier 0 is an include
 * list of `autobackup/` and `datastore/`; Tier 1 zips only the database copy and photos). The
 * Keystore key itself cannot leave the device, so even a copied ciphertext is useless elsewhere.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(@ApplicationContext private val context: Context) : SecretStore {
    private val file: File get() = File(File(context.filesDir, "secrets").apply { mkdirs() }, "gemini.bin")
    private val _has = MutableStateFlow(file.exists())
    override val hasGeminiKey: StateFlow<Boolean> = _has

    /**
     * One reader or writer at a time, so a read never races a save or a removal. Saves also replace
     * the file in one step ([writeReplacing]): a cut-off blob would fail decryption and be deleted as
     * unreadable, taking the key with it.
     */
    private val lock = Mutex()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    override suspend fun geminiKey(): String? = withContext(Dispatchers.IO) { lock.withLock { readKey() } }

    private fun readKey(): String? {
        if (!file.exists()) return null
        return runCatching {
            val blob = Base64.decode(file.readText(), Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse { e ->
            if (e !is javax.crypto.BadPaddingException && e !is IllegalArgumentException) return null
            // The Keystore key that sealed this file is gone (a data transfer that copied files, a
            // Keystore reset): the ciphertext can never be read again. Forget it, so Settings asks
            // for the key instead of showing a saved key that does nothing.
            file.delete()
            _has.value = false
            null
        }
    }

    override suspend fun setGeminiKey(key: String?) = withContext(Dispatchers.IO) { lock.withLock { writeKey(key) } }

    private fun writeKey(key: String?) {
        val trimmed = cleanApiKey(key)
        if (trimmed == null) {
            file.delete()
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val ct = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            writeReplacing(file, Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP))
        }
        _has.value = file.exists()
    }

    private companion object { const val ALIAS = "roastbook-gemini-key" }
}

/** The key was removed while a call made with it was still running; the call was canceled. */
class KeyRemovedException : java.io.IOException("the key was removed during the call")

/**
 * Runs [block], a call made with the stored key, and cancels it (and its HTTP request) if the key
 * is removed meanwhile: removing the key stops cloud work at once, not when the answer arrives.
 * That ends in [KeyRemovedException] rather than a cancellation, so the caller treats it as a
 * failed call and carries on (a read falls through to the label text) instead of stopping as if
 * it had been canceled itself.
 */
suspend fun <T> SecretStore.cancelIfKeyRemoved(block: suspend () -> T): T = coroutineScope {
    val removed = AtomicBoolean(false)
    val work = async { block() }
    val watch = launch { hasGeminiKey.first { !it }; removed.set(true); work.cancel() }
    try {
        work.await()
    } catch (e: CancellationException) {
        if (removed.get()) throw KeyRemovedException() else throw e
    } finally {
        watch.cancel()
    }
}
