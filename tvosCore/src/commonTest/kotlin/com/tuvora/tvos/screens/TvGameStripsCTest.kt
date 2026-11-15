package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.ScoringRun
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvGameStripsCTest {
    private val now = 10_000_000L
    private val dal = Side("Dallas Mavericks", short = "DAL", color = "00538c", score = "88")
    private val bos = Side("Boston Celtics", short = "BOS", color = "007a33", score = "85")

    private fun game(sport: String, vararg blocks: GameBlock, state: GameState = GameState.LIVE) =
        GameDetail(gameId = "espn:c", sport = sport, leagueId = "1", leagueName = "L", state = state, header = TeamsHeader(dal, bos), blocks = blocks.toList())

    private fun model(d: GameDetail, prev: GameDetail? = null) = TvGameScreenModel.from(d, now, false, prev, false)

    // --- Basketball ------------------------------------------------------------------------------------------------

    @Test fun `fouls timeouts and possession pass straight through`() {
        val s = model(game("Basketball", BasketballStripBlock(homeFouls = 3, awayFouls = 6, homeTimeouts = 5, awayTimeouts = 2, possession = "away"))).basketball
        assertNotNull(s)
        assertEquals(3, s.homeFouls)
        assertEquals(6, s.awayFouls)
        assertEquals(5, s.homeTimeouts)
        assertEquals(2, s.awayTimeouts)
        assertEquals("away", s.possession)
    }

    @Test fun `the bonus reads as team in the bonus and never prints the raw value`() {
        fun label(h: String?, a: String?) = model(game("Basketball", BasketballStripBlock(homeFouls = 1, homeBonus = h, awayBonus = a))).basketball?.bonusLabel
        assertEquals("DAL in the bonus", label("DOUBLE", "NONE"))
        assertEquals("BOS in the bonus", label("NONE", "SINGLE"))
        assertEquals("Both teams in the bonus", label("DOUBLE", "DOUBLE"))
        assertNull(label("NONE", "NONE"))
        assertNull(label(null, null))
    }

    @Test fun `a run shows only when core delivered one`() {
        val on = model(game("Basketball", BasketballStripBlock(homeFouls = 1, run = ScoringRun("home", 10)))).basketball
        assertEquals("DAL 10-0 run", on?.runLabel)
        assertNull(model(game("Basketball", BasketballStripBlock(homeFouls = 1, run = null))).basketball?.runLabel)
    }

    @Test fun `a basketball block with nothing in it draws nothing`() {
        assertNull(model(game("Basketball", BasketballStripBlock())).basketball)
        assertNull(model(game("Basketball")).basketball)
    }

    // --- Hockey ------------------------------------------------------------------------------------------------------

    @Test fun `even strength is said plainly`() {
        val h = model(game("Ice Hockey", HockeyStripBlock(powerPlaySide = null, homeShots = 20, awayShots = 14))).hockey
        assertEquals("Even strength", h?.chipLabel)
        assertFalse(h?.onPowerPlay ?: true)
        assertNull(h?.timeLeftText)
        assertNull(h?.fractionLeft)
    }

    @Test fun `a power play names the team and has no time without the derived clock`() {
        val h = model(game("Ice Hockey", HockeyStripBlock(powerPlaySide = "home", homeShots = 1))).hockey
        assertEquals("Power play · DAL", h?.chipLabel)
        assertTrue(h?.onPowerPlay ?: false)
        assertEquals("home", h?.powerPlaySide)
        assertNull(h?.timeLeftText)
        assertNull(h?.fractionLeft)
    }

    @Test fun `the clock shows m ss and the fraction of the penalty that is left`() {
        val h = model(game("Ice Hockey", HockeyStripBlock(powerPlaySide = "away", powerPlaySecondsLeft = 72))).hockey
        assertEquals("1:12", h?.timeLeftText)
        assertEquals(0.6f, h?.fractionLeft)
        val dbl = model(game("Ice Hockey", HockeyStripBlock(powerPlaySide = "away", powerPlaySecondsLeft = 180))).hockey
        assertEquals("3:00", dbl?.timeLeftText)
        assertEquals(0.75f, dbl?.fractionLeft, "over a minor the bar is measured against a double minor")
    }

    @Test fun `shots power plays and faceoffs are hidden when missing`() {
        val full = model(game("Ice Hockey", HockeyStripBlock(homeShots = 20, awayShots = 14, homePowerPlay = "1/2", awayPowerPlay = "0/1", homeFaceoffs = 14, awayFaceoffs = 11))).hockey
        assertEquals("1/2 – 0/1", full?.powerPlayText)
        assertEquals(20, full?.homeShots)
        assertEquals(11, full?.awayFaceoffs)
        val none = model(game("Ice Hockey", HockeyStripBlock(powerPlaySide = "home"))).hockey
        assertNull(none?.powerPlayText)
        assertNull(none?.homeShots)
        assertNull(none?.homeFaceoffs)
    }

    @Test fun `a goal the feed marked ppg is tagged and others are not`() {
        fun play(kind: String) = TimelineItem(time = "5:00", period = "2", side = "home", text = "Goal", kind = kind, id = kind)
        val rows = model(game("Ice Hockey", PlaysBlock(listOf(play("ppg"), play("goal"))))).panels.plays
        assertTrue(rows[0].ppg)
        assertFalse(rows[1].ppg)
    }

    // --- Moments -----------------------------------------------------------------------------------------------------

    @Test fun `a new foul a bonus and a timeout are moments`() {
        val a = game("Basketball", BasketballStripBlock(homeFouls = 3, homeTimeouts = 5, homeBonus = "NONE"))
        val b = game("Basketball", BasketballStripBlock(homeFouls = 4, homeTimeouts = 4, homeBonus = "DOUBLE"))
        val kinds = model(b, a).moments.map { it.kind }
        assertTrue(TvMomentKind.FOUL_COUNT in kinds)
        assertTrue(TvMomentKind.BONUS_REACHED in kinds)
        assertTrue(TvMomentKind.TIMEOUT_USED in kinds)
        assertEquals(4, model(b, a).moments.first { it.kind == TvMomentKind.FOUL_COUNT }.to)
    }

    @Test fun `a power play starting and ending are two different moments`() {
        val even = game("Ice Hockey", HockeyStripBlock(powerPlaySide = null))
        val pp = game("Ice Hockey", HockeyStripBlock(powerPlaySide = "home"))
        assertEquals("home", model(pp, even).moments.single { it.kind == TvMomentKind.POWER_PLAY_START }.side)
        assertTrue(model(even, pp).moments.any { it.kind == TvMomentKind.POWER_PLAY_END })
        assertEquals(emptyList(), model(pp, pp).moments)
    }
}
