package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.ResultsBlock
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.BlockType
import com.nuvio.app.features.sportsdetail.internal.EspnAssembler
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every assembler against the recorded fixtures of NBA, NFL, MLB, NHL, EPL, F1, UFC (+ tennis, cricket headers). */
class EspnAssemblerTest {
    private val asm = EspnAssembler(bundledMapping())

    private fun ev(s: String): JsonElement = parse(s)

    private inline fun <reified T : GameBlock> block(type: BlockType, profile: String, event: String, summary: String?): T? =
        asm.assemble(type, profile, ev(event), summary?.let(::parse)) as T?

    // ---- header -----------------------------------------------------------------------------------------------

    @Test fun `NBA header is teams with scores and records and a final state`() {
        val h = assertNotNull(asm.header("basketball", ev(SportsFixtures.espn_nba_event)))
        val teams = assertIs<TeamsHeader>(h.header)
        assertEquals("Atlanta Hawks", teams.home.name)
        assertEquals("Washington Wizards", teams.away.name)
        assertEquals("139", teams.home.score)
        assertEquals("132", teams.away.score)
        assertEquals("6-4", teams.home.record)
        assertNotNull(teams.home.badgeUrl)
        assertEquals(GameState.FINAL, h.state)
        assertEquals("Final", h.live?.detail)
        assertEquals("State Farm Arena", h.venue)
    }

    @Test fun `a live hockey header carries the ordinal period and the period number`() {
        val live = SportsFixtures.espn_nhl_event.replace("\"state\":\"post\"", "\"state\":\"in\"").replace("\"period\":3", "\"period\":2")
        val h = assertNotNull(asm.header("hockey", ev(live)))
        assertEquals("2nd", h.live?.periodLabel)
        assertEquals(2, h.live?.period)
    }

    @Test fun `EPL header carries form and soccer clock`() {
        val h = assertNotNull(asm.header("soccer", ev(SportsFixtures.espn_epl_event)))
        val t = assertIs<TeamsHeader>(h.header)
        assertEquals("Leeds United", t.home.name)
        assertEquals("1", t.home.score)
        assertEquals("0", t.away.score)
        assertEquals("90'+5'", h.live?.clock)
        assertEquals(GameState.FINAL, h.state)
    }

    @Test fun `NFL MLB NHL headers`() {
        assertEquals("Baltimore Ravens", assertIs<TeamsHeader>(asm.header("football", ev(SportsFixtures.espn_nfl_event))!!.header).home.name)
        assertEquals("Tampa Bay Rays", assertIs<TeamsHeader>(asm.header("baseball", ev(SportsFixtures.espn_mlb_event))!!.header).home.name)
        assertEquals("Buffalo Sabres", assertIs<TeamsHeader>(asm.header("hockey", ev(SportsFixtures.espn_nhl_event))!!.header).home.name)
    }

    @Test fun `F1 and UFC headers are events and upcoming UFC maps to upcoming`() {
        val f1 = assertNotNull(asm.header("motorsport", ev(SportsFixtures.espn_f1_event)))
        assertEquals("MSC Cruises São Paulo Grand Prix", assertIs<EventHeader>(f1.header).name)
        val ufc = assertNotNull(asm.header("fighting", ev(SportsFixtures.espn_ufc_event)))
        assertEquals("UFC Fight Night: Cortes Acosta vs. Walker", assertIs<EventHeader>(ufc.header).name)
        assertEquals(GameState.UPCOMING, ufc.state)
        assertEquals("Meta APEX", assertIs<EventHeader>(ufc.header).venue)
    }

    @Test fun `tennis header is versus ordered by competitor order`() {
        val h = assertNotNull(asm.header("tennis", ev(SportsFixtures.espn_atp_event)))
        val v = assertIs<VersusHeader>(h.header)
        assertTrue(v.a.name.isNotBlank() && v.b.name.isNotBlank())
        assertTrue(v.a.name != v.b.name)
    }

    @Test fun `cricket header resolves`() {
        assertNotNull(asm.header("cricket", ev(SportsFixtures.espn_cricket_8039_event)))
    }

    @Test fun `a source with no usable competitors yields no header and no exception`() {
        assertNull(asm.header("basketball", ev("""{"id":"1"}""")))
        assertNull(asm.header("nonexistent-profile", ev(SportsFixtures.espn_nba_event)))
    }

    // ---- linescore --------------------------------------------------------------------------------------------

