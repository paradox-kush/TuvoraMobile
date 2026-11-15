package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.Storyline
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvWinChanceCardTest {
    private val now = 10_000_000L
    private fun detail(state: GameState = GameState.LIVE, block: WinProbabilityBlock) = GameDetail(
        gameId = "tsdb:1", sport = "Basketball", leagueId = "1", leagueName = "NBA", startsAt = "2026-11-07T15:00:00Z",
        state = state, header = TeamsHeader(Side("Denver", short = "DEN"), Side("Indiana", short = "IND")), blocks = listOf(block),
    )
    private fun card(block: WinProbabilityBlock, state: GameState = GameState.LIVE) =
        TvWinChanceCard.from(block, detail(state, block), now)

    @Test fun `a polymarket headline is captioned as market-implied via Polymarket`() {
        val c = card(WinProbabilityBlock(homePercent = 62, source = "polymarket", attribution = "via Polymarket", asOfMs = now))
        assertEquals("Market-implied · via Polymarket", c.caption, "the credit and the market-implied wording are Polymarket's condition")
        assertEquals(62, c.homePercent)
        assertEquals(38, c.awayPercent)
    }

    @Test fun `an ESPN headline is credited to ESPN not called market-implied`() {
        assertEquals("via ESPN", card(WinProbabilityBlock(homePercent = 55, source = "espn")).caption)
    }

    @Test fun `away is the provider value when given else the remainder after draw`() {
        val soccer = card(WinProbabilityBlock(homePercent = 50, drawPercent = 20, awayPercent = 30, source = "polymarket"))
        assertEquals(30, soccer.awayPercent)
        assertEquals(20, soccer.drawPercent)
        val derived = card(WinProbabilityBlock(homePercent = 50, drawPercent = 20, source = "polymarket"))
        assertEquals(30, derived.awayPercent)
    }

    @Test fun `a stale market value on a live game carries the card's own label`() {
        val c = card(WinProbabilityBlock(homePercent = 62, source = "polymarket", asOfMs = now - 5 * 60_000))
        assertEquals("Updated 5 min ago", c.staleLabel)
        assertNull(card(WinProbabilityBlock(homePercent = 62, source = "polymarket", asOfMs = now - 30_000)).staleLabel)
        assertNull(card(WinProbabilityBlock(homePercent = 62, source = "polymarket", asOfMs = now - 5 * 60_000), GameState.FINAL).staleLabel,
            "a finished game is not marked stale")
    }

    @Test fun `the card carries at most one extra line and a comeback outranks a storyline`() {
        val history = listOf(WinPoint(now - 600_000, 20), WinPoint(now - 300_000, 30), WinPoint(now - 10_000, 45))
        val storylines = listOf(Storyline("overtime", "Goes to overtime", 23), Storyline("x", "Both teams to score", 55))
        val c = card(WinProbabilityBlock(homePercent = 40, awayPercent = 60, source = "polymarket", history = history, storylines = storylines, asOfMs = now))
        assertEquals("Comeback watch · DEN +25 in 10 min", c.line)
    }

    @Test fun `without a comeback the first visible storyline is the one line`() {
        val storylines = listOf(Storyline("overtime", "Goes to overtime", 23), Storyline("x", "Both teams to score", 55))
        val c = card(WinProbabilityBlock(homePercent = 60, source = "polymarket", storylines = storylines, asOfMs = now))
        assertEquals("Goes to overtime · 23%", c.line)
    }

    @Test fun `no history and no storylines means no extra line`() {
        assertNull(card(WinProbabilityBlock(homePercent = 60, source = "polymarket")).line)
    }

    @Test fun `a storyline from another source than the headline is credited`() {
        val storylines = listOf(Storyline("overtime", "Goes to overtime", 23))
        val c = card(WinProbabilityBlock(homePercent = 60, source = "espn", storylines = storylines, extrasAttribution = "via Polymarket"))
        assertEquals("via ESPN · storyline via Polymarket", c.caption)
    }
}
