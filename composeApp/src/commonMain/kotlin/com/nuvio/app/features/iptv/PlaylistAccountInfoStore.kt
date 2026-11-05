package com.nuvio.app.features.iptv

import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** When a cached panel answer is still fresh enough not to ask again. */
internal object AccountInfoFreshnessPolicy {
    const val TTL_MS = 6L * 60 * 60 * 1000

    /** A failed check is remembered only briefly: long enough not to hammer a dead panel, short enough to heal. */
    const val FAILURE_TTL_MS = 45L * 1000

    fun isFresh(fetchedAtMs: Long, nowMs: Long, succeeded: Boolean = true): Boolean =
        nowMs - fetchedAtMs in 0 until (if (succeeded) TTL_MS else FAILURE_TTL_MS)
}

/**
 * What the panel last said about each playlist's account (status, expiry, connections), kept in memory so
 * the settings list can say "N days left" without asking every panel every time the screen opens. One
 * request per playlist per [AccountInfoFreshnessPolicy.TTL_MS] at most; never on a timer. A playlist with no
 * panel to ask (M3U) is never fetched.
 */
internal class PlaylistAccountInfoStore(
    private val fetch: suspend (XtreamAccount) -> XtreamAccountInfo? = { account ->
        IptvClient.forAccount(account).accountInfo(account)
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
    },
    private val clock: () -> Long = { TraktPlatformClock.nowEpochMs() },
) {
    /** [info] is the last SUCCESSFUL answer (kept across a failure); [ok] says whether the latest ask succeeded. */
    private data class Entry(val info: XtreamAccountInfo?, val atMs: Long, val ok: Boolean)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** Playlist key -> last known info, observed by the settings list. */
    val known: StateFlow<Map<String, XtreamAccountInfo>> get() = knownFlow.asStateFlow()
    private val knownFlow = MutableStateFlow<Map<String, XtreamAccountInfo>>(emptyMap())

    fun cached(key: String): XtreamAccountInfo? = entries.value[key]?.info

    /**
     * The panel's answer for [account], from the cache while it is fresh. Null when unreachable or there is no panel.
     * A failed answer is cached for [AccountInfoFreshnessPolicy.FAILURE_TTL_MS] only; a cancelled one is not cached at all.
     */
    suspend fun infoFor(account: XtreamAccount, force: Boolean = false, inspect: Boolean = false): XtreamAccountInfo? {
        if (account.sourceType.isM3u()) return null
        val cached = entries.value[account.id]
        if (!force && cached != null && AccountInfoFreshnessPolicy.isFresh(cached.atMs, clock(), cached.ok)) return cached.info
        // [inspect]: the user opened this playlist's details, so the one read-only lookup is allowed even for a
        // playlist turned off on this device (B171). The list's background lookups never pass it.
        val info = if (inspect) com.nuvio.app.core.sources.DeviceSources.inspecting { fetch(account) } else fetch(account)
        // A failure is remembered for seconds (not hours) and never erases what the panel last said.
        entries.update { it + (account.id to Entry(info ?: cached?.info, clock(), ok = info != null)) }
        if (info != null) knownFlow.update { it + (account.id to info) }
        return info ?: cached?.info
    }

    fun clear() {
        entries.value = emptyMap()
        knownFlow.value = emptyMap()
    }

    companion object {
        val shared = PlaylistAccountInfoStore()
    }
}
