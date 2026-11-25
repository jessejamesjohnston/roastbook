package dev.roastbook.app.reader

/**
 * One downloadable model file, pinned: the URL names an exact repository commit and the file must
 * match [sizeBytes] and [sha256] before it is ever loaded.
 */
data class ModelSpec(
    val displayName: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    companion object {
        /**
         * Gemma 4 E2B (instruction-tuned), LiteRT-LM format, from the litert-community repository on
         * Hugging Face (Apache-2.0). Revision pinned to commit b3ca0d2f (2026-08-31); size and SHA-256
         * are the file's Git LFS object, from the Hugging Face tree API.
         */
        val GEMMA_4_E2B = ModelSpec(
            displayName = "Gemma 4 E2B",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        )
    }
}
