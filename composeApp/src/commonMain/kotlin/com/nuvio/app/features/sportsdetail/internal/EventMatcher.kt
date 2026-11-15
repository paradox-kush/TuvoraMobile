package com.nuvio.app.features.sportsdetail.internal

import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

/** What we know of the TheSportsDB game we are trying to find on ESPN. [away] null = a single-entity event (race, card). */
internal data class MatchTarget(val tsdbEventId: String, val home: String, val away: String?, val startsAtMs: Long)

internal data class EspnCandidate(val id: String, val home: String, val away: String?, val startsAtMs: Long, val name: String)

internal object TeamNames {
    private val DROP = setOf("fc", "cf", "afc", "sc", "ac")

    private val ACCENTS: Map<Char, Char> = buildMap {
        fun add(chars: String, to: Char) = chars.forEach { put(it, to) }
        add("àáâãäåāăąǎ", 'a'); add("çćĉċč", 'c'); add("ďđ", 'd'); add("èéêëēĕėęě", 'e'); add("ĝğġģ", 'g'); add("ĥħ", 'h')
        add("ìíîïĩīĭįı", 'i'); add("ĵ", 'j'); add("ķ", 'k'); add("ĺļľŀł", 'l'); add("ñńņňŉ", 'n'); add("òóôõöøōŏőǒ", 'o')
        add("ŕŗř", 'r'); add("śŝşšș", 's'); add("ţťŧț", 't'); add("ùúûüũūŭůűųǔ", 'u'); add("ŵ", 'w'); add("ýÿŷ", 'y'); add("źżž", 'z')
    }

    fun stripAccents(s: String): String = buildString(s.length) {
        for (ch in s) {
            val lower = ch.lowercaseChar()
            append(ACCENTS[lower] ?: lower)
        }
    }

    /** lowercase, accent-stripped, punctuation -> space, club-suffix tokens (fc/cf/afc) dropped, alias applied. */
    fun normalize(name: String, aliases: Map<String, String>): String {
        val base = clean(name)
        aliases.entries.firstOrNull { clean(it.key) == base }?.let { return clean(it.value) }
        return base
    }

    /** The one "same team?" rule: equal after normalisation, or one is a whole-word subset of the other. */
    fun sameTeam(a: String, b: String, aliases: Map<String, String>): Boolean = sameNormalized(normalize(a, aliases), normalize(b, aliases))

    internal fun sameNormalized(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val x = a.split(' ').toSet(); val y = b.split(' ').toSet()
        return (x.size >= 2 && y.containsAll(x)) || (y.size >= 2 && x.containsAll(y))
    }

    private fun clean(name: String): String =
        stripAccents(name).map { if (it.isLetterOrDigit() || it == '-') it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() && it !in DROP }.joinToString(" ")
}

/**
 * Finds the ESPN event for a TheSportsDB game: pinned by `eventOverrides["<tsdb id>"]` (legacy "tsdb:<id>" also read), else a candidate whose
 * kickoff is inside the date window AND whose two teams match after normalisation + alias table (in either
 * order). Single-entity events match on distinctive name tokens. Closest kickoff wins.
 */
internal class EventMatcher(private val m: SportsMapping) {

    fun match(target: MatchTarget, candidates: List<EspnCandidate>): EspnCandidate? {
        m.eventOverride(target.tsdbEventId)?.let { pinned ->
            return candidates.firstOrNull { it.id == pinned } ?: EspnCandidate(pinned, target.home, target.away, target.startsAtMs, target.home)
        }
        val windowMs = m.match.dateWindowHours * 3_600_000L
        val inWindow = candidates.filter { abs(it.startsAtMs - target.startsAtMs) <= windowMs }
        val hits = if (target.away == null) matchEntities(target, candidates, windowMs, inWindow) else inWindow.filter { teamsMatch(target, it) }
        return hits.minByOrNull { abs(it.startsAtMs - target.startsAtMs) }
    }

    private fun teamsMatch(t: MatchTarget, c: EspnCandidate): Boolean {
        val a = norm(t.home); val b = norm(t.away.orEmpty())
        val x = norm(c.home); val y = norm(c.away.orEmpty())
        val straight = same(a, x).toInt() + same(b, y).toInt()
        val swapped = same(a, y).toInt() + same(b, x).toInt()
        return maxOf(straight, swapped) >= m.match.minTeamsMatching.coerceAtLeast(1)
    }

    /** Race weekends span days, so the window is a day wider; names must share a distinctive token. */
    private fun matchEntities(t: MatchTarget, all: List<EspnCandidate>, windowMs: Long, narrow: List<EspnCandidate>): List<EspnCandidate> {
        val tokens = distinctive(t.home)
        val wide = all.filter { abs(it.startsAtMs - t.startsAtMs) <= windowMs + 48 * 3_600_000L }
        val pool = if (narrow.isEmpty()) wide else narrow + wide
        return pool.distinctBy { it.id }.filter { c -> tokens.isNotEmpty() && distinctive(c.name).any { it in tokens } }
    }

    private fun distinctive(name: String): Set<String> =
        TeamNames.normalize(name, m.aliases).split(' ').filter { it.length > 2 && it !in STOP }.toSet()

    private fun norm(s: String) = TeamNames.normalize(s, m.aliases)

    private fun same(a: String, b: String): Boolean = TeamNames.sameNormalized(a, b)

    private fun Boolean.toInt() = if (this) 1 else 0

    companion object {
        private val STOP = setOf("the", "grand", "prix", "fight", "night", "ufc", "vs", "and", "msc", "cruises")

        /** Candidates from an ESPN scoreboard payload (`events[*]`). */
        fun candidatesFrom(scoreboard: JsonElement, m: SportsMapping): List<EspnCandidate> =
            SportsPath.read(scoreboard, m.common.event ?: "events[*]").mapNotNull { e ->
                val id = SportsPath.text(e, m.common.id ?: "id") ?: return@mapNotNull null
                val start = Iso.parseMs(SportsPath.text(e, "date")) ?: return@mapNotNull null
                val name = SportsPath.text(e, "name").orEmpty()
                val home = SportsPath.text(SportsPath.first(e, m.common.home), m.common.team.name)
                val away = SportsPath.text(SportsPath.first(e, m.common.away), m.common.team.name)
                if (home != null && away != null) EspnCandidate(id, home, away, start, name)
                else EspnCandidate(id, name, null, start, name)
            }
    }
}
