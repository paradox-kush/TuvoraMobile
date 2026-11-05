package com.tuvora.tvos.screens

/**
 * A continuing replay's seek bar spans the PROGRAMME on screen, not the whole window the link asked for.
 * The player counts from where the link starts, so the bar's origin is the programme's start in that
 * timeline: `programme start - link start` (the phone's CatchUpSession.programmeOffsetMs; negative once a
 * restarted link begins part-way into the programme). Pure, so Swift only draws what this answers.
 */
object TvReplayBar {
    /** Where the programme starts in the player's own timeline. */
    fun originMs(programmeStartMs: Long, linkStartMs: Long): Long = programmeStartMs - linkStartMs

    /** The bar's position for the player's raw position. */
    fun position(rawMs: Long, originMs: Long, spanMs: Long): Long = (rawMs - originMs).coerceIn(0L, spanMs)

    /** The player position a bar position means (what a seek is sent as). */
    fun raw(barMs: Long, originMs: Long): Long = (barMs + originMs).coerceAtLeast(0L)

    /**
     * The furthest bar position a seek may ask for. A finished programme is wholly recorded; one still
     * airing (a Start over) only exists up to now minus the shared live-edge guard, so the bar's seekable
     * end stops short of live ([com.nuvio.app.features.iptv.CatchUpPlayback.maxSeekPositionMs], the phone's rule).
     */
    fun maxSeekMs(programmeStartMs: Long, programmeEndMs: Long, nowMs: Long): Long =
        com.nuvio.app.features.iptv.CatchUpPlayback.maxSeekPositionMs(programmeStartMs, programmeEndMs, nowMs)

    /** A bar position held to what has aired. */
    fun clampSeek(barMs: Long, programmeStartMs: Long, programmeEndMs: Long, nowMs: Long): Long =
        barMs.coerceIn(0L, maxSeekMs(programmeStartMs, programmeEndMs, nowMs))
}
