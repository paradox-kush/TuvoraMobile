package com.nuvio.app.features.player

/**
 * Which id the player may send to third-party subtitle add-ons (F17 + a privacy fix).
 *
 * IPTV items play under provider-scoped ids — `xtream:{account}:{kind}:{id}` — and the account part
 * is the raw playlist key: the server and username for Xtream, the full playlist URL (often with
 * `username=…&password=…`) for an M3U link, the MAC for Stalker. Any subtitle add-on that declares no
 * `idPrefixes` used to receive that id in its request URL. Those ids must never leave the app.
 *
 * Instead an IPTV movie or episode is looked up under its public IMDb id (`tt…`, `tt…:S:E` for an
 * episode — the Stremio convention OpenSubtitles answers), resolved through the IPTV feature's port.
 * No public id → no add-on request at all. Pure; unit-tested.
 */
object AddonSubtitleIdPolicy {
    private const val PROVIDER_PREFIX = "xtream:"
    private val IMDB_ID = Regex("^tt\\d{5,10}$")

    /** True for ids that embed provider identity and must not reach an add-on. */
    fun isProviderScoped(id: String?): Boolean =
        id?.trim()?.startsWith(PROVIDER_PREFIX) == true ||
            // Any other own source (a media server: `ms:` ids embed the server and user ids) is provider-scoped too.
            com.nuvio.app.core.contracts.OwnSourcePolicy.isOwnContentId(id?.trim())

    /**
     * Whether the stream behind [activeVideoId] may be fetched just to compute an OpenSubtitles hash.
     * The hash reads the first and last bytes of the stream URL; an IPTV or media-server URL can be a
     * single-use link, so probing it can burn the link the player is about to use.
     */
    fun allowsStreamHashProbe(activeVideoId: String?): Boolean = !isProviderScoped(activeVideoId)

    /** The Stremio-style public id for an IPTV item, or null when it cannot be formed. */
    fun publicVideoId(imdbId: String?, isSeries: Boolean, season: Int?, episode: Int?): String? {
        val imdb = imdbId?.trim()?.lowercase()?.takeIf { IMDB_ID.matches(it) } ?: return null
        if (!isSeries) return imdb
        val s = season?.takeIf { it >= 0 } ?: return null
        val e = episode?.takeIf { it > 0 } ?: return null
        return "$imdb:$s:$e"
    }

    /**
     * The id to use for an add-on subtitle request: the item's own id for ordinary content, the
     * resolved public id for IPTV content, and null (no request) when an IPTV item has none.
     */
    fun requestVideoId(activeVideoId: String?, resolvedPublicId: String?): String? {
        val id = activeVideoId?.takeIf { it.isNotBlank() } ?: return null
        if (!isProviderScoped(id)) return id
        return resolvedPublicId?.takeUnless { isProviderScoped(it) }
    }

    /** Add-on subtitle type for a public id: an episode id is always "series". */
    fun requestType(contentType: String, publicId: String): String =
        if (publicId.count { it == ':' } >= 2) "series" else contentType
}
