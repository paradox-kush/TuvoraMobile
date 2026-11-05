package com.nuvio.app.features.livetv

/**
 * What the viewer's Retry does on the Live TV screen, decided without the player.
 *
 * A single-use (Stalker archive) replay spends its URL on the first open, so re-resolving "its source" - which is
 * that same URL - can only fail again; Retry must mint a fresh link. That holds after a proven replay and after a
 * failed resolve alike. A reusable replay re-resolves its own source; a live channel re-resolves the live one.
 */
internal object ReplayRetryPolicy {
    enum class Step { REOPEN_PENDING, MINT_FRESH, RE_RESOLVE_REPLAY, RE_RESOLVE_LIVE }

    data class Session(val singleUse: Boolean)

    fun stepFor(hasPendingReopen: Boolean, session: Session?): Step = when {
        hasPendingReopen -> Step.REOPEN_PENDING
        session == null -> Step.RE_RESOLVE_LIVE
        session.singleUse -> Step.MINT_FRESH
        else -> Step.RE_RESOLVE_REPLAY
    }
}
