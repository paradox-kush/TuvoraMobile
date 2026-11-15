package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.ScoringRun
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.internal.ui.BasketballStripModel
import com.nuvio.app.features.sportsdetail.internal.ui.HockeyStripModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StripModelsTest {
    private val header = TeamsHeader(Side("Detroit", "DET"), Side("Miami", "MIA"))

    @Test fun `basketball bonus label names the team in the bonus never the raw value`() {
        val m = BasketballStripModel.of(BasketballStripBlock(homeBonus = "NONE", awayBonus = "DOUBLE"), header)
        assertEquals("MIA in the bonus", m.bonusLabel, "away is in the bonus")
    }

    @Test fun `basketball without any field is empty and run shows only when given`() {
        assertTrue(BasketballStripModel.of(BasketballStripBlock(), header).isEmpty, "all missing hides the strip")
        assertEquals("DET 9-0 run", BasketballStripModel.of(BasketballStripBlock(run = ScoringRun("home", 9)), header).runLabel, "run label")
        assertNull(BasketballStripModel.of(BasketballStripBlock(homeFouls = 2), header).runLabel, "no run no chip")
    }

    @Test fun `foul number appears only above five dots`() {
        assertNull(BasketballStripModel.foulNumber(5), "five fits the dots")
        assertEquals("7", BasketballStripModel.foulNumber(7), "seven shows a number")
    }

    @Test fun `hockey even strength has no time and no bar`() {
        val m = HockeyStripModel.of(HockeyStripBlock(homeShots = 20, awayShots = 14), header)
        assertEquals("Even strength", m.chip, "no power play")
        assertNull(m.timeLeft, "no time")
        assertNull(m.barFraction, "no bar")
        assertEquals(20 to 14, m.shotsHA, "numbers for the count-up")
        assertEquals("Shots 20 – 14", m.shots, "shots")
    }

    @Test fun `hockey power play without the clock shows the chip only`() {
        val m = HockeyStripModel.of(HockeyStripBlock(powerPlaySide = "home"), header)
        assertEquals("Power play · DET", m.chip, "chip with team")
        assertNull(m.timeLeft, "clock switch off")
        assertNull(m.barFraction, "no bar without a time")
    }

    @Test fun `hockey power play with the clock shows time and bar`() {
        val m = HockeyStripModel.of(HockeyStripBlock(powerPlaySide = "away", powerPlaySecondsLeft = 72), header)
        assertEquals("1:12 left", m.timeLeft, "mm:ss")
        assertEquals(0.6f, m.barFraction!!, 0.001f, "72 of 120")
        assertNull(HockeyStripModel.of(HockeyStripBlock(powerPlaySide = "away", powerPlaySecondsLeft = 150), header).barFraction, "above 2:00 the length is unknown: no bar, no rescale")
        assertEquals("2:30 left", HockeyStripModel.of(HockeyStripBlock(powerPlaySide = "away", powerPlaySecondsLeft = 150), header).timeLeft, "time still shown")
        assertEquals("Power play · MIA", m.chip, "chip")
    }

    @Test fun `hockey rows hide when a side is missing`() {
        val m = HockeyStripModel.of(HockeyStripBlock(homeFaceoffs = 14), header)
        assertNull(m.faceoffs, "only one side")
        assertNull(m.powerPlays, "none")
    }
}
