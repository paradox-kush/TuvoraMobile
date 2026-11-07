package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
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

    @Test fun `chip appears past 20 s using the OLDEST source`() {
        assertEquals("Updated 21s ago", GameDetailUiPolicy.staleLabel(mapOf("espn" to now - 21_000, "tsdb" to now - 2_000), now))
        assertEquals("Updated 2 min ago", GameDetailUiPolicy.staleLabel(mapOf("tsdb" to now - 125_000, "espn" to now), now))
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
}
