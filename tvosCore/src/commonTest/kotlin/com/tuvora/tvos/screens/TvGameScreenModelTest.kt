package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LeaderItem
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.StandingsGroup
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableRow
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvGameScreenModelTest {
    private val now = 10_000_000L
    private val home = Side("Dallas Stars", short = "DAL", record = "9-4-1", score = "2", color = "006847", altColor = "8f8f8c", rank = 7)
    private val away = Side("Buffalo Sabres", short = "BUF", record = "7-6-1", score = "0", color = "003087")

    private fun detail(
        blocks: List<com.nuvio.app.features.sportsdetail.api.GameBlock> = emptyList(),
        state: GameState = GameState.LIVE,
        live: LiveInfo? = LiveInfo(clock = "5:12", periodLabel = "2nd", period = 2),
        round: String? = null, h: Side = home, a: Side = away, sport: String = "Ice Hockey",
    ) = GameDetail(
        gameId = "espn:1", sport = sport, leagueId = "4380", leagueName = "NHL", round = round, state = state,
        header = TeamsHeader(h, a), live = live, blocks = blocks, asOf = mapOf("espn" to now - 5_000),
    )

    private fun play(id: String, text: String, side: String? = "home", time: String = "12:01") =
        TimelineItem(time = time, period = "2nd Period", side = side, text = text, kind = "goal", id = id, score = "2–0")

    private fun model(d: GameDetail, preview: Boolean = false, prev: GameDetail? = null, stale: Boolean = false) =
        TvGameScreenModel.from(d, now, preview, prev, stale)

    // --- Known Phase A items -------------------------------------------------------------------------------------

    @Test fun `a numeric round reads Round 7 not a bare 7`() {
        assertEquals("NHL · Round 7", model(detail(round = "7")).scoreboard.leagueLine)
        assertEquals("NHL · Quarterfinal", model(detail(round = "Quarterfinal")).scoreboard.leagueLine)
        assertEquals("NHL", model(detail(round = null)).scoreboard.leagueLine)
    }

    @Test fun `the clock appears once in the status lines`() {
        val d = detail(live = LiveInfo(clock = "5:12", periodLabel = "2nd", detail = "2nd Period - 5:12", period = 2))
        val s = model(d).scoreboard
        assertEquals("2nd · 5:12", s.statusMain)
        assertNull(s.statusSub, "a detail that repeats the clock adds nothing")
    }

    @Test fun `a detail that says something new is kept as the sub line`() {
        val s = model(detail(live = LiveInfo(clock = "5:12", periodLabel = "2nd", detail = "Power play", period = 2))).scoreboard
        assertEquals("Power play", s.statusSub)
    }

    @Test fun `halftime shows once`() {
        val s = model(detail(live = LiveInfo(clock = "45'", detail = "Halftime"), sport = "Soccer")).scoreboard
        assertEquals("Halftime", s.statusMain)
        assertNull(s.statusSub)
    }

    @Test fun `soccer draw percent reaches the card`() {
        val win = WinProbabilityBlock(homePercent = 40, drawPercent = 25, awayPercent = 35, source = "polymarket", asOfMs = now)
        val card = model(detail(blocks = listOf(win), sport = "Soccer")).winCard
        assertEquals(25, card?.chance?.drawPercent, "the draw segment must be drawn")
        assertEquals("Market-implied · via Polymarket", card?.chance?.caption)
    }

    // --- Scoreboard ------------------------------------------------------------------------------------------------

    @Test fun `rank and a contrast safe colour reach each side`() {
        val s = model(detail()).scoreboard
        assertEquals(7, s.home?.rank)
        assertNull(s.away?.rank)
        assertNotNull(s.home?.colorHex)
        assertEquals("Dallas Stars", s.home?.name)
        assertEquals("BUF", s.away?.shortName)
    }

    @Test fun `no colour in the feed gives null so Swift falls back to the theme`() {
        val s = model(detail(h = Side("A"), a = Side("B"))).scoreboard
        assertNull(s.home?.colorHex)
        assertNull(s.away?.colorHex)
    }

    // --- Last play, chips, panels ------------------------------------------------------------------------------------

    @Test fun `last play is the newest of the plays block`() {
        val d = detail(blocks = listOf(PlaysBlock(listOf(play("2", "Goal by Robertson"), play("1", "Faceoff")))))
        val lp = model(d).lastPlay
        assertEquals("Goal by Robertson", lp?.text)
        assertEquals("DAL", lp?.tag)
        assertEquals("2", lp?.key)
    }

    @Test fun `last play falls back to the newest scoring summary item`() {
        val d = detail(blocks = listOf(TimelineBlock(listOf(play("1", "old"), play("2", "newest")))))
        assertEquals("newest", model(d).lastPlay?.text)
    }

    @Test fun `no plays means no last play card`() {
        assertNull(model(detail()).lastPlay)
    }

    @Test fun `chips follow the sections and never include overview`() {
        val d = detail(blocks = listOf(
            PlaysBlock(listOf(play("1", "x"))),
            StatsBlock(listOf(StatRow("Shots", "20", "14"))),
            StandingsTableBlock(listOf(StandingsGroup("Central", listOf("W"), listOf(StandingsTableRow("1", "Dallas", listOf("9"), true))))),
        ))
        assertEquals(listOf(TvSection.PLAYS, TvSection.STATS, TvSection.STANDINGS), model(d).chips.map { it.section })
        assertEquals(listOf("Plays", "Stats", "Standings"), model(d).chips.map { it.title })
    }

    @Test fun `no blocks means no chips and the old situation line stays available`() {
        assertEquals(emptyList(), model(detail()).chips)
    }

    @Test fun `plays are capped 9 without a preview and 4 with one`() {
        val items = (1..20).map { play("$it", "p$it") }
        val d = detail(blocks = listOf(PlaysBlock(items)))
        assertEquals(9, model(d, preview = false).panels.plays.size)
        assertEquals(4, model(d, preview = true).panels.plays.size)
        assertEquals("p1", model(d).panels.plays.first().text, "newest first, as the block gives them")
    }

    @Test fun `stats leader honours lower is better and non numeric rows have no bar`() {
        val d = detail(blocks = listOf(StatsBlock(listOf(
            StatRow("Shots", "20", "10"), StatRow("Penalty Minutes", "4", "6"), StatRow("Power Play", "1/2", "0/1"), StatRow("Hits", "5", "5"),
        ))))
        val rows = model(d).panels.stats
        assertEquals("home", rows[0].leader)
        assertEquals(20f / 30f, rows[0].homeFraction)
        assertEquals("home", rows[1].leader, "fewer penalty minutes leads")
        assertNull(rows[2].homeFraction)
        assertNull(rows[2].leader)
        assertNull(rows[3].leader, "a tie has no leader")
    }

    @Test fun `stats are capped 6 and 3 with a preview and carry the by period table`() {
        val rows = (1..10).map { StatRow("S$it", "$it", "1") }
        val d = detail(blocks = listOf(StatsBlock(rows), LinescoreBlock(listOf("1", "2", "3", "T"), listOf(listOf("1", "1", "0", "2"), listOf("0", "0", "0", "0")), listOf("DAL", "BUF"))))
        assertEquals(6, model(d).panels.stats.size)
        assertEquals(3, model(d, preview = true).panels.stats.size)
        assertEquals(listOf("DAL", "BUF"), model(d).panels.byPeriod?.rowLabels)
    }

    @Test fun `the by period table hides periods that have not started`() {
        val ls = LinescoreBlock(listOf("1", "2", "3", "T"), listOf(listOf("1", "1", "", "2"), listOf("0", "0", "", "0")), listOf("DAL", "BUF"))
        val d = detail(blocks = listOf(StatsBlock(listOf(StatRow("Shots", "1", "1"))), ls), live = LiveInfo(clock = "5:12", periodLabel = "2nd", period = 2))
        assertEquals(listOf("1", "2", "T"), model(d).panels.byPeriod?.columns)
    }

    @Test fun `with no sections the by period table still reaches the right column`() {
        val ls = LinescoreBlock(listOf("1", "2", "T"), listOf(listOf("1", "1", "2"), listOf("0", "0", "0")), listOf("DAL", "BUF"))
        val m = model(detail(blocks = listOf(ls)))
        assertEquals(emptyList(), m.chips)
        assertEquals(listOf("1", "2", "T"), m.panels.byPeriod?.columns)
    }

    @Test fun `leaders are capped 4 and 2`() {
        val items = (1..8).map { LeaderItem("Goals", "P$it", "$it", "home") }
        val d = detail(blocks = listOf(LeadersBlock(items)))
        assertEquals(4, model(d).panels.leaders.size)
        assertEquals(2, model(d, preview = true).panels.leaders.size)
        assertEquals("DAL", model(d).panels.leaders.first().tag)
    }

    @Test fun `standings keep both game teams inside 7 rows and 3 with a preview`() {
        val rows = (1..16).map { StandingsTableRow("$it", "T$it", listOf("$it"), isGameTeam = it == 3 || it == 12) }
        val d = detail(blocks = listOf(StandingsTableBlock(listOf(StandingsGroup("All", listOf("W"), rows)))))
        val full = model(d).panels.standings
        assertEquals(7, full.sumOf { it.rows.size })
        assertEquals(2, full.sumOf { g -> g.rows.count { it.isGameTeam } })
        val small = model(d, preview = true).panels.standings
        assertEquals(3, small.sumOf { it.rows.size })
        assertEquals(2, small.sumOf { g -> g.rows.count { it.isGameTeam } })
    }

    @Test fun `a bare period number reads as the sport's own word`() {
        fun header(sport: String, period: String?): String? {
            val d = detail(sport = sport, blocks = listOf(PlaysBlock(listOf(play("1", "x").copy(period = period)))))
            return model(d).panels.plays.single().period
        }
        assertEquals("2nd half", header("Soccer", "2"))
        assertEquals("1st half", header("Soccer", "1"))
        assertEquals("Extra time", header("Soccer", "3"))
        assertEquals("1st period", header("Ice Hockey", "1"))
        assertEquals("3rd quarter", header("Basketball", "3"))
        assertEquals("4th quarter", header("American Football", "4"))
        assertEquals("2nd Period", header("Ice Hockey", "2nd Period"), "a feed label is kept as it is")
        assertNull(header("Ice Hockey", null))
    }

    // --- Moments -----------------------------------------------------------------------------------------------------

    @Test fun `opening the screen animates nothing`() {
        val m = model(detail())
        assertEquals(emptyList(), m.moments)
        assertNull(m.announcement)
    }

    @Test fun `an unchanged snapshot animates nothing`() {
        assertEquals(emptyList(), model(detail(), prev = detail()).moments)
    }

    @Test fun `a score change is one moment for the right side with an announcement`() {
        val prev = detail()
        val next = detail(h = home.copy(score = "3"))
        val m = model(next, prev = prev)
        assertEquals(listOf(TvMomentKind.SCORE_CHANGE), m.moments.map { it.kind })
        assertEquals("home", m.moments.single().side)
        assertEquals("Dallas Stars 3, Buffalo Sabres 0", m.announcement)
    }

    @Test fun `a win chance move is reported from and to`() {
        fun win(p: Int) = WinProbabilityBlock(homePercent = p, source = "espn")
        val m = model(detail(blocks = listOf(win(60))), prev = detail(blocks = listOf(win(55))))
        val mv = m.moments.single { it.kind == TvMomentKind.WIN_CHANCE_MOVE }
        assertEquals(55, mv.from)
        assertEquals(60, mv.to)
    }

    @Test fun `a new first play is a NewPlay moment once`() {
        val prev = detail(blocks = listOf(PlaysBlock(listOf(play("1", "Faceoff")))))
        val next = detail(blocks = listOf(PlaysBlock(listOf(play("2", "Shot"), play("1", "Faceoff")))))
        assertEquals(listOf(TvMomentKind.NEW_PLAY), model(next, prev = prev).moments.map { it.kind })
        assertEquals(emptyList(), model(next, prev = next).moments)
    }

    // --- Misc --------------------------------------------------------------------------------------------------------

    @Test fun `the win card carries the chart series and names`() {
        val hist = listOf(WinPoint(1, 50), WinPoint(2, 60), WinPoint(3, 70))
        val d = detail(blocks = listOf(WinProbabilityBlock(homePercent = 70, source = "polymarket", history = hist, asOfMs = now)))
        val c = model(d).winCard
        assertEquals(listOf(0.5f, 0.6f, 0.7f), c?.chart)
        assertEquals("DAL", c?.homeName)
        assertEquals("BUF", c?.awayName)
    }

    @Test fun `updated label appears when the data is old or a refresh failed`() {
        assertNull(model(detail()).updatedLabel)
        assertEquals("Updating…", model(detail().copy(asOf = emptyMap()), stale = true).updatedLabel)
        assertTrue(model(detail().copy(asOf = mapOf("espn" to now - 90_000))).updatedLabel!!.startsWith("Updated"))
    }
}
