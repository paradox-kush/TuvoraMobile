package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.HighlightsBlock
import com.nuvio.app.features.sportsdetail.api.LeaderItem
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameDetailUiPolicyTest {
    private val now = 1_000_000_000L

    @Test fun `no chip while the data is fresh`() {
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 5_000), now))
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 20_000), now))
        assertNull(GameDetailUiPolicy.staleLabel(emptyMap(), now))
    }

    @Test fun `chip tracks the FRESHEST source fetch and shows past 20 s`() {
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 21_000, "tsdb" to now - 2_000), now), "a fresh source keeps the chip away")
        assertEquals("Updated 21s ago", GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 21_000, "tsdb" to now - 90_000), now))
        assertEquals("Updated 2 min ago", GameDetailUiPolicy.staleLabel(mapOf("tsdb" to now - 125_000, "espn" to now - 300_000), now))
        assertEquals("Updated 3 h ago", GameDetailUiPolicy.staleLabel(mapOf("tsdb" to now - 3 * 3_600_000L - 5), now))
    }

    @Test fun `a clock that went backwards never shows a negative age`() {
        assertNull(GameDetailUiPolicy.staleLabel(mapOf("espn" to now + 60_000), now))
    }

    @Test fun `empty blocks hide`() {
        assertTrue(GameDetailUiPolicy.isEmpty(StatsBlock(emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(LinescoreBlock(emptyList(), emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(LinescoreBlock(listOf("Q1"), emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(TimelineBlock(emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(LeadersBlock(emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(BroadcastBlock(emptyMap())))
        assertTrue(GameDetailUiPolicy.isEmpty(BroadcastBlock(mapOf("US" to emptyList()))))
        assertTrue(GameDetailUiPolicy.isEmpty(FormBlock(emptyList(), emptyList())))
        assertTrue(GameDetailUiPolicy.isEmpty(SituationBlock(" ")))
        assertTrue(GameDetailUiPolicy.isEmpty(HighlightsBlock("")))
    }

    @Test fun `non-empty blocks show`() {
        assertFalse(GameDetailUiPolicy.isEmpty(StatsBlock(listOf(StatRow("Fouls", "1", "2")))))
        assertFalse(GameDetailUiPolicy.isEmpty(LeadersBlock(listOf(LeaderItem("Points", "A", "30")))))
        assertFalse(GameDetailUiPolicy.isEmpty(BroadcastBlock(mapOf("US" to listOf("ESPN")))))
        assertFalse(GameDetailUiPolicy.isEmpty(WinProbabilityBlock(0)))
        assertFalse(GameDetailUiPolicy.isEmpty(FormBlock(listOf("W"), emptyList())))
    }

    @Test fun `wide layout from 768 dp`() {
        assertFalse(GameDetailUiPolicy.isWide(767))
        assertTrue(GameDetailUiPolicy.isWide(768))
    }

    @Test fun `wide layout puts score blocks left and detail blocks right`() {
        assertTrue(GameDetailUiPolicy.isPrimaryColumn(LinescoreBlock(listOf("Q1"), listOf(listOf("1")))))
        assertTrue(GameDetailUiPolicy.isPrimaryColumn(BroadcastBlock(mapOf("US" to listOf("ESPN")))))
        assertFalse(GameDetailUiPolicy.isPrimaryColumn(StatsBlock(listOf(StatRow("a", "1", "2")))))
        assertFalse(GameDetailUiPolicy.isPrimaryColumn(TimelineBlock(emptyList())))
    }

    @Test fun `loading ends at its deadline in the UI even if the controller is stuck`() {
        val loading = com.nuvio.app.features.sportsdetail.internal.GameDetailUiState.Loading(startedAtMs = 0, deadlineAtMs = 20_000)
        assertEquals(loading, GameDetailUiPolicy.effective(loading, 19_999))
        assertEquals(com.nuvio.app.features.sportsdetail.internal.GameDetailUiState.Failed(timedOut = true), GameDetailUiPolicy.effective(loading, 20_000))
        val failed = com.nuvio.app.features.sportsdetail.internal.GameDetailUiState.Failed(false)
        assertEquals(failed, GameDetailUiPolicy.effective(failed, 99_999))
    }

    @Test fun `only http and https links open`() {
        assertTrue(GameDetailUiPolicy.isSafeLink("https://youtu.be/x"))
        assertTrue(GameDetailUiPolicy.isSafeLink("http://example.com/a"))
        assertFalse(GameDetailUiPolicy.isSafeLink("intent://x#Intent;end"))
        assertFalse(GameDetailUiPolicy.isSafeLink("javascript:alert(1)"))
        assertFalse(GameDetailUiPolicy.isSafeLink("file:///etc/passwd"))
        assertFalse(GameDetailUiPolicy.isSafeLink(""))
    }

    // ---- live line, periods, team tags, broadcast ordering -----------------------------------------------------

    @Test fun `live status text is period and clock or the break wording`() {
        fun t(l: LiveInfo?) = GameDetailUiPolicy.liveStatusText(l)
        assertEquals("2nd · 5:12", t(LiveInfo(clock = "5:12", periodLabel = "2nd", detail = "5:12 - 2nd Period")))
        assertEquals("Intermission", t(LiveInfo(clock = "0:00", periodLabel = "2nd", detail = "End of 2nd Period")))
        assertEquals("Halftime", t(LiveInfo(clock = "45'", detail = "Halftime")))
        assertEquals("67'", t(LiveInfo(clock = "67'", detail = "67'")))
        assertEquals("LIVE", t(LiveInfo()))
        assertEquals("LIVE", t(null))
    }

    @Test fun `not-started periods are hidden while live and totals are kept`() {
        val b = LinescoreBlock(listOf("P1", "P2", "P3", "T"), listOf(listOf("1", "2", "0", "3"), listOf("2", "0", "0", "2")), listOf("WSH", "FLA"))
        val trimmed = GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.LIVE, livePeriod = 2)
        assertEquals(listOf("P1", "P2", "T"), trimmed.columns)
        assertEquals(listOf("1", "2", "3"), trimmed.rows[0])
        assertEquals(listOf("2", "0", "2"), trimmed.rows[1])
        assertEquals(b, GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.FINAL, livePeriod = 2), "final games show every period")
        assertEquals(b, GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.LIVE, livePeriod = null))
        val mlb = LinescoreBlock(listOf("1", "2", "3", "R", "H", "E"), listOf(listOf("0", "1", "0", "1", "3", "0"), listOf("0", "0", "0", "0", "1", "1")))
        assertEquals(listOf("1", "2", "R", "H", "E"), GameDetailUiPolicy.trimUnstartedPeriods(mlb, GameState.LIVE, livePeriod = 2).columns)
        assertEquals(b.columns, GameDetailUiPolicy.trimUnstartedPeriods(b, GameState.LIVE, livePeriod = 9).columns)
    }

    @Test fun `rows carry the team short name from the header sides`() {
        val h = TeamsHeader(Side("Washington Capitals", short = "WSH"), Side("Florida Panthers"))
        assertEquals("WSH", GameDetailUiPolicy.teamTag("home", h))
        assertEquals("Florida Panthers", GameDetailUiPolicy.teamTag("away", h), "no short name: the full name")
        assertNull(GameDetailUiPolicy.teamTag(null, h))
        assertNull(GameDetailUiPolicy.teamTag("home", EventHeader("Grand Prix")))
    }

    @Test fun `broadcast puts the viewer's region first and caps the rest`() {
        val by = linkedMapOf(
            "Canada" to listOf("SN"), "Croatia" to listOf("DAZN"), "France" to listOf("beIN"), "Germany" to listOf("SD"),
            "United States" to listOf("ESPN+"), "United Kingdom" to listOf("TNT"),
        )
        val us = GameDetailUiPolicy.splitBroadcast(by, "US")
        assertEquals("United States", us.first.first().first)
        assertEquals(3, us.first.size)
        assertEquals(3, us.second.size)
        assertEquals(6, (us.first + us.second).map { it.first }.toSet().size)
        assertEquals("United Kingdom", GameDetailUiPolicy.splitBroadcast(by, "GB").first.first().first)
        // ESPN keys its US list as "US"
        assertEquals("US", GameDetailUiPolicy.splitBroadcast(mapOf("US" to listOf("USA Net"), "Spain" to listOf("DAZN")), "US").first.first().first)
        // unknown region: first three alphabetical, rest behind More
        val none = GameDetailUiPolicy.splitBroadcast(by, null)
        assertEquals(listOf("Canada", "Croatia", "France"), none.first.map { it.first })
        assertEquals(3, none.second.size)
        assertEquals(0, GameDetailUiPolicy.splitBroadcast(mapOf("Canada" to listOf("SN")), "CA").second.size)
    }
}
