package com.nuvio.app.features.sportsdetail.internal

import kotlinx.serialization.json.JsonElement
import com.nuvio.app.features.sportsdetail.api.GameDetail

/** Outcome of asking one source for one payload. A failure is never an [Empty]. */
internal sealed interface SourceResult<out T> {
    data class Ok<T>(val value: T) : SourceResult<T>
    /** The source answered: there is no such thing (404, empty list). Safe to remember. */
    data object Empty : SourceResult<Nothing>
    /** Transport/HTTP/parse failure or deadline. NOT safe to remember as "no data". */
    data class Failed(val timedOut: Boolean) : SourceResult<Nothing>
    /** The backend kill switch is on for this source: behave as if the source were not configured. */
    data object Disabled : SourceResult<Nothing>
}

internal data class HttpReply(val status: Int, val body: String?, val etag: String?)

/** Minimal GET port over the app's HTTP client stack. May throw on transport errors; adapters catch. */
internal interface SportsHttp {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), deadlineMs: Long): HttpReply
}

/** ESPN, called from the device only. */
internal interface EspnSource {
    suspend fun scoreboard(espnPath: String, yyyymmdd: String): SourceResult<JsonElement>
    suspend fun summary(espnPath: String, eventId: String): SourceResult<JsonElement>
    /** The single-event scoreboard (`sources.espn.event`): the event object itself, same shape as an entry of the whole-day scoreboard. */
    suspend fun event(espnPath: String, eventId: String): SourceResult<JsonElement> = SourceResult.Disabled
}

/**
 * TheSportsDB through our backend's `game-detail` function, which already returns the NORMALISED [GameDetail]
 * (header + the TheSportsDB-sourced blocks). [blocks] limits the work to named block types (a fast poll asks
 * for stats,timeline only); null = all the sport's blocks.
 */
internal interface TsdbSource {
    suspend fun detail(eventId: String, leagueId: String?, blocks: Set<String>?): SourceResult<GameDetail>
}

/**
 * Backend function URLs, derived from ONE base (the Supabase project URL) so there is a single place to change.
 * `GET {base}/functions/v1/game-detail?event_id=&league_id=&blocks=` and `GET {base}/functions/v1/sports-mapping`.
 */
internal class SportsDetailEndpoints(functionsBase: String) {
    val gameDetailUrl: String = "${functionsBase.trimEnd('/')}/$GAME_DETAIL_FUNCTION"
    val mappingUrl: String = "${functionsBase.trimEnd('/')}/$MAPPING_FUNCTION"
    val marketWinChanceUrl: String = "${functionsBase.trimEnd('/')}/$MARKET_FUNCTION"

    companion object {
        const val GAME_DETAIL_FUNCTION = "game-detail"
        const val MAPPING_FUNCTION = "sports-mapping"
        const val MARKET_FUNCTION = "market-winchance"

        fun fromSupabaseUrl(supabaseUrl: String) = SportsDetailEndpoints(supabaseUrl.trim().trimEnd('/') + "/functions/v1")
    }
}
