package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.FieldDrive
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupPlayer
import com.nuvio.app.features.sportsdetail.api.LineupSide
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SoccerEvent
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvGameStripsBTest {
    private val now = 10_000_000L
    private val nyg = Side("New York Giants", short = "NYG", color = "0b2265")
    private val wsh = Side("Washington Commanders", short = "WSH", color = "5a1414")

    private fun football(block: FootballFieldBlock?, state: GameState = GameState.LIVE, h: Side = nyg, a: Side = wsh, extra: List<GameBlock> = emptyList()) =
        GameDetail(gameId = "espn:f", sport = "American Football", leagueId = "1", leagueName = "NFL", state = state,
            header = TeamsHeader(h, a), blocks = listOfNotNull<GameBlock>(block) + extra)

    private fun model(d: GameDetail, prev: GameDetail? = null) = TvGameScreenModel.from(d, now, false, prev, false)

    // --- Field strip ------------------------------------------------------------------------------------------------

    @Test fun `home ball at the 25 with 1st and 10`() {
        val f = model(football(FootballFieldBlock(yardLine = 25, possession = "home", down = 1, distance = 10,
            line = "1st & 10 at NYG 25", drive = FieldDrive(startYardLine = 20, summary = "3 plays, 5 yards, 0:54")))).field
        assertNotNull(f)
        assertEquals(0.25f, f.ballX)
        assertEquals(0.35f, f.firstDownX)
        assertEquals(true, f.attackingRight)
        assertEquals("NYG ball", f.ballLabel)
        assertEquals(0.20f, f.driveFrom)
        assertEquals(0.25f, f.driveTo)
        assertEquals("1st & 10 at NYG 25", f.line)
        assertEquals("3 plays, 5 yards, 0:54", f.driveSummary)
    }

    @Test fun `away ball at 58 with 2nd and 5 gains toward the home goal`() {
        val f = model(football(FootballFieldBlock(yardLine = 58, possession = "away", down = 2, distance = 5))).field
        assertEquals(0.58f, f?.ballX)
        assertEquals(0.53f, f?.firstDownX)
        assertEquals(false, f?.attackingRight)
        assertEquals("WSH ball", f?.ballLabel)
    }

    @Test fun `goal to go has no first down line`() {
        assertNull(model(football(FootballFieldBlock(yardLine = 95, possession = "home", down = 1, distance = 10))).field?.firstDownX)
    }

    @Test fun `unknown possession draws no arrow no label and no drive shading`() {
        val f = model(football(FootballFieldBlock(yardLine = 50, possession = null, down = 1, distance = 10, drive = FieldDrive(startYardLine = 40)))).field
        assertNull(f?.attackingRight)
        assertNull(f?.ballLabel)
        assertNull(f?.driveFrom)
        assertNull(f?.firstDownX)
    }

    @Test fun `red zone border takes the possessing team colour and is null otherwise`() {
        val red = model(football(FootballFieldBlock(yardLine = 90, possession = "home", redZone = true))).field
        assertNotNull(red?.borderHex)
        assertEquals(red?.driveTintHex, red?.borderHex)
        assertNull(model(football(FootballFieldBlock(yardLine = 50, possession = "home", redZone = false))).field?.borderHex)
    }

    @Test fun `timeouts pass through for the dots`() {
        val f = model(football(FootballFieldBlock(yardLine = 50, possession = "home", homeTimeouts = 3, awayTimeouts = 1))).field
        assertEquals(3, f?.homeTimeouts)
        assertEquals(1, f?.awayTimeouts)
    }

    @Test fun `the field strip is live only and absent without a block`() {
        assertNull(model(football(FootballFieldBlock(yardLine = 50, possession = "home"), state = GameState.FINAL)).field)
        assertNull(model(football(null)).field)
    }

    // --- Soccer strip ------------------------------------------------------------------------------------------------

    private val ars = Side("Arsenal", short = "ARS", color = "ef0107", score = "2")
    private val che = Side("Chelsea", short = "CHE", color = "034694", score = "1")
    private fun soccer(block: SoccerStripBlock?, state: GameState = GameState.LIVE, extra: List<GameBlock> = emptyList()) =
        GameDetail(gameId = "espn:s", sport = "Soccer", leagueId = "1", leagueName = "EPL", state = state,
            header = TeamsHeader(ars, che), blocks = listOfNotNull<GameBlock>(block) + extra)

    @Test fun `a goal chip is filled and names the team and minute`() {
        val s = model(soccer(SoccerStripBlock(lastEvent = SoccerEvent("goal", "home", "23'", "Goal! Saka")))).soccer
        assertEquals(TvEventStyle.GOAL, s?.chip?.style)
        assertEquals("GOAL", s?.chip?.label)
        assertEquals("ARS", s?.chip?.team)
        assertEquals("23'", s?.chip?.minute)
    }

    @Test fun `card chips use their own style and other events are neutral`() {
        fun style(kind: String) = model(soccer(SoccerStripBlock(lastEvent = SoccerEvent(kind, "away", "50'", "x")))).soccer?.chip?.style
        assertEquals(TvEventStyle.YELLOW, style("yellow"))
        assertEquals(TvEventStyle.RED, style("red"))
        assertEquals(TvEventStyle.NEUTRAL, style("corner"))
        assertEquals(TvEventStyle.NEUTRAL, style("sub"))
    }

    @Test fun `possession corners and cards come from the strip and no event means no chip`() {
        val s = model(soccer(SoccerStripBlock(lastEvent = null, homePossession = 56, homeCorners = 4, awayCorners = 2, homeYellow = 1, awayYellow = 2, awayRed = 1), GameState.FINAL)).soccer
        assertNull(s?.chip)
        assertEquals(56, s?.homePossession)
        assertEquals(44, s?.awayPossession)
        assertEquals(4, s?.homeCorners)
        assertEquals(2, s?.awayYellow)
        assertEquals(1, s?.awayRed)
    }

    @Test fun `scorers sit under the team names capped at 3 lines then plus N`() {
        val d = soccer(SoccerStripBlock(homeScorers = listOf("Saka 23'", "Odegaard 41'", "Havertz 60'", "Rice 70'", "Jesus 80'"), awayScorers = listOf("Palmer 41' (pen)")))
        val b = model(d).scoreboard
        assertEquals(listOf("Saka 23'", "Odegaard 41'", "Havertz 60'", "+2"), b.home?.scorers)
        assertEquals(listOf("Palmer 41' (pen)"), b.away?.scorers)
    }

    @Test fun `no soccer block means no strip and no scorers`() {
        val m = model(soccer(null))
        assertNull(m.soccer)
        assertEquals(emptyList(), m.scoreboard.home?.scorers)
    }

    // --- Pitch lineups -----------------------------------------------------------------------------------------------

    private fun xi(places: IntRange = 1..11) = places.map { LineupPlayer(number = "$it", name = "P$it", pos = "M", starter = true, place = it) }
    private fun lineup(home: List<LineupPlayer>, away: List<LineupPlayer>, hf: String? = "4-3-3", af: String? = "4-4-2") =
        LineupBlock(listOf(LineupSide("home", hf, home), LineupSide("away", af, away)))

    @Test fun `two 4-3-3 sides put 22 dots home left and away right`() {
        val p = model(soccer(null, extra = listOf(lineup(xi(), xi(), af = "4-3-3")))).pitch
        assertNotNull(p)
        assertEquals(22, p.spots.size)
        assertTrue(p.spots.filter { it.isHome }.all { it.x <= 0.5f }, "home occupies the left half")
        assertTrue(p.spots.filter { !it.isHome }.all { it.x >= 0.5f }, "away occupies the right half")
        assertEquals("4-3-3", p.homeFormation)
    }

    @Test fun `a side without places or with an unknown formation falls back to the list`() {
        assertNull(model(soccer(null, extra = listOf(lineup(xi(), xi(1..10))))).pitch)
        assertNull(model(soccer(null, extra = listOf(lineup(xi(), xi(), hf = null)))).pitch)
    }

    // --- Moments -----------------------------------------------------------------------------------------------------

    @Test fun `the ball moving forward is one field move from the old spot`() {
        val a = football(FootballFieldBlock(yardLine = 25, possession = "home", down = 1, distance = 10))
        val b = football(FootballFieldBlock(yardLine = 40, possession = "home", down = 1, distance = 10))
        val m = model(b, a).moments.single { it.kind == TvMomentKind.FIELD_MOVE }
        assertEquals(25, m.from)
        assertEquals(40, m.to)
        assertFalse(m.loss)
        assertTrue(m.firstDown is Boolean)
    }

    @Test fun `a loss and a new first down are flagged`() {
        val a = football(FootballFieldBlock(yardLine = 40, possession = "home", down = 2, distance = 6))
        val loss = model(football(FootballFieldBlock(yardLine = 35, possession = "home", down = 3, distance = 11)), a).moments.single { it.kind == TvMomentKind.FIELD_MOVE }
        assertTrue(loss.loss)
        val first = model(football(FootballFieldBlock(yardLine = 48, possession = "home", down = 1, distance = 10)), a).moments.single { it.kind == TvMomentKind.FIELD_MOVE }
        assertTrue(first.firstDown)
        assertFalse(first.loss)
    }

    @Test fun `a possession flip is one PossessionChange and no slide`() {
        val a = football(FootballFieldBlock(yardLine = 40, possession = "home", down = 4, distance = 2))
        val b = football(FootballFieldBlock(yardLine = 40, possession = "away", down = 1, distance = 10))
        val kinds = model(b, a).moments.map { it.kind }
        assertTrue(TvMomentKind.POSSESSION_CHANGE in kinds)
        assertFalse(TvMomentKind.FIELD_MOVE in kinds)
    }

    @Test fun `six points for the side with the ball is a touchdown`() {
        val a = football(FootballFieldBlock(yardLine = 97, possession = "home"), h = nyg.copy(score = "7"), a = wsh.copy(score = "3"))
        val b = football(FootballFieldBlock(yardLine = 25, possession = "away"), h = nyg.copy(score = "13"), a = wsh.copy(score = "3"))
        assertTrue(model(b, a).moments.any { it.kind == TvMomentKind.TOUCHDOWN && it.side == "home" })
    }

    @Test fun `a new soccer last event is one soccer moment`() {
        val a = soccer(SoccerStripBlock(lastEvent = SoccerEvent("yellow", "away", "50'", "Yellow")))
        val b = soccer(SoccerStripBlock(lastEvent = SoccerEvent("goal", "home", "55'", "Goal")))
        val m = model(b, a).moments.single { it.kind == TvMomentKind.SOCCER_EVENT }
        assertEquals("home", m.side)
        assertEquals(emptyList(), model(b, b).moments)
    }

    // --- Left column budget ------------------------------------------------------------------------------------------

    @Test fun `a roomy left column keeps everything`() {
        assertEquals(TvLeftFit(showChart = true, showLastPlay = true), TvLeftColumnFit.fit(0, 0, hasLine = false, hasChart = true, hasLastPlay = true))
    }

    @Test fun `the chart is the first thing dropped`() {
        val f = TvLeftColumnFit.fit(stripDp = 78, scorerLines = 3, hasLine = true, hasChart = true, hasLastPlay = true)
        assertFalse(f.showChart)
        assertTrue(f.showLastPlay)
    }

    @Test fun `the last play goes only after the chart`() {
        val f = TvLeftColumnFit.fit(stripDp = 200, scorerLines = 3, hasLine = true, hasChart = true, hasLastPlay = true)
        assertFalse(f.showChart)
        assertFalse(f.showLastPlay)
    }

    @Test fun `things that are not there never show`() {
        val f = TvLeftColumnFit.fit(0, 0, hasLine = false, hasChart = false, hasLastPlay = false)
        assertFalse(f.showChart)
        assertFalse(f.showLastPlay)
    }
}
