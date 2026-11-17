package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.HighlightsBlock
import com.nuvio.app.features.sportsdetail.api.LeaderItem
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupPlayer
import com.nuvio.app.features.sportsdetail.api.LineupSide
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.StandingRow
import com.nuvio.app.features.sportsdetail.api.StandingsBlock
import com.nuvio.app.features.sportsdetail.api.StandingsGroup
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableRow
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.GameMomentPolicy
import com.nuvio.app.features.sportsdetail.internal.GameSection
import com.nuvio.app.features.sportsdetail.internal.GameSectionsPolicy
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.MotionSpec
import com.nuvio.app.features.sportsdetail.internal.StandingsWindow
import com.nuvio.app.features.sportsdetail.internal.TeamColor
import com.nuvio.app.features.sportsdetail.internal.platformReduceMotion
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameScreenPoliciesTest {
    private val play = TimelineItem(time = "5:12", period = "2nd Quarter", side = "home", text = "Layup", kind = "score", id = "p1")
    private val plays = PlaysBlock(listOf(play), "espn")
    private val timeline = TimelineBlock(listOf(play), "espn")
    private val stats = StatsBlock(listOf(StatRow("Rebounds", "40", "35")), "espn")
    private val leaders = LeadersBlock(listOf(LeaderItem("Points", "A. Player", "30", "home")), "espn")
    private val lineup = LineupBlock(listOf(LineupSide("home", "4-3-3", listOf(LineupPlayer("1", "K", "G", true)))), "espn")
    private val table = StandingsTableBlock(listOf(StandingsGroup("East", listOf("W", "L"), listOf(StandingsTableRow("1", "Home", listOf("5", "1"), true)))), "espn")
    private val linescore = LinescoreBlock(listOf("Q1", "T"), listOf(listOf("1", "1"), listOf("2", "2")), source = "espn")

    // ---- GameSectionsPolicy ------------------------------------------------------------------------------------

    @Test fun `sections - overview is always first and each present block opens its own section`() {
        val d = detailOf(linescore, plays, stats, leaders, lineup, table)
        assertEquals(listOf(GameSection.OVERVIEW, GameSection.PLAYS, GameSection.STATS, GameSection.LEADERS, GameSection.LINEUPS, GameSection.STANDINGS), GameSectionsPolicy.sections(d))
    }

    @Test fun `sections - a detail with no blocks is just the overview`() {
        assertEquals(listOf(GameSection.OVERVIEW), GameSectionsPolicy.sections(detailOf()))
    }

    @Test fun `sections - a section disappears when its block is empty`() {
        val d = detailOf(PlaysBlock(emptyList()), StatsBlock(emptyList()), LeadersBlock(emptyList()), StandingsTableBlock(emptyList()), LineupBlock(listOf(LineupSide("home", null, emptyList()))))
        assertEquals(listOf(GameSection.OVERVIEW), GameSectionsPolicy.sections(d))
    }

    @Test fun `sections - plays fall back to the scoring timeline and standings to the soccer table`() {
        val d = detailOf(timeline, StandingsBlock(listOf(StandingRow("1", "Home", "10", "25"))))
        assertEquals(listOf(GameSection.OVERVIEW, GameSection.PLAYS, GameSection.STANDINGS), GameSectionsPolicy.sections(d))
        assertEquals(listOf(timeline), GameSectionsPolicy.blocksFor(GameSection.PLAYS, d))
        val both = detailOf(plays, timeline)
        assertEquals(listOf(plays), GameSectionsPolicy.blocksFor(GameSection.PLAYS, both), "the full list wins over the scoring summary")
        assertTrue(GameSectionsPolicy.blocksFor(GameSection.OVERVIEW, both).none { it is TimelineBlock }, "and the summary is not repeated on Overview")
    }

    @Test fun `sections - win chance linescore form and highlights stay on the overview and stats stay off it`() {
        val win = WinProbabilityBlock(60, "espn")
        val d = detailOf(win, linescore, FormBlock(listOf("W"), listOf("L")), HighlightsBlock("https://x"), stats, plays)
        val overview = GameSectionsPolicy.blocksFor(GameSection.OVERVIEW, d)
        assertEquals(listOf(win, linescore), overview.take(2))
        assertEquals(4, overview.size)
        assertEquals(listOf(stats), GameSectionsPolicy.blocksFor(GameSection.STATS, d))
    }

    @Test fun `sections - the phone shows the tab row only with two or more sections`() {
        assertFalse(GameSectionsPolicy.showsTabRow(detailOf()))
        assertFalse(GameSectionsPolicy.showsTabRow(detailOf(linescore)))
        assertTrue(GameSectionsPolicy.showsTabRow(detailOf(stats)))
    }

    // ---- StandingsWindow ---------------------------------------------------------------------------------------

    private fun rows(prefix: String, n: Int, vararg gameIdx: Int) =
        (0 until n).map { StandingsTableRow("$prefix$it", "$prefix$it", listOf("$it"), it in gameIdx) }
    private fun block(vararg groups: StandingsGroup) = StandingsTableBlock(groups.toList())
    private fun StandingsTableBlock.gameTeams() = groups.flatMap { it.rows }.filter { it.isGameTeam }.map { it.teamId }
    private fun StandingsTableBlock.total() = groups.sumOf { it.rows.size }

    @Test fun `window - keeps both game teams in one group for max 3 5 and 7`() {
        val b = block(StandingsGroup("East", listOf("#"), rows("e", 16, 2, 13)))
        for (max in listOf(3, 5, 7)) {
            val w = StandingsWindow.window(b, max)
            assertEquals(listOf<String?>("e2", "e13"), w.gameTeams(), "max=$max")
            assertTrue(w.total() <= max, "max=$max rows=${w.total()}")
            assertTrue(w.groups.single().rows == w.groups.single().rows.sortedBy { it.name.drop(1).toInt() }, "original order kept")
        }
    }

    @Test fun `window - two groups each with one game team both stay visible and groups without a game team are dropped`() {
        val b = block(
            StandingsGroup("A", listOf("#"), rows("a", 10, 4)),
            StandingsGroup("Other", listOf("#"), rows("o", 10)),
            StandingsGroup("B", listOf("#"), rows("b", 10, 8)),
        )
        for (max in listOf(3, 5, 7)) {
            val w = StandingsWindow.window(b, max)
            assertEquals(listOf<String?>("a4", "b8"), w.gameTeams(), "max=$max")
            assertEquals(listOf("A", "B"), w.groups.map { it.name }, "max=$max")
            assertTrue(w.total() <= max, "max=$max total=${w.total()}")
        }
    }

    @Test fun `window - a short table is returned whole and the neighbours centre on the team`() {
        val small = block(StandingsGroup("A", listOf("#"), rows("a", 4, 1)))
        assertEquals(small, StandingsWindow.window(small, 7))
        val w = StandingsWindow.window(block(StandingsGroup("A", listOf("#"), rows("a", 20, 10))), 5)
        assertEquals(listOf("a8", "a9", "a10", "a11", "a12"), w.groups.single().rows.map { it.name })
        val top = StandingsWindow.window(block(StandingsGroup("A", listOf("#"), rows("a", 20, 0))), 5)
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4"), top.groups.single().rows.map { it.name }, "a window at the edge slides inward")
    }

    @Test fun `window - no game team found keeps the head of the first group rather than nothing`() {
        val w = StandingsWindow.window(block(StandingsGroup("A", listOf("#"), rows("a", 10))), 3)
        assertEquals(3, w.total())
    }

    // ---- GameMomentPolicy (Phase A) ----------------------------------------------------------------------------

    private val base = detailOf(WinProbabilityBlock(50, "espn"), plays, home = Side("Home", short = "HOM", score = "1"), away = Side("Away", short = "AWY", score = "0"))

    @Test fun `moments - no animation on open - null prev another game or a state change into LIVE`() {
        assertEquals(emptyList(), GameMomentPolicy.diff(null, base))
        assertEquals(emptyList(), GameMomentPolicy.diff(withScore(base.copy(gameId = "espn:2"), home = "5"), base))
        val upcoming = base.copy(state = GameState.UPCOMING)
        assertEquals(emptyList(), GameMomentPolicy.diff(upcoming, withScore(base, home = "9")))
    }

    @Test fun `moments - an unchanged snapshot yields nothing`() {
        assertEquals(emptyList(), GameMomentPolicy.diff(base, base.copy()))
    }

    @Test fun `moments - score 1 to 2 is one ScoreChange for the scoring side`() {
        assertEquals(listOf<Moment>(Moment.ScoreChange("home", "2")), GameMomentPolicy.diff(base, withScore(base, home = "2")))
        assertEquals(listOf<Moment>(Moment.ScoreChange("away", "3")), GameMomentPolicy.diff(base, withScore(base, away = "3")))
    }

    @Test fun `moments - win chance moves are reported with both ends`() {
        val next = base.copy(blocks = listOf(WinProbabilityBlock(61, "espn"), plays))
        assertEquals(listOf<Moment>(Moment.WinChanceMove(50, 61)), GameMomentPolicy.diff(base, next))
    }

    @Test fun `moments - a new newest play yields NewPlay once and a re-delivered play yields nothing`() {
        val newer = play.copy(id = "p2", text = "Dunk")
        val next = base.copy(blocks = listOf(WinProbabilityBlock(50, "espn"), PlaysBlock(listOf(newer, play), "espn")))
        assertEquals(listOf<Moment>(Moment.NewPlay(newer)), GameMomentPolicy.diff(base, next))
        assertEquals(emptyList(), GameMomentPolicy.diff(next, next.copy()))
    }

    @Test fun `moments - a first play appearing counts as new and the scoring timeline stands in without a plays block`() {
        val noPlays = base.copy(blocks = listOf(WinProbabilityBlock(50, "espn")))
        assertEquals(listOf<Moment>(Moment.NewPlay(play)), GameMomentPolicy.diff(noPlays, base))
        val tl = detailOf(TimelineBlock(listOf(play.copy(id = null)), "espn"))
        val tl2 = detailOf(TimelineBlock(listOf(play.copy(id = null), play.copy(id = null, text = "Another", time = "4:00")), "espn"))
        assertEquals(1, GameMomentPolicy.diff(tl, tl2).filterIsInstance<Moment.NewPlay>().size, "no ids: compare time and text")
    }

    // ---- TeamColor ------------------------------------------------------------------------------------------------

    private val surface = "14161c"
    private fun lum(hex: String): Double {
        fun ch(i: Int): Double { val c = hex.substring(i, i + 2).toInt(16) / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(0) + 0.7152 * ch(2) + 0.0722 * ch(4)
    }
    private fun contrast(a: String, b: String): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun `team colour - a dark purple is lightened to reach contrast 3 against the surface`() {
        assertTrue(contrast("461d7c", surface) < 3.0, "the raw colour really is too dark")
        val out = assertNotNull(TeamColor.forDark("461d7c", surface))
        assertTrue(contrast(out, surface) >= 3.0, "contrast ${contrast(out, surface)} for $out")
        assertNotEquals("461d7c", out)
        assertEquals(6, out.length)
    }

    @Test fun `team colour - an already readable colour is returned unchanged and bad input is null`() {
        assertEquals("f2b200", TeamColor.forDark("f2b200", surface))
        assertEquals("f2b200", TeamColor.forDark("F2B200", surface), "normalised to lower case")
        assertEquals("f2b200", TeamColor.forDark("#f2b200", surface), "a leading # is tolerated")
        assertNull(TeamColor.forDark(null, surface))
        assertNull(TeamColor.forDark("", surface))
        assertNull(TeamColor.forDark("zzzzzz", surface))
        assertNull(TeamColor.forDark("f2b2", surface))
    }

    private fun hsl(hex: String): Triple<Double, Double, Double> {
        val r = hex.substring(0, 2).toInt(16) / 255.0; val g = hex.substring(2, 4).toInt(16) / 255.0; val b = hex.substring(4, 6).toInt(16) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2; val d = mx - mn
        if (d == 0.0) return Triple(0.0, 0.0, l)
        val sat = d / (1 - kotlin.math.abs(2 * l - 1))
        val h = when (mx) { r -> ((g - b) / d).mod(6.0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        return Triple(h, sat, l)
    }

    @Test fun `team colour - black is lifted to the first lightness that has contrast 3`() {
        val out = assertNotNull(TeamColor.forDark("000000", surface))
        assertTrue(contrast(out, surface) >= 3.0, out)
        assertTrue(hsl(out).third < 0.6, "not pushed toward white: $out")
    }

    @Test fun `team colour - lifting never reaches white`() {
        val out = assertNotNull(TeamColor.forDark("a0a0a0", "ffffff"))
        assertTrue(hsl(out).third <= 0.86, out)
    }

    @Test fun `G5 - a navy keeps its hue and saturation instead of going grey`() {
        val (h0, s0, _) = hsl("03202f")
        val out = assertNotNull(TeamColor.forDark("03202f", surface))
        val (h1, s1, l1) = hsl(out)
        assertTrue(contrast(out, surface) >= 3.0, out)
        assertTrue(kotlin.math.abs(h1 - h0) < 6, "hue $h0 -> $h1 ($out)")
        assertTrue(s1 > s0 * 0.85, "saturation $s0 -> $s1 ($out)")
        assertTrue(l1 < 0.6, out)
    }

    @Test fun `G5 - the LSU purple lightens to a purple and the Chelsea blue to a blue`() {
        for ((hex, alt) in listOf("461d7c" to "fdd023", "034694" to "ee242c")) {
            val (h0, s0, _) = hsl(hex)
            val out = assertNotNull(TeamColor.forDark(hex, surface, alt))
            val (h1, s1, _) = hsl(out)
            assertTrue(contrast(out, surface) >= 3.0, out)
            assertTrue(kotlin.math.abs(h1 - h0) < 6, "$hex stays its hue, got $out")
            assertTrue(s1 > s0 * 0.85, "$hex stays saturated, got $out")
        }
    }

    @Test fun `G5 - a primary that needs heavy lightening yields to an alt that already reads`() {
        // IND: navy primary, light grey alt that passes contrast as it is.
        assertEquals("a2aaad", TeamColor.forDark("002c5f", surface, "a2aaad"))
        // HOU: navy primary, red alt that does NOT reach contrast 3 -> the lightened navy stays.
        val hou = assertNotNull(TeamColor.forDark("03202f", surface, "a71930"))
        assertNotEquals("a71930", hou)
        assertTrue(contrast(hou, surface) >= 3.0)
    }

    @Test fun `G5 - HOU and IND stay visibly different from each other`() {
        val (h, a) = TeamColor.pair(Side("Texans", color = "03202f", altColor = "a71930"), Side("Colts", color = "002c5f", altColor = "a2aaad"), surface)
        assertNotNull(h); assertNotNull(a)
        assertNotEquals(h, a)
    }

    @Test fun `team colour - near identical colours push the away side to its alternate colour`() {
        val home = Side("H", color = "d00000", altColor = "ffffff")
        val away = Side("A", color = "d10101", altColor = "2255ff")
        val (h, a) = TeamColor.pair(home, away, surface)
        assertEquals(TeamColor.forDark("d00000", surface), h)
        assertEquals(TeamColor.forDark("2255ff", surface), a)
        val (_, none) = TeamColor.pair(home, away.copy(altColor = null), surface)
        assertNull(none, "no usable alternate: the caller falls back to the theme")
    }

    @Test fun `team colour - distinct colours are kept as they are and a missing side stays null`() {
        val (h, a) = TeamColor.pair(Side("H", color = "d00000"), Side("A", color = "00aa00"), surface)
        assertEquals(TeamColor.forDark("d00000", surface), h)
        assertEquals(TeamColor.forDark("00aa00", surface), a)
        assertEquals(null to null, TeamColor.pair(Side("H"), Side("A"), surface))
    }

    // ---- MotionSpec / reduce motion / lowerIsBetter --------------------------------------------------------------

    @Test fun `motion spec values are the material3 tokens`() {
        assertEquals(listOf(200, 500, 600, 700), listOf(MotionSpec.QUICK_MS, MotionSpec.MOVE_MS, MotionSpec.COUNT_MS, MotionSpec.PULSE_MS))
    }

    @Test fun `reduce motion can be read on every runner`() {
        val v: Boolean = platformReduceMotion()
        assertTrue(v || !v)
    }

    @Test fun `lower is better rows flip the leader`() {
        for (l in listOf("Turnovers", "turnovers", "Fouls", "Penalty Minutes", "Giveaways", "Personal Fouls", "Penalties")) assertTrue(GameDetailUiPolicy.lowerIsBetter(l), l)
        for (l in listOf("Rebounds", "Total Yards", "Possession", "Shots", "Takeaways", "Blocked Shots")) assertFalse(GameDetailUiPolicy.lowerIsBetter(l), l)
    }
}