    @Test fun `NBA linescore has quarters and a total and home row first`() {
        val b = assertNotNull(block<LinescoreBlock>(BlockType.LINESCORE, "basketball", SportsFixtures.espn_nba_event, null))
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "T"), b.columns)
        assertEquals(listOf("28", "38", "39", "34", "139"), b.rows[0])
        assertEquals(listOf("32", "27", "35", "38", "132"), b.rows[1])
        assertEquals(2, b.rowLabels.size)
        assertEquals("espn", b.source)
    }

    @Test fun `NFL and NHL linescores`() {
        val nfl = assertNotNull(block<LinescoreBlock>(BlockType.LINESCORE, "football", SportsFixtures.espn_nfl_event, null))
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "T"), nfl.columns)
        assertEquals(listOf("3", "0", "0", "7", "10"), nfl.rows[0])
        assertEquals(listOf("10", "7", "3", "13", "33"), nfl.rows[1])
        val nhl = assertNotNull(block<LinescoreBlock>(BlockType.LINESCORE, "hockey", SportsFixtures.espn_nhl_event, null))
        assertEquals(listOf("P1", "P2", "P3", "T"), nhl.columns)
        assertEquals(listOf("2", "3", "0", "5"), nhl.rows[0])
    }

    @Test fun `MLB linescore is innings then R H E`() {
        val b = assertNotNull(block<LinescoreBlock>(BlockType.LINESCORE, "baseball", SportsFixtures.espn_mlb_event, null))
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "R", "H", "E"), b.columns)
        assertEquals(listOf("0", "0", "0", "0", "0", "0", "0", "0", "1", "1", "4", "0"), b.rows[0])
        assertEquals(listOf("0", "1", "0", "0", "1", "2", "0", "0", "0", "4", "8", "2"), b.rows[1])
    }

    @Test fun `soccer has no linescore spec so the block is simply absent`() {
        assertNull(asm.assemble(BlockType.LINESCORE, "soccer", ev(SportsFixtures.espn_epl_event), null))
    }

    @Test fun `a missing period score keeps the columns aligned`() {
        val ev = """{"id":"1","date":"2026-11-07T00:00Z","status":{"type":{"state":"in","shortDetail":"Q3"}},
            "competitions":[{"competitors":[
              {"homeAway":"home","score":"6","team":{"id":"1","displayName":"H","abbreviation":"H"},"linescores":[{"displayValue":"1"},{},{"displayValue":"5"}]},
              {"homeAway":"away","score":"4","team":{"id":"2","displayName":"A","abbreviation":"A"},"linescores":[{"displayValue":"2"},{"displayValue":"2"},{"displayValue":"0"}]}]}]}"""
        val b = asm.assemble(BlockType.LINESCORE, "basketball", parse(ev), null) as LinescoreBlock
        assertEquals(listOf("1", "-", "5", "6"), b.rows[0])
        assertEquals(listOf("2", "2", "0", "4"), b.rows[1])
    }

    // ---- stats ------------------------------------------------------------------------------------------------

    @Test fun `NBA stats follow the allow-list order and put each team on the right side`() {
        val b = assertNotNull(block<StatsBlock>(BlockType.STATS, "basketball", SportsFixtures.espn_nba_event, SportsFixtures.espn_nba_summary))
        assertEquals(listOf("Field Goal %", "Three Point %", "Free Throw %", "Rebounds", "Assists", "Steals", "Blocks", "Turnovers"), b.rows.map { it.label })
        val fg = b.rows.first { it.label == "Field Goal %" }
        // boxscore lists the AWAY team first (id 27); the home team (id 1) shot 52-98.
        assertEquals("53", fg.home)
        assertEquals("51", fg.away)
    }

    @Test fun `EPL stats from the summary`() {
        val b = assertNotNull(block<StatsBlock>(BlockType.STATS, "soccer", SportsFixtures.espn_epl_event, SportsFixtures.espn_epl_summary))
        assertTrue(b.rows.size >= 3, "rows=${b.rows}")
        assertEquals("11", b.rows.first { it.label.equals("Fouls", true) }.home)
    }

    @Test fun `NFL and NHL stats`() {
        val nfl = assertNotNull(block<StatsBlock>(BlockType.STATS, "football", SportsFixtures.espn_nfl_event, SportsFixtures.espn_nfl_summary))
        assertTrue(nfl.rows.any { it.label == "Total Yards" })
        val nhl = assertNotNull(block<StatsBlock>(BlockType.STATS, "hockey", SportsFixtures.espn_nhl_event, SportsFixtures.espn_nhl_summary))
        assertTrue(nhl.rows.isNotEmpty())
    }

    @Test fun `stats without a summary are absent not empty`() {
        assertNull(asm.assemble(BlockType.STATS, "basketball", ev(SportsFixtures.espn_nba_event), null))
    }

    // ---- leaders ----------------------------------------------------------------------------------------------

    @Test fun `NBA leaders are per team with sides`() {
        val b = assertNotNull(block<LeadersBlock>(BlockType.LEADERS, "basketball", SportsFixtures.espn_nba_event, null))
        assertTrue(b.items.isNotEmpty())
        assertEquals(setOf("home", "away"), b.items.map { it.side }.toSet())
        assertTrue(b.items.all { it.athlete.isNotBlank() && it.value.isNotBlank() && it.category.isNotBlank() })
    }

    @Test fun `NFL and MLB competition-level leaders resolve a side by team id`() {
        val nfl = assertNotNull(block<LeadersBlock>(BlockType.LEADERS, "football", SportsFixtures.espn_nfl_event, null))
        assertTrue(nfl.items.any { it.athlete == "Trevor Lawrence" && it.side == "away" })
        val mlb = assertNotNull(block<LeadersBlock>(BlockType.LEADERS, "baseball", SportsFixtures.espn_mlb_event, null))
        assertTrue(mlb.items.isNotEmpty())
    }

    @Test fun `leaders come from the per-game summary so they follow the live game`() {
        val b = assertNotNull(block<LeadersBlock>(BlockType.LEADERS, "basketball", SportsFixtures.espn_nba_event, SportsFixtures.espn_nba_summary))
        assertEquals(setOf("home", "away"), b.items.map { it.side }.toSet())
        assertTrue(b.items.any { it.category == "Points" && it.athlete == "Nickeil Alexander-Walker" && it.value == "28" && it.side == "home" })
        val nfl = assertNotNull(block<LeadersBlock>(BlockType.LEADERS, "football", SportsFixtures.espn_nfl_event, SportsFixtures.espn_nfl_summary))
        assertTrue(nfl.items.all { it.side != null })
    }

    @Test fun `baseball has no summary leaders so it falls back to the scoreboard snapshot`() {
        assertNotNull(block<LeadersBlock>(BlockType.LEADERS, "baseball", SportsFixtures.espn_mlb_event, SportsFixtures.espn_mlb_summary))
    }

    // ---- timeline ---------------------------------------------------------------------------------------------

    @Test fun `NBA timeline is the last five scoring plays in chronological order`() {
        val b = assertNotNull(block<TimelineBlock>(BlockType.TIMELINE, "basketball", SportsFixtures.espn_nba_event, SportsFixtures.espn_nba_summary))
        assertEquals(5, b.items.size)
        val last = b.items.last()
        assertTrue(last.text.startsWith("Onyeka Okongwu makes free throw"), last.text)
        assertEquals("4th Quarter", last.period)
        assertEquals("4.5", last.time)
        assertEquals("score", last.kind)
    }

    @Test fun `NHL goals are tagged goal with a side`() {
        val b = assertNotNull(block<TimelineBlock>(BlockType.TIMELINE, "hockey", SportsFixtures.espn_nhl_event, SportsFixtures.espn_nhl_summary))
        assertTrue(b.items.size in 1..5)
        assertTrue(b.items.all { it.kind == "goal" && it.side != null })
    }

    @Test fun `NFL scoring plays fall back to the numbered period`() {
        val b = assertNotNull(block<TimelineBlock>(BlockType.TIMELINE, "football", SportsFixtures.espn_nfl_event, SportsFixtures.espn_nfl_summary))
        assertEquals(5, b.items.size)
        assertTrue(b.items.all { it.period?.startsWith("Q") == true }, "${b.items}")
    }

    @Test fun `MLB timeline uses the inning as the time`() {
        val b = assertNotNull(block<TimelineBlock>(BlockType.TIMELINE, "baseball", SportsFixtures.espn_mlb_event, SportsFixtures.espn_mlb_summary))
        assertTrue(b.items.isNotEmpty() && b.items.all { it.text.isNotBlank() })
    }

    @Test fun `EPL key events keep cards with the right side`() {
        val b = assertNotNull(block<TimelineBlock>(BlockType.TIMELINE, "soccer", SportsFixtures.espn_epl_event, SportsFixtures.espn_epl_summary))
        val card = b.items.first { it.kind == "card" }
        assertEquals("away", card.side) // Micky van de Ven (Tottenham, away)
        assertTrue(b.items.all { it.text.isNotBlank() })
    }

    // ---- win probability --------------------------------------------------------------------------------------

    @Test fun `win probability is the home percent 0 to 100`() {
        val nba = assertNotNull(block<WinProbabilityBlock>(BlockType.WIN_PROBABILITY, "basketball", SportsFixtures.espn_nba_event, SportsFixtures.espn_nba_summary))
        assertEquals(100, nba.homePercent)
        val nfl = assertNotNull(block<WinProbabilityBlock>(BlockType.WIN_PROBABILITY, "football", SportsFixtures.espn_nfl_event, SportsFixtures.espn_nfl_summary))
        assertEquals(0, nfl.homePercent)
        val mid = asm.assemble(BlockType.WIN_PROBABILITY, "basketball", ev(SportsFixtures.espn_nba_event),
            parse("""{"winprobability":[{"homeWinPercentage":0.25},{"homeWinPercentage":0.623}]}""")) as WinProbabilityBlock
        assertEquals(62, mid.homePercent)
        val pct = asm.assemble(BlockType.WIN_PROBABILITY, "basketball", ev(SportsFixtures.espn_nba_event),
            parse("""{"winprobability":[{"homeWinPercentage":71.4}]}""")) as WinProbabilityBlock
        assertEquals(71, pct.homePercent)
    }

    @Test fun `NHL has no win probability in the profile`() {
        assertNull(asm.assemble(BlockType.WIN_PROBABILITY, "hockey", ev(SportsFixtures.espn_nhl_event), parse(SportsFixtures.espn_nhl_summary)))
    }

    // ---- situation --------------------------------------------------------------------------------------------

    @Test fun `football situation is the down and distance line`() {
        val live = """{"competitions":[{"situation":{"downDistanceText":"3rd & 4 at KC 38"}}]}"""
        val b = asm.assemble(BlockType.SITUATION, "football", ev(live), null) as SituationBlock
        assertEquals("3rd & 4 at KC 38", b.line)
    }

    @Test fun `baseball situation object becomes parts and a count line`() {
        val live = """{"competitions":[{"situation":{"balls":2,"strikes":1,"outs":2,"onFirst":true,"onSecond":false,"onThird":true}}]}"""
        val b = asm.assemble(BlockType.SITUATION, "baseball", ev(live), null) as SituationBlock
        assertEquals("2-1, 2 outs", b.line)
        assertEquals("true", b.parts["onFirst"])
        assertEquals("2", b.parts["outs"])
    }

    @Test fun `no situation in a finished game fixture`() {
        assertNull(asm.assemble(BlockType.SITUATION, "football", ev(SportsFixtures.espn_nfl_event), null))
        assertNull(asm.assemble(BlockType.SITUATION, "baseball", ev(SportsFixtures.espn_mlb_event), null))
    }

    // ---- results ----------------------------------------------------------------------------------------------

    @Test fun `F1 results list sessions`() {
        val b = assertNotNull(block<ResultsBlock>(BlockType.RESULTS, "motorsport", SportsFixtures.espn_f1_event, null))
        assertEquals(listOf("Session", "Result"), b.columns)
        assertEquals(3, b.rows.size)
        assertEquals("FP1", b.rows[0][0])
        assertTrue(b.rows.all { it.size == 2 && it[1].isNotBlank() })
    }

    @Test fun `UFC results list bouts`() {
        val b = assertNotNull(block<ResultsBlock>(BlockType.RESULTS, "fighting", SportsFixtures.espn_ufc_event, null))
        assertEquals(listOf("Bout", "Fighters", "Result"), b.columns)
        assertEquals("Welterweight", b.rows[0][0])
        assertEquals("Jonny Parsons vs José Souza", b.rows[0][1])
    }

    // ---- broadcast, form, lineup ------------------------------------------------------------------------------

    @Test fun `broadcast names come from the scoreboard under region US`() {
        val b = assertNotNull(block<BroadcastBlock>(BlockType.BROADCAST, "soccer", SportsFixtures.espn_epl_event, null))
        assertEquals(listOf("USA Net", "Universo"), b.byRegion["US"])
        assertNull(asm.assemble(BlockType.BROADCAST, "soccer", ev("""{"competitions":[{"broadcasts":[]}]}"""), null))
    }

    @Test fun `form splits the result string per side`() {
        val b = assertNotNull(block<FormBlock>(BlockType.FORM, "soccer", SportsFixtures.espn_epl_event, null))
        assertEquals(listOf("W", "L", "D", "D", "L"), b.home)
        assertEquals(listOf("L", "W", "W", "W", "D"), b.away)
        assertNull(asm.assemble(BlockType.FORM, "basketball", ev(SportsFixtures.espn_nba_event), null))
    }

    @Test fun `lineup from the soccer summary rosters`() {
        val b = assertNotNull(block<LineupBlock>(BlockType.LINEUP, "soccer", SportsFixtures.espn_epl_event, SportsFixtures.espn_epl_summary))
        assertEquals(2, b.sides.size)
        val home = b.sides.first { it.side == "home" }
        assertEquals("3-4-2-1", home.formation)
        val gk = home.players.first()
        assertEquals("1", gk.number)
        assertEquals("James Trafford", gk.name)
        assertTrue(gk.starter)
        assertTrue(gk.pos.isNotBlank())
    }

    // ---- mapping edits repair broken blocks -------------------------------------------------------------------

    @Test fun `tennis linescore is empty under v1 and a mapping edit renaming the path repairs it`() {
        // v1 reads linescores[*].displayValue; ESPN tennis sets carry `value`. This is the "unverified" gap in the contract.
        assertNull(asm.assemble(BlockType.LINESCORE, "tennis", ev(SportsFixtures.espn_atp_event), null))
        val fixed = EspnAssembler(mappingOf(editedMappingJson(
            "\"tennis\": { \"blocks\": [\"header\", \"situation\", \"watch\", \"broadcast\", \"linescore\", \"next\"], \"linescore\": { \"from\": \"scoreboard\", \"path\": \"competitions[0].competitors[*].linescores[*].displayValue\" } }",
            "\"tennis\": { \"blocks\": [\"header\", \"situation\", \"watch\", \"broadcast\", \"linescore\", \"next\"], \"linescore\": { \"from\": \"scoreboard\", \"path\": \"competitions[0].competitors[*].linescores[*].value\" } }",
        )))
        val b = assertNotNull(fixed.assemble(BlockType.LINESCORE, "tennis", ev(SportsFixtures.espn_atp_event), null)) as LinescoreBlock
        assertEquals(2, b.rows.size)
        assertTrue(b.rows.all { r -> r.isNotEmpty() && r.all { it.matches(Regex("[0-9]+")) } }, "${b.rows}")
    }

    @Test fun `a renamed stats path breaks the block and the corrected path restores it`() {
        val brokenJson = editedMappingJson("\"path\": \"boxscore.teams[*].statistics[*]\", \"label\": \"label\", \"value\": \"displayValue\",\n        \"allow\": [\"Field Goal %\"", "\"path\": \"boxscore.teamz[*].statistics[*]\", \"label\": \"label\", \"value\": \"displayValue\",\n        \"allow\": [\"Field Goal %\"")
        val broken = EspnAssembler(mappingOf(brokenJson))
        assertNull(broken.assemble(BlockType.STATS, "basketball", ev(SportsFixtures.espn_nba_event), parse(SportsFixtures.espn_nba_summary)))
        val repaired = EspnAssembler(mappingOf(brokenJson.replace("boxscore.teamz[*]", "boxscore.teams[*]")))
        assertNotNull(repaired.assemble(BlockType.STATS, "basketball", ev(SportsFixtures.espn_nba_event), parse(SportsFixtures.espn_nba_summary)))
    }

    @Test fun `assembleAll honours the profile block order and skips what is missing`() {
        val all = asm.assembleAll("basketball", ev(SportsFixtures.espn_nba_event), parse(SportsFixtures.espn_nba_summary))
        val types = all.map { it::class.simpleName }
        assertTrue(types.indexOf("LinescoreBlock") in 0 until types.indexOf("StatsBlock"), "$types")
        assertTrue(all.none { it is LineupBlock })
    }

    @Test fun `garbage roots never throw`() {
        for (junk in listOf("[]", "null", "\"x\"", "{}", "{\"competitions\":5}", "{\"competitions\":[null]}")) {
            for (t in BlockType.entries) asm.assemble(t, "basketball", parse(junk), parse(junk))
            asm.header("basketball", parse(junk))
        }
    }
}
