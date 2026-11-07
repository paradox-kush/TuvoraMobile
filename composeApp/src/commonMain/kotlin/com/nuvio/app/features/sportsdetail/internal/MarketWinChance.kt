package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.WinPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What the backend needs to find the market for a game. [startsAtIso] is UTC. */
internal data class MarketRequest(val gameId: String, val leagueId: String, val home: String, val away: String, val startsAtIso: String)

/** Win chance per side (whole percent), no odds or prices. [source] "polymarket"; [attribution] "via Polymarket". */
internal data class MarketWinChance(
    val home: Int,
    val away: Int,
    val draw: Int?,
    val asOfMs: Long?,
    val source: String,
    val attribution: String?,
    val history: List<WinPoint>,
)

/** Port: the Polymarket win chance for a game, served by OUR backend (devices never call Polymarket). */
internal interface MarketWinChanceSource {
    suspend fun winChance(req: MarketRequest, history: Boolean): SourceResult<MarketWinChance>
}

/** `GET {url}?game_id=&league_id=&home=&away=&starts_at=[&history=1]` -> `{home, away, draw?, asOf, source, attribution, history?}` | `{disabled:true}` | 404. */
internal class MarketWinChanceHttpSource(
    private val http: SportsHttp,
    private val url: String,
    private val deadlineMs: Long = 8_000L,
) : MarketWinChanceSource {
    override suspend fun winChance(req: MarketRequest, history: Boolean): SourceResult<MarketWinChance> {
        val query = buildString {
            append("game_id=").append(encode(req.gameId))
            append("&league_id=").append(encode(req.leagueId))
            append("&home=").append(encode(req.home))
            append("&away=").append(encode(req.away))
            append("&starts_at=").append(encode(req.startsAtIso))
            if (history) append("&history=1")
        }
        return when (val r = getJson(http, "$url?$query", deadlineMs)) {
            is SourceResult.Empty -> SourceResult.Empty
            is SourceResult.Disabled -> SourceResult.Disabled
            is SourceResult.Failed -> r
            is SourceResult.Ok -> parse(r.value)
        }
    }

    private fun parse(e: JsonElement): SourceResult<MarketWinChance> {
        val o = e as? JsonObject ?: return SourceResult.Failed(false)
        if ((o["disabled"] as? JsonPrimitive)?.booleanOrNull == true) return SourceResult.Disabled
        val home = pct(o["home"]) ?: return SourceResult.Failed(false)
        val points = (o["history"] as? JsonArray).orEmpty().mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            val t = (po["t"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val h = pct(po["home"]) ?: return@mapNotNull null
            WinPoint(t, h)
        }
        return SourceResult.Ok(
            MarketWinChance(
                home = home,
                away = pct(o["away"]) ?: (100 - home),
                draw = pct(o["draw"]),
                asOfMs = (o["asOf"] as? JsonPrimitive)?.longOrNull,
                source = (o["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "polymarket",
                attribution = (o["attribution"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                history = points,
            ),
        )
    }

    private fun pct(e: JsonElement?): Int? {
        val d = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        return d.toInt().coerceIn(0, 100)
    }

    /** RFC 3986 percent-encoding of UTF-8 bytes (unreserved characters kept). */
    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }

    private companion object {
        const val HEX = "0123456789ABCDEF"
    }
}
