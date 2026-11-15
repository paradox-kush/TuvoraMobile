package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.internal.BlockType
import com.nuvio.app.features.sportsdetail.internal.EspnAssembler
import com.nuvio.app.features.sportsdetail.internal.Iso
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameScreenAssemblerTest {
    private val asm = EspnAssembler(bundledMapping())
    private fun teams(profile: String, event: String) = assertIs<TeamsHeader>(assertNotNull(asm.header(profile, parse(event))).header)

    // ---- Side colour / rank ---------------------------------------------------------------------------------------------

    @Test fun `sides carry colour alternate colour and poll rank`() {
        val h = teams("football", GameScreenFixtures.cfbLiveEvent)
        assertEquals("fe5c00", h.home.color); assertEquals(25, h.home.rank)
        assertEquals("da291c", h.away.color); assertEquals(12, h.away.rank)
        assertTrue(h.home.altColor != null)
    }

    @Test fun `an NBA team has colour but no rank and 99 means unranked`() {
        val h = teams("basketball", GameScreenFixtures.nbaEvent)
        assertTrue(h.home.color != null && h.home.rank == null)
        val unranked = GameScreenFixtures.cfbLiveEvent.replace("\"current\":25", "\"current\":99")
        assertNull(teams("football", unranked).home.rank)
    }

    @Test fun `a malformed colour is dropped rather than shown`() {
        val bad = GameScreenFixtures.cfbLiveEvent.replace("\"color\":\"fe5c00\"", "\"color\":\"not-a-colour\"")
        assertNull(teams("football", bad).home.color)
    }

    // ---- Plays ------------------------------------------------------------------------------------------------------------

    private fun plays(profile: String, event: String, summary: String, mapping: com.nuvio.app.features.sportsdetail.internal.SportsMapping = bundledMapping()) =
        EspnAssembler(mapping).assemble(BlockType.PLAYS, profile, parse(event), parse(summary)) as? PlaysBlock

    @Test fun `NBA plays are newest first with id wallclock score and side`() {
        val b = assertNotNull(plays("basketball", GameScreenFixtures.nbaEvent, GameScreenFixtures.nbaSummary))
        assertEquals(60, b.items.size, "capped at extra.last = 60")
        val newest = b.items.first()
        val oldest = b.items.last()
        assertTrue(Iso.parseMs("2026-11-15T01:12:28Z")!! > 0)
        assertTrue(newest.wallclockMs!! >= oldest.wallclockMs!!, "newest first")
        assertNotNull(newest.id)
        assertTrue(newest.score!!.matches(Regex("""\d+–\d+""")), "home–away running score: ${newest.score}")
        assertTrue(newest.side == "home" || newest.side == "away" || newest.side == null)
        assertTrue(b.items.all { it.time.isNotBlank() && it.period?.isNotBlank() == true })
    }

    @Test fun `the plays cap comes from the mapping`() {
        val m = mappingOf(editedMappingJson("\"extra\": {\"last\": 60}", "\"extra\": {\"last\": 5}"))
        assertEquals(5, plays("basketball", GameScreenFixtures.nbaEvent, GameScreenFixtures.nbaSummary, m)!!.items.size)
    }

    @Test fun `NBA play fields line up with the feed`() {
        val b = plays("basketball", GameScreenFixtures.nbaEvent, GameScreenFixtures.nbaSummary)!!
        val raw = com.nuvio.app.features.sportsdetail.internal.SportsPath.read(parse(GameScreenFixtures.nbaSummary), "plays[-1]").single()
        fun t(path: String) = com.nuvio.app.features.sportsdetail.internal.SportsPath.text(raw, path)
        val p = b.items.first()
        assertEquals(t("id"), p.id)
        assertEquals(t("text"), p.text)
        assertEquals("${t("homeScore")}\u2013${t("awayScore")}", p.score, "home first")
        assertEquals(t("clock.displayValue"), p.time)
        assertEquals(t("period.displayValue"), p.period)
        assertEquals(Iso.parseMs(t("wallclock")), p.wallclockMs)
        val homeId = com.nuvio.app.features.sportsdetail.internal.SportsPath.text(parse(GameScreenFixtures.nbaEvent), "competitions[0].competitors[homeAway=home].team.id")
        assertEquals(if (t("team.id") == homeId) "home" else "away", p.side)
    }

    @Test fun `scoring plays are tagged score or goal`() {
        val nba = plays("basketball", GameScreenFixtures.nbaEvent, GameScreenFixtures.nbaSummary)!!
        assertTrue(nba.items.any { it.kind == "score" })
        val nhl = plays("hockey", GameScreenFixtures.nhlLiveEvent, GameScreenFixtures.nhlLiveSummary)!!
        assertTrue(nhl.items.any { it.kind == "goal" }, "an NHL goal is a goal")
        assertTrue(nhl.items.any { it.kind == "penalty" }, "penalty plays carry the penalty tag")
    }

    @Test fun `NFL plays flatten the drives newest first with quarter labels`() {
        val b = assertNotNull(plays("football", GameScreenFixtures.nflEvent, GameScreenFixtures.nflSummary))
        assertEquals(12, b.items.size, "3 drives x 4 plays in the trimmed fixture")
        assertTrue(b.items.first().wallclockMs!! >= b.items.last().wallclockMs!!)
        assertTrue(b.items.all { it.period?.startsWith("Q") == true }, b.items.map { it.period }.toString())
    }

    @Test fun `a live NFL summary adds the current drive on top`() {
        val live = """{"drives":{"previous":[{"id":"d1","plays":[{"id":"a1","text":"Old play","period":{"number":1},"clock":{"displayValue":"14:00"},"wallclock":"2026-11-15T02:00:00Z"}]}],
            "current":{"id":"d2","plays":[{"id":"b1","text":"Newest play","period":{"number":2},"clock":{"displayValue":"9:12"},"wallclock":"2026-11-15T02:40:00Z"}]}}}"""
        val b = assertNotNull(plays("football", GameScreenFixtures.cfbLiveEvent, live))
        assertEquals(listOf("b1", "a1"), b.items.map { it.id })
    }

    @Test fun `soccer plays keep only the allowed kinds and resolve the side by team name`() {
        val b = assertNotNull(plays("soccer", GameScreenFixtures.eplEvent, GameScreenFixtures.eplSummary))
        val kinds = b.items.map { it.kind }.toSet()
        assertTrue(kinds.all { it != null }, "every kept play has a kind: $kinds")
        assertTrue(b.items.none { it.text.startsWith("Lineups") }, "no unscoped commentary")
        assertTrue(b.items.any { it.kind == "corner" } && b.items.any { it.kind == "sub" || it.kind == "card" || it.kind == "goal" })
        assertTrue(b.items.any { it.side == "home" } && b.items.any { it.side == "away" })
        assertNull(b.items.first().score, "soccer has no running score in the commentary")
    }

    @Test fun `a summary without plays yields no block`() {
        assertNull(plays("basketball", GameScreenFixtures.nbaEvent, """{"boxscore":{"teams":[]}}"""))
        assertNull(plays("football", GameScreenFixtures.cfbLiveEvent, """{"drives":{"previous":[]}}"""))
    }

    // ---- Standings table ------------------------------------------------------------------------------------------------

    private fun table(profile: String, event: String, summary: String) =
        asm.assemble(BlockType.STANDINGS_TABLE, profile, parse(event), parse(summary)) as? StandingsTableBlock

    @Test fun `NBA standings use the W L PCT GB STRK columns and flag both game teams`() {
        val t = assertNotNull(table("basketball", GameScreenFixtures.nbaEvent, GameScreenFixtures.nbaSummary))
        assertEquals(listOf("W", "L", "PCT", "GB", "STRK"), t.groups.first().columns)
        val game = t.groups.flatMap { it.rows }.filter { it.isGameTeam }
        assertTrue(game.isNotEmpty() && game.all { it.teamId == "8" || it.teamId == "14" }, game.toString())
        assertTrue(t.groups.all { g -> g.rows.all { it.cells.size == g.columns.size } })
        assertTrue(t.groups.first().name.isNotBlank() && !t.groups.first().name.endsWith("Standings"))
    }

    @Test fun `a missing stat shows a dash`() {
        val stripped = GameScreenFixtures.nbaSummary.replace("\"name\":\"streak\"", "\"name\":\"streakX\"")
        val t = assertNotNull(table("basketball", GameScreenFixtures.nbaEvent, stripped))
        assertTrue(t.groups.flatMap { it.rows }.all { it.cells.last() == "–" })
    }

    @Test fun `soccer standings start with the rank and carry P W D L GD PTS`() {
        val t = assertNotNull(table("soccer", GameScreenFixtures.eplEvent, GameScreenFixtures.eplSummary))
        assertEquals(listOf("#", "P", "W", "D", "L", "GD", "PTS"), t.groups.single().columns)
        assertTrue(t.groups.single().rows.all { it.cells.size == 7 })
        assertTrue(t.groups.single().rows.any { it.isGameTeam }, "Brighton or Brentford is in the first 8 rows")
    }

    @Test fun `NHL and NFL standings read their own stat names`() {
        assertEquals(listOf("W", "L", "OTL", "PTS"), table("hockey", GameScreenFixtures.nhlLiveEvent, GameScreenFixtures.nhlLiveSummary)!!.groups.first().columns)
        assertEquals(listOf("W", "L", "T", "PCT"), table("football", GameScreenFixtures.nflEvent, GameScreenFixtures.nflSummary)!!.groups.first().columns)
    }

    @Test fun `no standings in the summary yields no block`() {
        assertNull(table("basketball", GameScreenFixtures.nbaEvent, """{"standings":{"groups":[]}}"""))
        assertNull(table("basketball", GameScreenFixtures.nbaEvent, "{}"))
    }

    @Test fun `new blocks are visible live and final`() {
        val d = asm.assembleAll("basketball", parse(GameScreenFixtures.nbaEvent), parse(GameScreenFixtures.nbaSummary))
        assertTrue(d.any { it is PlaysBlock } && d.any { it is StandingsTableBlock })
    }
}
