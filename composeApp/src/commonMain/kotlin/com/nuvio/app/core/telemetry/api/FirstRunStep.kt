package com.nuvio.app.core.telemetry.api

/**
 * The one-time first-run funnel steps (`first_run_step` event, contract research/first-run-funnel-2026-11/CONTRACT.md).
 * Each fires at most once per install; the once-gate, the new-vs-existing decision and the consent rule live in the
 * telemetry service, so a call site is one line and cannot double-send.
 */
enum class FirstRunStep(val wire: String) {
    FIRST_LAUNCH("first_launch"),
    SOURCE_ADDED("source_added"),
    FIRST_CONTENT_LOADED("first_content_loaded"),
    FIRST_PLAYBACK("first_playback"),
}

/** Closed vocabularies for `source_kind` and `via`. Anything else is sent as `unknown`. */
object FirstRunVocab {
    const val KIND_M3U = "m3u"
    const val KIND_XTREAM = "xtream"
    const val KIND_STALKER = "stalker"
    const val KIND_ADDON = "addon"
    const val KIND_MEDIA_SERVER = "media_server"
    const val KIND_UNKNOWN = "unknown"
    val sourceKinds: Set<String> = setOf(KIND_M3U, KIND_XTREAM, KIND_STALKER, KIND_ADDON, KIND_MEDIA_SERVER, KIND_UNKNOWN)

    const val VIA_MANUAL = "manual"
    const val VIA_SETUP_CODE = "setup_code"
    const val VIA_SYNC = "sync"
    const val VIA_UNKNOWN = "unknown"
    val vias: Set<String> = setOf(VIA_MANUAL, VIA_SETUP_CODE, VIA_SYNC, VIA_UNKNOWN)

    /** Maps a playlist `sourceType` (`xtream`, `m3u_url`, `m3u_file`, `stalker`) to a [sourceKinds] value. */
    fun kindOfPlaylist(sourceType: String): String = when (sourceType) {
        "xtream" -> KIND_XTREAM
        "m3u_url", "m3u_file" -> KIND_M3U
        "stalker" -> KIND_STALKER
        else -> KIND_UNKNOWN
    }
}

/** An IPTV playlist, add-on or media server now exists on this install. */
fun TelemetryPort.firstSourceAdded(kind: String, via: String) =
    firstRunStep(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to kind, "via" to via))

/** An IPTV load ended `loaded` on [surface] (the `iptv_load` surface key). */
fun TelemetryPort.firstContentLoaded(surface: String) =
    firstRunStep(FirstRunStep.FIRST_CONTENT_LOADED, mapOf("surface" to surface))

/** Playback produced output (the `playback_started` chokepoint). */
fun TelemetryPort.firstPlayback(kind: String, engine: String) =
    firstRunStep(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to kind, "engine" to engine))
