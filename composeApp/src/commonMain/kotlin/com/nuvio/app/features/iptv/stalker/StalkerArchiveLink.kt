package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.LoadOutcome

/**
 * The wire shape of a Stalker/Ministra `create_link`, and of the archive (catch-up) one in particular.
 *
 * Replaying a past programme is `type=tv_archive` with a cmd built from the portal's own EPG programme id -
 * `auto /media/<id>.mpg` (Ministra c/tv_archive.js) - and the server derives the time range from it. There is
 * no `utc`/`lutc` form on Stalker. Pure, so the request and the documented refusals test without a portal.
 * Port of NuvioTV's StalkerArchiveLink.
 */
object StalkerArchiveLink {

    fun cmd(programmeId: String): String = "auto /media/$programmeId.mpg"

    /** Params every `create_link` carries; the session adds `JsHttpRequest` itself. */
    fun createLinkParams(type: String, cmd: String, extra: Map<String, String>): Map<String, String> = buildMap {
        put("type", type)
        put("action", "create_link")
        put("cmd", cmd)
        put("forced_storage", "undefined")
        put("disable_ad", "0")
        putAll(extra)
    }

    /** The whole archive request for one programme. */
    fun params(programmeId: String): Map<String, String> =
        createLinkParams("tv_archive", cmd(programmeId), ARCHIVE_EXTRA)

    /** Extra params of an archive request: a stream link, not a download. */
    val ARCHIVE_EXTRA: Map<String, String> = mapOf("download" to "0")

    /** The `create_link` answers worth naming to the viewer. */
    enum class LinkError { NONE, SESSION_LIMIT, LINK_FAULT, NOTHING_TO_PLAY }

    /**
     * Reads a raw Stalker response for the three documented refusals. Matched on the FIELD, not on the word
     * (a channel called "No Limits TV" must not report a session cap); both quoting styles are accepted.
     */
    fun linkErrorOf(raw: String?): LinkError {
        if (raw.isNullOrBlank()) return LinkError.NONE
        if (ERROR_LIMIT.containsMatchIn(raw)) return LinkError.SESSION_LIMIT
        if (LINK_FAULT.containsMatchIn(raw)) return LinkError.LINK_FAULT
        if (NOTHING_TO_PLAY.containsMatchIn(raw)) return LinkError.NOTHING_TO_PLAY
        return LinkError.NONE
    }

    private val ERROR_LIMIT = Regex("""["']?error["']?\s*:\s*["']limit["']""", RegexOption.IGNORE_CASE)
    private val NOTHING_TO_PLAY = Regex("""["']?error["']?\s*:\s*["']nothing_to_play["']""", RegexOption.IGNORE_CASE)
    private val LINK_FAULT = Regex("""["']?(cmd|error)["']?\s*:\s*["']link_fault["']""", RegexOption.IGNORE_CASE)
}

/** What one `create_link` came back with: the playable [url], or why there is none. */
data class StalkerArchiveAnswer(val url: String?, val error: StalkerArchiveLink.LinkError)

/** Why a Stalker replay did not play. Every one is shown with Retry; Retry is always a FRESH create_link. */
enum class StalkerReplayFailure {
    /** The portal's session cap: another device is streaming. */
    SESSION_LIMIT,

    /** The portal says it has no recording of this programme. */
    NOTHING_TO_PLAY,

    /** The portal could not build a playback link. */
    LINK_FAULT,

    /** The create_link request itself failed or hung: no link was ever handed out. */
    NO_ANSWER,

    /** A link was minted and opened, but no frame arrived within the startup deadline. */
    STARTUP_HANG,

    /** A link was minted and the engine reported an error playing it. */
    PLAYBACK_ERROR,
}

/**
 * How asking a Stalker portal for a replay link ends. The decision is here, not in the screen: the three
 * documented refusals, a failed or hung request and a link that played are told apart, and "the portal has no
 * programme id for it" is the only case that reads as "no recording" without ever touching the portal.
 */
sealed interface StalkerReplayOutcome {
    /** A single-use link: open it once, never probe it, never re-open it. */
    data class Link(val url: String) : StalkerReplayOutcome

    data class Refused(val failure: StalkerReplayFailure) : StalkerReplayOutcome

    /** No programme id (a guide that predates it, or a programme the portal never listed). */
    data object NoRecording : StalkerReplayOutcome

    companion object {
        fun ofAnswer(answer: StalkerArchiveAnswer): StalkerReplayOutcome {
            answer.url?.takeIf { it.isNotBlank() }?.let { return Link(it) }
            return Refused(
                when (answer.error) {
                    StalkerArchiveLink.LinkError.SESSION_LIMIT -> StalkerReplayFailure.SESSION_LIMIT
                    StalkerArchiveLink.LinkError.NOTHING_TO_PLAY -> StalkerReplayFailure.NOTHING_TO_PLAY
                    // No url and no named refusal: the portal answered but built nothing - a link fault, not "gone".
                    StalkerArchiveLink.LinkError.LINK_FAULT, StalkerArchiveLink.LinkError.NONE -> StalkerReplayFailure.LINK_FAULT
                },
            )
        }

        /**
         * How a minted link that did not play ends: the startup deadline passing with no frame is a hang
         * ([StalkerReplayFailure.STARTUP_HANG]); an engine error is [StalkerReplayFailure.PLAYBACK_ERROR].
         * Either way the link is spent and the viewer's Retry mints a fresh one.
         */
        fun ofSession(startupTimedOut: Boolean): StalkerReplayFailure =
            if (startupTimedOut) StalkerReplayFailure.STARTUP_HANG else StalkerReplayFailure.PLAYBACK_ERROR

        /** [LoadOutcome] of the bounded create_link: null value = no programme id. A failure or a hang is never "empty". */
        fun ofLoad(outcome: LoadOutcome<StalkerArchiveAnswer?>): StalkerReplayOutcome = when (outcome) {
            is LoadOutcome.Loaded -> outcome.value?.let(::ofAnswer) ?: NoRecording
            is LoadOutcome.Empty -> NoRecording
            is LoadOutcome.Failed -> Refused(StalkerReplayFailure.NO_ANSWER)
        }
    }
}
