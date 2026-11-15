package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.BlockType
import com.nuvio.app.features.sportsdetail.internal.ChartMarker
import com.nuvio.app.features.sportsdetail.internal.ChartMarkers
import com.nuvio.app.features.sportsdetail.internal.EspnAssembler
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.GameMomentPolicy
import com.nuvio.app.features.sportsdetail.internal.Moment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase D: chart markers, period dividers, the chartMarkers switch and the remaining moments. */
class GameScreenMarkersDTest {
    private val t0 = 1_800_000_000_000L
    private fun series(n: Int, stepMs: Long = 30_000L, home: (Int) -> Int = { 40 + it % 30 }) = List(n) { WinPoint(t0 + it * stepMs, home(it)) }
    private fun play(atMs: Long, kind: String, text: String = "x", side: String? = "home", id: String = "p$atMs", period: String? = null) =
        TimelineItem(time = "1:00", period = period, side = side, text = text, kind = kind, id = id, wallclockMs = atMs)

    // ---- markers ---------------------------------------------------------------------------------------------------------------

    @Test fun `markers - a goal lands on the nearest series point and sits on the drawn line`() {
        val h = series(20)
        val m = ChartMarkers.markers(h, listOf(play(t0 + 10 * 30_000L + 4_000L, "goal")), "Ice Hockey").single()
        assertEquals("G", m.label); assertEquals("home", m.side)
        assertEquals(10f / 19f, m.x, 0.0001f)
        assertEquals(h[10].home / 100f, m.y, 0.0001f, "y is the series value at that point")
    }

    @Test fun `markers - an event with no series point within 90 seconds gets no marker and never an estimate`() {
        val sparse = series(10, stepMs = 300_000L)
        assertEquals(emptyList(), ChartMarkers.markers(sparse, listOf(play(t0 + 150_000L, "goal")), "Ice Hockey"))
        assertEquals(1, ChartMarkers.markers(sparse, listOf(play(t0 + 300_000L + 89_000L, "goal")), "Ice Hockey").size)
        assertEquals(emptyList(), ChartMarkers.markers(sparse, listOf(play(t0 + 300_000L + 91_000L, "goal")), "Ice Hockey"))
        assertEquals(1, ChartMarkers.markers(sparse, listOf(play(t0 + 150_000L, "goal")), "Ice Hockey", toleranceMs = 150_000L).size, "the tolerance is a parameter")
    }

    @Test fun `markers - a play with no wall clock never gets one`() {
        assertEquals(emptyList(), ChartMarkers.markers(series(20), listOf(play(t0, "goal").copy(wallclockMs = null)), "Ice Hockey"))
    }

    @Test fun `markers - at most max and the newest ones win`() {
        val h = series(100)
        val plays = (1..12).map { play(t0 + it * 6 * 30_000L, "goal", id = "g$it") }
        val m = ChartMarkers.markers(h, plays, "Ice Hockey")
        assertEquals(8, m.size)
        assertEquals((5..12).map { "g$it" }, m.map { it.playId }, "the 8 newest, left to right")
        assertEquals(5, ChartMarkers.markers(h, plays, "Ice Hockey", max = 5).size)
    }

    @Test fun `markers - labels per sport and what is not marked`() {
        val h = series(20)
        fun labels(sport: String, vararg p: TimelineItem) = ChartMarkers.markers(h, p.toList().mapIndexed { i, it -> it.copy(wallclockMs = t0 + (2 + i * 5) * 30_000L, id = "i$i") }, sport).map { it.label }
        assertEquals(listOf("TD", "FG"), labels("American Football", play(0, "score", "J.Doe pass for 12 yards TOUCHDOWN"), play(0, "score", "A.Kicker 42 yard field goal is GOOD")))
        assertEquals(emptyList(), labels("American Football", play(0, "score", "A.Kicker extra point is GOOD"), play(0, "penalty", "Holding")))
        assertEquals(listOf("G", "PPG"), labels("Ice Hockey", play(0, "goal"), play(0, "ppg")))
        assertEquals(listOf("G", "RC"), labels("Soccer", play(0, "goal"), play(0, "card", "Ismael Thiago (Brentford) is shown the red card.")))
        assertEquals(emptyList(), labels("Soccer", play(0, "card", "Kevin Schade (Brentford) is shown the yellow card."), play(0, "sub")))
        assertEquals(emptyList(), labels("Basketball", play(0, "score", "Layup")), "basketball has no markers")
        assertEquals(emptyList(), labels("Cricket", play(0, "goal")))
    }

