package dev.roastbook.app.util

import android.net.Uri
import androidx.core.net.toUri

/** Links the journal opens (the roaster's site, a product page) are web pages and nothing else. */
object WebLinks {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * The web address a stored link names, or null when it is not an http(s) page with a host. A link
     * typed without a scheme ("larkmoor.example") is read as https. Anything else (tel:, market:,
     * intent:, a file) is never handed to the system, whether it was typed, suggested or restored.
     */
    fun uri(raw: String?): Uri? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val u = (if (SCHEME.containsMatchIn(t)) t else "https://$t").toUri()
        val scheme = u.scheme?.lowercase()
        return u.takeIf { (scheme == "http" || scheme == "https") && !it.host.isNullOrBlank() }
    }
}
