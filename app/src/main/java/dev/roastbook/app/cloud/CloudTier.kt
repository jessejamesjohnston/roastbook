package dev.roastbook.app.cloud

import android.graphics.Bitmap
import dev.roastbook.app.data.prefs.UserPrefs
import javax.inject.Inject
import javax.inject.Singleton

/** The bring-your-own-key extraction tier: only exists when the user pasted a Gemini key. */
interface CloudTier {
    suspend fun available(): Boolean
    suspend fun generate(images: List<Bitmap>, prompt: String): String
    suspend fun modelName(): String
}

/**
 * [CloudTier] over the Gemini API, using the stored key and the model named in Settings. A call in
 * flight is canceled as soon as the key is removed and surfaces as a failed call.
 */
@Singleton
class GeminiCloudTier @Inject constructor(
    private val client: GeminiClient,
    private val secrets: SecretStore,
    private val prefs: UserPrefs,
) : CloudTier {
    override suspend fun available(): Boolean = secrets.geminiKey() != null
    override suspend fun modelName(): String = prefs.current().geminiModel
    override suspend fun generate(images: List<Bitmap>, prompt: String): String {
        val key = secrets.geminiKey() ?: error("no key")
        val model = modelName()
        return secrets.cancelIfKeyRemoved { client.generate(key, model, images, prompt) }
    }
}
