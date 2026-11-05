package com.nuvio.app.features.livetv.pause

/**
 * THE single capped reconnect rule for one live capture. Replaces the two divergent loops of the reference
 * (an uncapped HLS refetch, and a TS loop that retried every 2-8 s forever): both ended up a frozen picture
 * nobody was told about.
 *
 * A single-use link (Stalker create_link and friends) is NEVER re-opened: every [Decision.Retry] for it asks for
 * a freshly minted link, and so does the first capture open ([mintOnFirstOpen]) because the player just spent
 * the URL it was given. Confined to one coroutine, like the capture that owns it.
 */
class LiveCaptureReconnect(
    val singleUseLink: Boolean,
    val maxAttemptsWithoutProgress: Int = 3,
) {
    sealed interface Decision {
        data class Retry(val delayMs: Long, val mintFreshLink: Boolean) : Decision
        data object GiveUp : Decision
    }

    var attempts: Int = 0
        private set

    /** The first capture open of a single-use link must mint too: the player already consumed its URL. */
    val mintOnFirstOpen: Boolean get() = singleUseLink

    /** The connection dropped. Attempt n (1-based) waits 2 s * 2^(n-1), capped at 8 s; past the maximum: give up. */
    fun onDrop(): Decision {
        if (attempts >= maxAttemptsWithoutProgress) return Decision.GiveUp
        attempts++
        val shift = (attempts - 1).coerceAtMost(2)
        return Decision.Retry(delayMs = (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS), mintFreshLink = singleUseLink)
    }

    /** A piece or segment landed: the provider is serving, so the counter starts over. */
    fun onProgress() {
        attempts = 0
        mintsLeft = MAX_MINTS
    }

    /**
     * The viewer pressed Retry after this channel's capture gave up: they get ONE more reconnect, not a fresh
     * [maxAttemptsWithoutProgress], so repeated Retry cannot burn through single-use links.
     */
    fun onRetry() {
        attempts = minOf(attempts, maxAttemptsWithoutProgress - 1)
    }

    /** Fresh single-use links this channel run may still ask the provider for (first opens AND reconnects). */
    var mintsLeft: Int = MAX_MINTS
        private set

    /**
     * Spends one fresh link. Every mint of a single-use link goes through here, so neither reconnects nor Retry
     * presses can make the provider hand out links without end. Reusable links cost nothing.
     */
    fun tryMint(): Boolean {
        if (!singleUseLink) return true
        if (mintsLeft <= 0) return false
        mintsLeft--
        return true
    }

    /** Whether a Retry can still open anything: false once a single-use run spent its links. */
    val canRetry: Boolean get() = !singleUseLink || mintsLeft > 0

    companion object {
        const val MAX_MINTS = 4
        private const val BASE_DELAY_MS = 2_000L
        private const val MAX_DELAY_MS = 8_000L
    }
}
