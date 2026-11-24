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

    /** The first visit ended (app left the foreground) before anything played. Fired by the service, never by a call site. */
    FIRST_LEAVE("first_leave"),
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

    /**
     * Closed vocabulary of `save_error`: the lower-cased names of the playlist-save error enum, `other` for a failure
     * outside it, `none` when no save failed (a test pins this to the enum so the two cannot drift).
     */
    const val SAVE_ERROR_OTHER = "other"
    const val SAVE_ERROR_NONE = "none"
    val saveErrors: Set<String> = setOf(
        "missing_xtream_fields", "missing_m3u_url", "missing_stalker_fields", "missing_m3u_file", "invalid_address",
        "unreachable", "wrong_credentials", "provider_blocked", "secure_connection_failed", "source_disabled_on_device",
        "source_disabled_for_account", "signing_in", "file_unreadable", SAVE_ERROR_OTHER,
    )

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

/**
 * Facts about the first visit that [FirstRunStep.FIRST_LEAVE] summarises. Closed shapes only: a screen identifier
 * from the app's own route names, never anything the person typed.
 */
sealed interface FirstRunNote {
    /** A screen was shown ([name] = the `Breadcrumbs.screenChanged` identifier; sanitised by the policy). */
    data class Screen(val name: String) : FirstRunNote

    /** An add-source form (Xtream / M3U / Stalker / add-on install / media server) was opened. */
    data object SourceFormOpened : FirstRunNote

    /** The person pressed save/add on such a form. */
    data object SaveTried : FirstRunNote

    /** A save failed; [kind] is the playlist-save error name lower-cased (anything outside the vocabulary becomes `other`). */
    data class SaveFailed(val kind: String) : FirstRunNote
}

fun TelemetryPort.funnelScreen(name: String) = firstRunNote(FirstRunNote.Screen(name))
fun TelemetryPort.funnelSourceFormOpened() = firstRunNote(FirstRunNote.SourceFormOpened)
fun TelemetryPort.funnelSaveTried() = firstRunNote(FirstRunNote.SaveTried)
fun TelemetryPort.funnelSaveFailed(kind: String) = firstRunNote(FirstRunNote.SaveFailed(kind))
