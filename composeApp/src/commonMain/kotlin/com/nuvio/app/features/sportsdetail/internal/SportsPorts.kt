package com.nuvio.app.features.sportsdetail.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Outcome of asking one source for one payload. A failure is never an [Empty]. */
internal sealed interface SourceResult<out T> {
    data class Ok<T>(val value: T) : SourceResult<T>
    /** The source answered: there is no such thing (404, empty list). Safe to remember. */
    data object Empty : SourceResult<Nothing>
    /** Transport/HTTP/parse failure or deadline. NOT safe to remember as "no data". */
    data class Failed(val timedOut: Boolean) : SourceResult<Nothing>
}

internal data class HttpReply(val status: Int, val body: String?, val etag: String?)

/** Minimal GET port over the app's HTTP client stack. May throw on transport errors; adapters catch. */
internal interface SportsHttp {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), deadlineMs: Long): HttpReply
}

internal enum class TsdbLookup(val path: String) {
    TV("event_tv"), STATS("event_stats"), TIMELINE("event_timeline"), HIGHLIGHTS("event_highlights"),
}

/** ESPN, called from the device only. */
internal interface EspnSource {
    suspend fun scoreboard(espnPath: String, yyyymmdd: String): SourceResult<JsonElement>
    suspend fun summary(espnPath: String, eventId: String): SourceResult<JsonElement>
}

/** TheSportsDB through our backend (cached there). Rows come back as `{"lookup":[...]}`. */
internal interface TsdbSource {
    suspend fun event(idEvent: String): SourceResult<JsonObject>
    suspend fun lookup(idEvent: String, kind: TsdbLookup): SourceResult<JsonObject>
}

/** Single-sourced endpoint constants. The backend lane (`gd-backend`) owns the server side of these. */
internal object SportsDetailEndpoints {
    /** Remote mapping (ETag-polled). TODO(gd-backend): confirm the final path when the endpoint lands. */
    const val MAPPING_URL: String = "https://api.tuvora.co/sports/mapping/v1.json"

    /** TheSportsDB v2-shaped passthrough/cache on our backend: `{base}/lookup/event/{id}`, `/lookup/event_tv/{id}`, ... */
    const val TSDB_BASE: String = "https://api.tuvora.co/sports/tsdb"
}
