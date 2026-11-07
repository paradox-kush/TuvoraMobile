package com.nuvio.app.core.telemetry.api

/*
 * TEMPORARY, Apple TV lane only. The real contract lives in composeApp `core/telemetry/api`, built by the
 * "Telemetry v2: Mobile shared" lane (research/telemetry-flight-recorder-design-2026-11-07.md, "Contract shared
 * by every lane"). This file mirrors it so tvosCore compiles before that branch lands.
 *
 * ON MERGING feat/telemetry-v2-mobile: DELETE THIS FILE (same package and names, so the duplicate-class
 * compile error is the reminder), then delete InterimTvTelemetryPort and point TvTelemetry.start() at the
 * real port. ReportContext is not spelled out in the contract; adapt TvTelemetry.sendReport to the real shape.
 */

interface TelemetryPort {
    val supportCode: String
    fun record(entry: DiagEntry)
    fun reportError(event: String, props: Map<String, Any>)
    suspend fun sendUserReport(category: ReportCategory, surface: ReportSurface, context: ReportContext): ReportResult
}

data class DiagEntry(val atMs: Long, val area: DiagArea, val code: String, val fields: Map<String, Any>)

enum class DiagArea { PLAYBACK, IPTV_LOAD, EPG, SCREEN, MEMORY, LIFECYCLE }

enum class ReportCategory { WONT_PLAY, BLACK_SCREEN, FREEZES, APP_CLOSED, OTHER }

enum class ReportSurface { LIVE, VOD, SETTINGS }

/** Stand-in: what the player knows when the viewer presses Send report. Enum/number values only. */
data class ReportContext(val engine: String? = null, val failureCode: String? = null)

sealed class ReportResult {
    data class Sent(val supportCode: String) : ReportResult()
    data object ConsentOff : ReportResult()
    data class RateLimited(val retryInSec: Int) : ReportResult()
    data object Failed : ReportResult()
}
