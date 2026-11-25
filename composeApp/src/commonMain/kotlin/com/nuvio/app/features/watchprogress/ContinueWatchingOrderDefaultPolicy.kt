package com.nuvio.app.features.watchprogress

/**
 * Decides the default Continue Watching order (owner decision 2026-11-24): "Pick up where I left off" for
 * NEW installs only. Existing users keep their current order, and anyone who picked an order themselves
 * always keeps it. Pure; the caller supplies the facts.
 *
 * Deferred: the 10% holdout from SPEC 6 is not built here.
 */
object ContinueWatchingOrderDefaultPolicy {
    /**
     * @return `true` when resume-first should be recorded as the starting order, `null` to change nothing.
     * [installHasUserState] is only read when nothing else already settles the question (it touches storage).
     */
    fun initialResumeFirst(
        storedResumeFirst: Boolean?,
        hasStoredPayload: Boolean,
        installHasUserState: () -> Boolean,
    ): Boolean? = when {
        storedResumeFirst != null -> null // an explicit choice (or an earlier decision) always wins
        hasStoredPayload -> null // preferences already exist, so this is not a first run
        installHasUserState() -> null // add-ons, playlists or watch history: an existing user
        else -> true
    }
}
