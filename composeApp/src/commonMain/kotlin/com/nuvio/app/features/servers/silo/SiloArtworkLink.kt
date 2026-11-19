package com.nuvio.app.features.servers.silo

import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter

// Split from SiloArtwork.kt (fork, sync 16): the pure link marks have no Coil dependency, so Apple TV's tvosCore
// (which excludes every file importing Coil) can compile the mapper and provider that use them.

internal enum class SiloArtworkKind { POSTER, BACKGROUND, LOGO }

/** The title a Silo artwork link belongs to, carried in the link's fragment (never sent to the server). */
internal data class SiloArtworkMark(val connectionId: String, val contentId: String, val kind: SiloArtworkKind)

/**
 * Silo signs its artwork links for a few hours. Each link the app keeps (rows, Continue watching, Library) names its
 * title, so an expired one can be swapped for a fresh link to the same picture.
 */
internal object SiloArtworkLink {
    private const val MARK = "nuvio-silo="

    fun mark(url: String, connectionId: String, contentId: String, kind: SiloArtworkKind): String {
        if ('#' in url || contentId.isBlank()) return url
        return "$url#$MARK${encode(connectionId)}.${encode(contentId)}.${kind.name.lowercase()}"
    }

    fun parse(url: String): SiloArtworkMark? {
        val value = url.substringAfter('#', "").split('&').firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK)
            ?: return null
        val parts = value.split('.')
        if (parts.size != 3) return null
        val kind = SiloArtworkKind.entries.firstOrNull { it.name.equals(parts[2], ignoreCase = true) } ?: return null
        val connectionId = decode(parts[0])?.takeIf { it.isNotBlank() } ?: return null
        val contentId = decode(parts[1])?.takeIf { it.isNotBlank() } ?: return null
        return SiloArtworkMark(connectionId, contentId, kind)
    }

    /** Seconds since 1970 when Silo stops accepting [url]; null when the link carries no expiry of its own. */
    fun expiresAt(url: String): Long? =
        runCatching { Url(url.substringBefore('#')).parameters["exp"]?.toLongOrNull() }.getOrNull()

    fun withoutMark(url: String): String = url.substringBefore('#')

    private fun encode(value: String): String = value.encodeURLParameter().replace(".", "%2E")

    private fun decode(value: String): String? = runCatching { value.decodeURLQueryComponent() }.getOrNull()
}