    @Test fun `markers - the label sits above the line from 50 percent and below under it`() {
        assertTrue(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.5f, "G", "home")))
        assertTrue(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.9f, "G", "home")))
        assertFalse(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.49f, "G", "home")))
    }

    @Test fun `markers - a marker that would overlap a newer one is dropped`() {
        val h = series(100)
        val plays = listOf(play(t0 + 40 * 30_000L, "goal", id = "old"), play(t0 + 41 * 30_000L, "goal", id = "new"), play(t0 + 80 * 30_000L, "goal", id = "far"))
        assertEquals(listOf("new", "far"), ChartMarkers.markers(h, plays, "Ice Hockey").map { it.playId })
    }

    @Test fun `markers - on a long series the marker is matched on the full series then placed on the drawn one`() {
        val h = series(300) { 30 + it % 50 }
        val drawn = GameDetailUiPolicy.chartPoints(h)!!
        val m = ChartMarkers.markers(h, listOf(play(t0 + 151 * 30_000L, "goal")), "Ice Hockey").single()
        val idx = kotlin.math.round(m.x * (drawn.size - 1)).toInt()
        assertEquals(drawn[idx], m.y, 0.00001f, "the marker is ON the drawn polyline")
        assertTrue(kotlin.math.abs(m.x - 0.5f) < 0.03f, "halfway through the game, x=${m.x}")
    }

    @Test fun `markers - empty inputs`() {
        assertEquals(emptyList(), ChartMarkers.markers(emptyList(), listOf(play(t0, "goal")), "Ice Hockey"))
        assertEquals(emptyList(), ChartMarkers.markers(series(1), listOf(play(t0, "goal")), "Ice Hockey"))
        assertEquals(emptyList(), ChartMarkers.markers(series(20), emptyList(), "Ice Hockey"))
        assertEquals(emptyList(), ChartMarkers.dividers(emptyList(), listOf(play(t0, "periodStart", period = "2nd"))))
        assertEquals(emptyList(), ChartMarkers.dividers(series(20), emptyList()))
    }

    // ---- the switch ---------------------------------------------------------------------------------------------------------------

    private fun detailWith(switches: Set<String>, plays: PlaysBlock) = detailOf(WinProbabilityBlock(50, "polymarket", history = series(20)), plays, sport = "Ice Hockey").copy(uiSwitches = switches)

    @Test fun `switch - chartMarkers off removes markers and dividers and on brings them back`() {
        val plays = PlaysBlock(listOf(play(t0 + 5 * 30_000L, "goal")), "espn", chartItems = listOf(play(t0 + 5 * 30_000L, "goal"), play(t0 + 9 * 30_000L, "periodStart", period = "2nd")))
        assertEquals(emptyList(), ChartMarkers.markersFor(detailWith(emptySet(), plays)))
        assertEquals(emptyList(), ChartMarkers.dividersFor(detailWith(emptySet(), plays)))
        assertEquals(1, ChartMarkers.markersFor(detailWith(setOf("chartMarkers"), plays)).size)
        assertEquals(1, ChartMarkers.dividersFor(detailWith(setOf("chartMarkers"), plays)).size)
    }

    @Test fun `switch - ESPN only win probability has no series so no markers`() {
        val d = detailOf(WinProbabilityBlock(50, "espn"), PlaysBlock(emptyList(), "espn", chartItems = listOf(play(t0, "goal"))), sport = "Ice Hockey").copy(uiSwitches = setOf("chartMarkers"))
        assertEquals(emptyList(), ChartMarkers.markersFor(d))
    }

    // ---- dividers ----------------------------------------------------------------------------------------------------------------

    @Test fun `dividers - period starts become labelled x positions`() {
        val h = series(100)
        val d = ChartMarkers.dividers(h, listOf(play(t0 + 30 * 30_000L, "periodStart", period = "2nd"), play(t0 + 60 * 30_000L, "periodStart", period = "3rd")), "Ice Hockey")
        assertEquals(listOf("P2", "P3"), d.map { it.second })
        assertEquals(18f / 59f, d[0].first, 0.0001f, "on the drawn (60 point) series: full index 30 of 100 is drawn point 18 of 60")
    }

    @Test fun `dividers - a feed with only period ends draws nothing because an end is not a start`() {
        val h = series(100)
        val d = ChartMarkers.dividers(h, listOf(play(t0 + 25 * 30_000L, "periodEnd", period = "1st Quarter"), play(t0 + 50 * 30_000L, "periodEnd", period = "2nd Quarter")), "Basketball")
        assertEquals(emptyList(), d, "the end of Q2 is the start of halftime, not of Q3: no guess")
    }

    @Test fun `dividers - only period starts count and overtime reads OT`() {
        val h = series(100)
        val plays = listOf(
            play(t0 + 29 * 30_000L, "periodEnd", period = "1st"), play(t0 + 30 * 30_000L, "periodStart", period = "2nd"),
            play(t0 + 70 * 30_000L, "periodEnd", period = "3rd"), play(t0 + 75 * 30_000L, "periodStart", period = "4th"),
        )
        val d = ChartMarkers.dividers(h, plays, "Ice Hockey")
        assertEquals(listOf("P2", "OT"), d.map { it.second })
        assertEquals(18f / 59f, d[0].first, 0.0001f, "on the drawn (60 point) series: full index 30 of 100 is drawn point 18 of 60")
    }

    @Test fun `dividers - soccer reads 45 and 90 and a boundary outside the series or without a wall clock is skipped`() {
        val h = series(100)
        val d = ChartMarkers.dividers(h, listOf(play(t0 + 40 * 30_000L, "periodStart", period = "2nd Half"), play(t0 + 80 * 30_000L, "periodStart", period = "3rd Half")), "Soccer")
        assertEquals(listOf("45'", "90'"), d.map { it.second })
        assertEquals(emptyList(), ChartMarkers.dividers(h, listOf(play(t0 + 9_000 * 30_000L, "periodStart", period = "2nd")), "Ice Hockey"), "after the series")
        assertEquals(emptyList(), ChartMarkers.dividers(h, listOf(play(0, "periodStart", period = "2nd").copy(wallclockMs = null)), "Ice Hockey"))
        assertEquals(emptyList(), ChartMarkers.dividers(h, listOf(play(t0 + 5 * 30_000L, "periodStart", period = "1st")), "Ice Hockey"), "the start of the game is not a divider")
    }

    // ---- assembler: the full set of key events for the chart -------------------------------------------------------------------------

    private val nhlEvent = """{"id":"1","status":{"type":{"state":"in"}},"competitions":[{"competitors":[{"homeAway":"home","team":{"id":"10","displayName":"H"}},{"homeAway":"away","team":{"id":"20","displayName":"A"}}]}]}"""
    private fun nhlPlay(id: Int, type: String, text: String, period: Int, wall: String, team: String? = null, scoring: Boolean = false, strength: String? = null) =
        """{"id":"$id","type":{"text":"$type"},"text":"$text","period":{"number":$period,"displayValue":"${period}th"},"clock":{"displayValue":"1:00"},"wallclock":"$wall","scoringPlay":$scoring${team?.let { ",\"team\":{\"id\":\"$it\"}" } ?: ""}${strength?.let { ",\"strength\":{\"text\":\"$it\"}" } ?: ""}}"""

    @Test fun `plays block - chartItems keep every goal and period boundary even beyond the display cap`() {
        val plays = buildList {
            add(nhlPlay(1, "Period Start", "Start of 1st Period", 1, "2026-11-15T01:00:00Z"))
            add(nhlPlay(2, "Goal", "A Skater Goal (1)", 1, "2026-11-15T01:10:00Z", "10", true, "Even Strength"))
            add(nhlPlay(3, "Period End", "End of 1st Period", 1, "2026-11-15T01:20:00Z"))
            add(nhlPlay(4, "Period Start", "Start of 2nd Period", 2, "2026-11-15T01:40:00Z"))
            for (i in 5..80) add(nhlPlay(i, "Shot", "Shot $i", 2, "2026-11-15T02:00:00Z"))
            add(nhlPlay(81, "Goal", "B Skater Goal (2)", 2, "2026-11-15T02:10:00Z", "20", true, "Power Play"))
        }.joinToString(",")
        val b = EspnAssembler(bundledMapping()).assemble(BlockType.PLAYS, "hockey", parse(nhlEvent), parse("""{"plays":[$plays]}""")) as PlaysBlock
        assertEquals(60, b.items.size, "the display cap is unchanged")
        assertEquals(listOf("periodStart", "goal", "periodEnd", "periodStart", "ppg"), b.chartItems.map { it.kind }, "chronological, nothing shot-like")
        assertEquals(listOf("home", "away"), b.chartItems.mapNotNull { it.side })
        assertNotNull(b.chartItems.first().wallclockMs)
    }

    @Test fun `plays block - football scores and quarter ends and soccer goals red cards and halftime`() {
        val nfl = """{"drives":{"previous":[{"plays":[
            {"id":"1","type":{"text":"Passing Touchdown"},"text":"J.Doe pass to X for 12 yards TOUCHDOWN","period":{"number":1},"clock":{"displayValue":"9:00"},"wallclock":"2026-11-15T01:10:00Z","scoringPlay":true,"start":{"team":{"id":"10"}}},
            {"id":"2","type":{"text":"End Period"},"text":"END QUARTER 1","period":{"number":1},"clock":{"displayValue":"0:00"},"wallclock":"2026-11-15T01:20:00Z","scoringPlay":false},
            {"id":"3","type":{"text":"Rush"},"text":"run","period":{"number":2},"clock":{"displayValue":"14:00"},"wallclock":"2026-11-15T01:25:00Z","scoringPlay":false}]}]}}"""
        val fb = EspnAssembler(bundledMapping()).assemble(BlockType.PLAYS, "football", parse(nhlEvent), parse(nfl)) as PlaysBlock
        assertEquals(listOf("score", "periodEnd"), fb.chartItems.map { it.kind })
        val soccer = """{"commentary":[
            {"time":{"value":600.0,"displayValue":"10'"},"text":"Goal! 1-0","play":{"id":"a","type":{"text":"Goal"},"team":{"displayName":"H"},"period":{"number":1},"clock":{"displayValue":"10'"},"wallclock":"2026-11-15T01:10:00Z"}},
            {"time":{"value":1300.0,"displayValue":"22'"},"text":"Corner","play":{"id":"b","type":{"text":"Corner Awarded"},"team":{"displayName":"H"},"period":{"number":1},"clock":{"displayValue":"22'"},"wallclock":"2026-11-15T01:22:00Z"}},
            {"time":{"value":2400.0,"displayValue":"40'"},"text":"Player is shown the red card.","play":{"id":"c","type":{"text":"Red Card"},"team":{"displayName":"A"},"period":{"number":1},"clock":{"displayValue":"40'"},"wallclock":"2026-11-15T01:40:00Z"}},
            {"time":{"value":2700.0,"displayValue":"45'"},"text":"Halftime","play":{"id":"d","type":{"text":"Halftime"},"period":{"number":1},"clock":{"displayValue":"45'"},"wallclock":"2026-11-15T01:46:00Z"}}]}"""
        val sb = EspnAssembler(bundledMapping()).assemble(BlockType.PLAYS, "soccer", parse(nhlEvent), parse(soccer)) as PlaysBlock
        assertEquals(listOf("goal", "card", "periodEnd"), sb.chartItems.map { it.kind })
        assertEquals(listOf("home", "away", null), sb.chartItems.map { it.side })
        assertTrue(sb.items.none { it.kind == "periodEnd" }, "halftime is a chart boundary, not a row in the plays list")
    }

    @Test fun `mapping - chartMarkers is on in the bundled mapping`() {
        assertTrue(bundledMapping().switchOn("chartMarkers"))
        assertEquals(setOf("chartMarkers"), bundledMapping().uiSwitches)
    }

    // ---- moments (the rest of the table) ------------------------------------------------------------------------------------------

    private fun md(plays: PlaysBlock, switches: Set<String> = setOf("chartMarkers"), vararg extra: com.nuvio.app.features.sportsdetail.api.GameBlock) =
        detailOf(WinProbabilityBlock(50, "polymarket", history = series(40)), plays, *extra, sport = "Ice Hockey").copy(uiSwitches = switches)

    @Test fun `moments - a new chart marker pops once and only while the switch is on`() {
        val a = PlaysBlock(listOf(play(t0 + 5 * 30_000L, "goal", id = "g1")), "espn", chartItems = listOf(play(t0 + 5 * 30_000L, "goal", id = "g1")))
        val b = a.copy(chartItems = a.chartItems + play(t0 + 20 * 30_000L, "ppg", id = "g2", side = "away"))
        val d = GameMomentPolicy.diff(md(a), md(b)).filterIsInstance<Moment.MarkerAdded>()
        assertEquals(1, d.size)
        assertEquals("PPG", d.single().marker.label)
        assertEquals("g2", d.single().marker.playId)
        assertEquals(emptyList(), GameMomentPolicy.diff(md(a, emptySet()), md(b, emptySet())).filterIsInstance<Moment.MarkerAdded>(), "switch off")
        assertEquals(emptyList(), GameMomentPolicy.diff(md(b), md(b)).filterIsInstance<Moment.MarkerAdded>())
    }

    @Test fun `moments - a stat row changing and the leader flipping`() {
        fun d(rows: List<StatRow>) = md(PlaysBlock(emptyList(), "espn"), setOf("chartMarkers"), StatsBlock(rows, "espn"))
        val prev = d(listOf(StatRow("Shots", "10", "12"), StatRow("Turnovers", "5", "3"), StatRow("Possession", "55%", "45%")))
        val next = d(listOf(StatRow("Shots", "13", "12"), StatRow("Turnovers", "4", "4"), StatRow("Possession", "55%", "45%")))
        val m = GameMomentPolicy.diff(prev, next).filterIsInstance<Moment.StatChange>().associateBy { it.label }
        assertEquals(setOf("Shots", "Turnovers"), m.keys, "unchanged rows are quiet")
        assertTrue(m.getValue("Shots").leaderChanged, "12-10 became 12-13: home now leads")
        assertTrue(m.getValue("Turnovers").leaderChanged, "lower is better: home led 5-3 at the wrong end, now level")
    }

    @Test fun `moments - non numeric stats and a row that appears are not animated as a flip`() {
        fun d(rows: List<StatRow>) = md(PlaysBlock(emptyList(), "espn"), setOf("chartMarkers"), StatsBlock(rows, "espn"))
        val m = GameMomentPolicy.diff(d(listOf(StatRow("3rd down", "4/11", "5/9"))), d(listOf(StatRow("3rd down", "5/12", "5/9"), StatRow("Shots", "1", "0"))))
        assertEquals(listOf("3rd down"), m.filterIsInstance<Moment.StatChange>().map { it.label })
        assertFalse(m.filterIsInstance<Moment.StatChange>().single().leaderChanged)
    }

    // ---- Review fixes: classification --------------------------------------------------------------------------------------------------

    private fun footballPlays(vararg plays: String): PlaysBlock =
        EspnAssembler(bundledMapping()).assemble(BlockType.PLAYS, "football", parse(nhlEvent), parse("""{"drives":{"previous":[{"plays":[${plays.joinToString(",")}]}]}}""")) as PlaysBlock

    /** Real type text and text from feeds/football_nfl.json (NFL, 2026-11-12). */
    private val fgGood = """{"id":"4018730521007","type":{"id":"59","text":"Field Goal Good","abbreviation":"FG"},"text":"D.Stevens 46 yard field goal is GOOD, Center-T.Ott, Holder-T.Way.","period":{"number":2},"clock":{"displayValue":"1:00"},"scoringPlay":true,"wallclock":"2026-11-13T01:52:10Z","start":{"team":{"id":"10"}}}"""
    private val fgMissed = """{"id":"401873052643","type":{"id":"60","text":"Field Goal Missed","abbreviation":"FGM"},"text":"D.Zvada 33 yard field goal is No Good, Wide Left, Center-B.Mann, Holder-J.Stout.","period":{"number":1},"clock":{"displayValue":"2:00"},"scoringPlay":false,"wallclock":"2026-11-13T01:37:00Z","start":{"team":{"id":"20"}}}"""

    @Test fun `football - a made field goal is a score and gets an FG marker while a missed one is nothing`() {
        val b = footballPlays(fgGood, fgMissed)
        val good = b.items.single { it.id == "4018730521007" }
        val missed = b.items.single { it.id == "401873052643" }
        assertEquals("score", good.kind)
        assertNull(missed.kind, "a missed or blocked field goal is not a goal")
        assertEquals(listOf("score"), b.chartItems.map { it.kind })
        val h = series(20)
        val wall = good.wallclockMs!!
        val m = ChartMarkers.markers(List(20) { WinPoint(wall - 10 * 30_000L + it * 30_000L, 50) }, b.chartItems, "American Football")
        assertEquals(listOf("FG"), m.map { it.label })
        assertEquals(20, h.size)
    }

    private fun basketballPlays(vararg plays: String): PlaysBlock =
        EspnAssembler(bundledMapping()).assemble(BlockType.PLAYS, "basketball", parse(nhlEvent), parse("""{"plays":[${plays.joinToString(",")}]}""")) as PlaysBlock
    private fun bball(id: Int, type: String, scoring: Boolean) =
        """{"id":"$id","type":{"text":"$type"},"text":"play $id","period":{"displayValue":"1st Quarter"},"clock":{"displayValue":"9:00"},"scoringPlay":$scoring,"wallclock":"2026-11-15T01:1${id}:00Z","team":{"id":"10"},"homeScore":2,"awayScore":0}"""

    @Test fun `basketball - a made jump shot is a score not a shot and defensive goaltending is a score not a goal`() {
        val b = basketballPlays(bball(1, "Jump Shot", true), bball(2, "Defensive Goaltending", true), bball(3, "Layup Shot", false))
        val byId = b.items.associateBy { it.id }
        assertEquals("score", byId["1"]!!.kind)
        assertEquals("score", byId["2"]!!.kind)
        assertEquals("shot", byId["3"]!!.kind, "a missed shot stays a shot")
    }

    @Test fun `soccer - Start 2nd Half is a period start for the chart`() {
        val soccer = """{"commentary":[
            {"time":{"displayValue":"10'"},"text":"Goal! 1-0","play":{"id":"g","type":{"text":"Goal"},"team":{"displayName":"H"},"period":{"number":1},"clock":{"displayValue":"10'"},"wallclock":"2026-11-15T01:10:00Z"}},
            {"time":{"displayValue":"46'"},"text":"Second Half begins.","play":{"id":"s","type":{"text":"Start 2nd Half"},"period":{"number":2},"clock":{"displayValue":"46'"},"wallclock":"2026-11-15T02:05:00Z"}}]}"""
        val sb = EspnAssembler(bundledMapping()).assemble(BlockType.PLAYS, "soccer", parse(nhlEvent), parse(soccer)) as? PlaysBlock
        assertEquals(listOf("goal", "periodStart"), sb?.chartItems?.map { it.kind })
    }

    // ---- Review fixes: first down ------------------------------------------------------------------------------------------------------

    private fun fd(yard: Int, down: Int, dist: Int, side: String = "home") =
        detailOf(com.nuvio.app.features.sportsdetail.api.FootballFieldBlock(yard, side, down = down, distance = dist), sport = "American Football")

    @Test fun `moments - a first down gained on first down is a first down but a defensive penalty reset is not`() {
        val gained = GameMomentPolicy.diff(fd(30, 1, 10), fd(45, 1, 10))
        assertEquals(listOf<Moment>(Moment.FieldMove(30, 45, loss = false, firstDown = true)), gained)
        val penalty = GameMomentPolicy.diff(fd(30, 1, 10), fd(35, 1, 5))
        assertEquals(listOf<Moment>(Moment.FieldMove(30, 35, loss = false, firstDown = false)), penalty, "5 yards from a 1st and 10 is a penalty, not a first down")
    }
}
