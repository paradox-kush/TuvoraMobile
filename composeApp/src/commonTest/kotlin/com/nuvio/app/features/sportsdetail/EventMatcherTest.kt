package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.EspnCandidate
import com.nuvio.app.features.sportsdetail.internal.EventMatcher
import com.nuvio.app.features.sportsdetail.internal.MatchTarget
import com.nuvio.app.features.sportsdetail.internal.TeamNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventMatcherTest {
    private val mapping = bundledMapping()
    private val matcher = EventMatcher(mapping)
    private val t0 = 1_794_000_000_000L
    private val hour = 3_600_000L

    private fun cand(id: String, home: String, away: String?, at: Long = t0, name: String = "$away at $home") =
        EspnCandidate(id, home, away, at, name)

    @Test fun `normalisation lowercases strips accents punctuation and club suffixes`() {
        assertEquals("atletico madrid", TeamNames.normalize("Atlético Madrid CF", emptyMap()))
        assertEquals("tottenham hotspur", TeamNames.normalize("Tottenham Hotspur FC", emptyMap()))
        assertEquals("brighton hove albion", TeamNames.normalize("Brighton & Hove Albion", emptyMap()))
        assertEquals("manchester united", TeamNames.normalize("Man Utd", mapping.aliases))
        assertEquals("paris saint-germain", TeamNames.normalize("PSG", mapping.aliases))
    }

    @Test fun `exact names inside the date window match`() {
        val m = matcher.match(MatchTarget("2494097", "Leeds United", "Tottenham Hotspur", t0 + hour), listOf(cand("e1", "Leeds United", "Tottenham Hotspur")))
        assertEquals("e1", m?.id)
    }

    @Test fun `alias and accent differences match`() {
        val m = matcher.match(MatchTarget("1", "Man Utd", "Spurs", t0), listOf(cand("e2", "Manchester United", "Tottenham Hotspur")))
        assertEquals("e2", m?.id)
        val a = matcher.match(MatchTarget("1", "Atlético Madrid", "Girona FC", t0), listOf(cand("e3", "Atletico Madrid", "Girona")))
        assertEquals("e3", a?.id)
    }

    @Test fun `swapped home and away still match`() {
        assertEquals("e4", matcher.match(MatchTarget("1", "Leeds United", "Tottenham Hotspur", t0), listOf(cand("e4", "Tottenham Hotspur", "Leeds United")))?.id)
    }

    @Test fun `outside the date window there is no match`() {
        assertNull(matcher.match(MatchTarget("1", "Leeds United", "Tottenham Hotspur", t0), listOf(cand("e5", "Leeds United", "Tottenham Hotspur", t0 + 7 * hour))))
        assertEquals("e6", matcher.match(MatchTarget("1", "Leeds United", "Tottenham Hotspur", t0), listOf(cand("e6", "Leeds United", "Tottenham Hotspur", t0 - 5 * hour)))?.id)
    }

    @Test fun `one matching team is not enough`() {
        assertNull(matcher.match(MatchTarget("1", "Leeds United", "Tottenham Hotspur", t0), listOf(cand("e7", "Leeds United", "Arsenal"))))
    }

    @Test fun `double header picks the closest kickoff`() {
        val m = matcher.match(
            MatchTarget("1", "Yankees", "Red Sox", t0 + 4 * hour),
            listOf(cand("g1", "Yankees", "Red Sox", t0), cand("g2", "Yankees", "Red Sox", t0 + 3 * hour)),
        )
        assertEquals("g2", m?.id)
    }

    @Test fun `event override wins over any matching`() {
        val overridden = mappingOf(editedMappingJson("\"eventOverrides\": {}", "\"eventOverrides\": {\"2494097\": \"forced\"}"))
        val m = EventMatcher(overridden).match(
            MatchTarget("2494097", "Leeds United", "Tottenham Hotspur", t0),
            listOf(cand("e1", "Leeds United", "Tottenham Hotspur"), cand("forced", "Nobody", "Else", t0 + 40 * hour)),
        )
        assertEquals("forced", m?.id)
    }

    @Test fun `single-entity events match on distinctive name tokens`() {
        val m = matcher.match(
            MatchTarget("9", "Sao Paulo Grand Prix", null, t0),
            listOf(cand("f1", "MSC Cruises São Paulo Grand Prix", null, t0 + 20 * hour, "MSC Cruises São Paulo Grand Prix")),
        )
        assertEquals("f1", m?.id)
        assertNull(matcher.match(MatchTarget("9", "Monaco Grand Prix", null, t0), listOf(cand("f2", "MSC Cruises São Paulo Grand Prix", null, t0, "MSC Cruises São Paulo Grand Prix"))))
        val ufc = matcher.match(MatchTarget("9", "UFC Fight Night Cortes Acosta vs Walker", null, t0),
            listOf(cand("u1", "UFC Fight Night: Cortes Acosta vs. Walker", null, t0 + 2 * hour, "UFC Fight Night: Cortes Acosta vs. Walker")))
        assertEquals("u1", ufc?.id)
    }

    @Test fun `candidates are read from a scoreboard payload`() {
        val sb = parse("""{"events":[{"id":"77","date":"2026-11-07T12:30Z","name":"Tottenham Hotspur at Leeds United","competitions":[{"competitors":[
            {"homeAway":"home","team":{"displayName":"Leeds United"}},{"homeAway":"away","team":{"displayName":"Tottenham Hotspur"}}]}]}]}""")
        val list = EventMatcher.candidatesFrom(sb, mapping)
        assertEquals(1, list.size)
        assertEquals("77", list[0].id)
        assertEquals("Leeds United", list[0].home)
        assertEquals("Tottenham Hotspur", list[0].away)
        assertEquals(1794054600000L, list[0].startsAtMs)
    }
}
