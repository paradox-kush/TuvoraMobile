package com.nuvio.app.features.party

/** What one watch-party chat RPC (`watch_party_list_messages` / `watch_party_send_message`) answered. */
internal sealed interface PartyChatProbe {
    /** The RPC ran and returned a well-formed answer: the backend has chat. */
    data object Answered : PartyChatProbe

    /** PostgREST `PGRST202` / bare 404: the backend has no chat RPCs yet. */
    data object MissingFunction : PartyChatProbe

    /** Offline, timeout, 5xx, an unreadable body, an error answer: no verdict either way. */
    data object Inconclusive : PartyChatProbe
}

/**
 * Whether the chat tab may show. Upstream shows it unconditionally, so against a backend that has not
 * deployed the chat RPCs it would offer a box whose every message ends in "Not sent". The tab starts
 * hidden and is revealed by the first answered RPC; a missing function hides it again. The rest of the
 * party (sync, presence, control) never depends on this.
 */
internal object PartyChatAvailabilityPolicy {
    fun next(previous: Boolean, probe: PartyChatProbe): Boolean = when (probe) {
        PartyChatProbe.Answered -> true
        PartyChatProbe.MissingFunction -> false
        PartyChatProbe.Inconclusive -> previous
    }

    /** Classifies a thrown RPC failure with the same rules as the `watch_party_enabled` probe. */
    fun probeOfFailure(statusCode: Int?, message: String?): PartyChatProbe =
        when (PartyAvailabilityPolicy.probeOfFailure(statusCode, message)) {
            PartyEnabledProbe.MissingFunction -> PartyChatProbe.MissingFunction
            else -> PartyChatProbe.Inconclusive
        }
}
