package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.HighlightsBlock
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import kotlinx.serialization.json.JsonObject

/**
 * TheSportsDB side of the chain. Unlike ESPN these shapes are fixed by our own backend (`lookup/<kind>/<id>`
 * envelopes), so the field names live here rather than in the remote mapping. Total: anything unreadable is null.
 */
internal object TsdbAssembler {
    const val TSDB = "tsdb"
    private val FINAL = setOf("FT", "AET", "PEN", "AOT", "AP", "FINAL", "FINISHED", "MATCH FINISHED", "FT_PEN", "AFTER EXTRA TIME")
    private val UPCOMING = setOf("", "NS", "NOT STARTED", "TBD", "SCHEDULED", "UPCOMING")
    private val POSTPONED = setOf("PST", "POSTPONED", "CANC", "CANCELLED", "CANCELED", "ABD", "ABANDONED")

    private fun JsonObject.str(key: String): String? = SportsPath.display(this[key])?.trim()?.takeIf { it.isNotEmpty() }

    fun state(row: JsonObject): GameState {
        if (row.str("strPostponed").equals("yes", ignoreCase = true)) return GameState.POSTPONED
        val s = (row.str("strStatus") ?: row.str("strProgress")).orEmpty().uppercase()
        return when (s) {
            in FINAL -> GameState.FINAL
            in UPCOMING -> GameState.UPCOMING
            in POSTPONED -> GameState.POSTPONED
            else -> GameState.LIVE
        }
    }

    fun header(row: JsonObject, profileKey: String?): EspnHeader? = try {
        val kind = when (profileKey) { "motorsport", "fighting" -> "event"; "tennis" -> "versus"; else -> "teams" }
        val home = Side(
            name = row.str("strHomeTeam") ?: return null,
            badgeUrl = row.str("strHomeTeamBadge"),
            score = row.str("intHomeScore"),
        )
        val away = Side(name = row.str("strAwayTeam").orEmpty(), badgeUrl = row.str("strAwayTeamBadge"), score = row.str("intAwayScore"))
        val header = when {
            kind == "event" -> EventHeader(name = row.str("strEvent") ?: home.name, venue = row.str("strVenue"))
            away.name.isBlank() -> EventHeader(name = row.str("strEvent") ?: home.name, venue = row.str("strVenue"))
            kind == "versus" -> VersusHeader(home, away)
            else -> TeamsHeader(home, away)
        }
        EspnHeader(
            header = header,
            state = state(row),
            live = LiveInfo(clock = row.str("strProgress"), detail = row.str("strStatus")),
            startsAt = Iso.normalize(row.str("strTimestamp")),
            venue = row.str("strVenue"),
            homeTeamId = row.str("idHomeTeam"),
            awayTeamId = row.str("idAwayTeam"),
        )
    } catch (_: Throwable) {
        null
    }

    fun round(row: JsonObject): String? = row.str("intRound")?.takeIf { it != "0" }

    /** [lookup] is the `{"lookup":[...]}` envelope for BROADCAST / STATS / TIMELINE; HIGHLIGHTS reads the event row. */
    fun block(type: BlockType, row: JsonObject, lookup: JsonObject?): GameBlock? = try {
        when (type) {
            BlockType.HIGHLIGHTS -> row.str("strVideo")?.let { HighlightsBlock(url = it, thumbnail = row.str("strThumb"), source = TSDB) }
            BlockType.BROADCAST -> lookup?.let(::broadcast)
            BlockType.STATS -> lookup?.let(::stats)
            BlockType.TIMELINE -> lookup?.let(::timeline)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun rows(lookup: JsonObject): List<JsonObject> = TheSportsDbHttpSource.rowsOf(lookup)

    private fun broadcast(lookup: JsonObject): GameBlock? {
        val by = LinkedHashMap<String, MutableList<String>>()
        for (r in rows(lookup)) {
            val country = r.str("strCountry") ?: continue
            val channel = r.str("strChannel") ?: continue
            val list = by.getOrPut(country) { mutableListOf() }
            if (channel !in list) list += channel
        }
        return by.takeIf { it.isNotEmpty() }?.let { BroadcastBlock(it, TSDB) }
    }

    private fun stats(lookup: JsonObject): GameBlock? {
        val out = rows(lookup).mapNotNull { r ->
            val label = r.str("strStat") ?: return@mapNotNull null
            val h = r.str("intHome")
            val a = r.str("intAway")
            if (h == null && a == null) null else StatRow(prettify(label), h ?: "-", a ?: "-")
        }
        return out.take(14).takeIf { it.isNotEmpty() }?.let { StatsBlock(it, TSDB) }
    }

    private fun prettify(label: String): String = label.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

    private fun timeline(lookup: JsonObject): GameBlock? {
        val items = rows(lookup).mapNotNull { r ->
            val type = r.str("strTimeline") ?: return@mapNotNull null
            val detail = r.str("strTimelineDetail")
            val player = r.str("strPlayer")
            val assist = r.str("strAssist")
            val text = buildString {
                append(detail ?: type)
                if (player != null) append(" — ").append(player)
                if (assist != null) append(" (").append(assist).append(")")
            }
            val minute = r.str("intTime")
            TimelineItem(
                time = minute?.let { "$it'" }.orEmpty(),
                period = r.str("strPeriod"),
                side = when (r.str("strHome")?.lowercase()) { "yes" -> "home"; "no" -> "away"; else -> null },
                text = text,
                kind = when (type.lowercase()) { "goal" -> "goal"; "card" -> "card"; "subst", "substitution" -> "sub"; else -> null },
            ) to (minute?.toIntOrNull() ?: Int.MAX_VALUE)
        }.sortedBy { it.second }.map { it.first }
        return items.takeIf { it.isNotEmpty() }?.let { TimelineBlock(it, TSDB) }
    }
}
