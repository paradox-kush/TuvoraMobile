package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.overlay.OverlaySnapshot
import com.nuvio.app.features.iptv.stalker.StalkerCrypto

/**
 * UX15: the fingerprint of what IPTV search can return, so a shown search is refreshed exactly when
 * a playlist setting that changes its results changes. KMP twin of NuvioTV's
 * `XtreamIptvSearchProvider.signatureOf` (which has no hidden-items overlay in search to fold in).
 *
 * In: which playlists are enabled and what they point at, their content types, their per-type
 * category selections (null "all" differs from an empty "none"), and the channels / groups the
 * viewer hid. Out: display-only settings (name, EPG, catch-up, user agent, DNS) and every password,
 * so editing those never re-runs a search. Order-insensitive. The result is a SHA-256 digest, so even
 * the fields that go in (an M3U link can carry credentials in its query) never leave as text.
 */
internal object IptvSearchSourceSignature {

    fun of(accounts: List<XtreamAccount>, overlay: OverlaySnapshot): String? {
        val enabled = accounts.filter { it.availableOnDevice }
        if (enabled.isEmpty()) return null
        fun selection(list: List<String>?) = list?.sorted()?.joinToString(",", "[", "]") ?: "all"
        val playlists = enabled.map { acc ->
            listOf(
                acc.id, acc.sourceType, acc.baseUrl, acc.username, acc.macAddress, acc.fileName.orEmpty(),
                acc.contentTypes.sorted().joinToString(","),
                selection(acc.categorySelections.live),
                selection(acc.categorySelections.movies),
                selection(acc.categorySelections.series),
            ).joinToString(FIELD)
        }.sorted().joinToString(RECORD)
        val hiddenGroups = overlay.categories.filterValues { it.hidden }.keys.sorted().joinToString(",")
        val hiddenChannels = overlay.channels.filterValues { it.hidden }.keys.sorted().joinToString(",")
        return StalkerCrypto.sha256Hex(listOf(playlists, hiddenGroups, hiddenChannels).joinToString(PART))
    }

    private const val FIELD = "\u0001"
    private const val RECORD = "\u0002"
    private const val PART = "\u0003"
}
