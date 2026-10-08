package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideDataRefreshPolicyTest {

    private val anchor = 1_000L

    @Test
    fun `a screen ignores the generation it was born with`() {
        assertFalse(GuideDataRefreshPolicy.changedSince(seenGeneration = 3, generation = 3))
        assertTrue(GuideDataRefreshPolicy.changedSince(seenGeneration = 3, generation = 4))
    }

    @Test
    fun `rows that answered empty before the ingest landed are re-asked and their stamps dropped`() {
        // The reproduced race: the guide opened while XMLTV ingest was still downloading, every row
        // answered empty and was stamped once-only, so the guide stayed "No EPG" after the ingest.
        val plan = GuideDataRefreshPolicy.plan(
            requestedStamps = setOf("a@$anchor", "b@$anchor", "c@$anchor"),
            hasProgrammes = { false },
            visible = listOf("a", "b", "c"),
            current = "a",
            anchorMs = anchor,
        )
        assertEquals(setOf("a@$anchor", "b@$anchor", "c@$anchor"), plan.dropStamps)
        assertEquals(listOf("a", "b", "c"), plan.reask)
    }

    @Test
    fun `visible rows that already show programmes are not re-asked`() {
        val plan = GuideDataRefreshPolicy.plan(
            requestedStamps = setOf("a@$anchor", "b@$anchor", "c@$anchor"),
            hasProgrammes = { it == "b" },
            visible = listOf("a", "b", "c"),
            current = "a",
            anchorMs = anchor,
        )
        assertEquals(setOf("a@$anchor", "c@$anchor"), plan.dropStamps)
        assertEquals(listOf("a", "c"), plan.reask)
    }

    @Test
    fun `the focused channel is always re-read even when it already shows programmes`() {
        // A manual guide pick (EpgOverrides) changes data for a row that already had some.
        val plan = GuideDataRefreshPolicy.plan(
            requestedStamps = setOf("a@$anchor"),
            hasProgrammes = { true },
            visible = listOf("a", "b"),
            current = "a",
            anchorMs = anchor,
        )
        assertEquals(setOf("a@$anchor"), plan.dropStamps)
        assertEquals(listOf("a"), plan.reask)
    }

    @Test
    fun `the focused channel comes first and is not duplicated when it is also visible`() {
        val plan = GuideDataRefreshPolicy.plan(
            requestedStamps = emptySet(),
            hasProgrammes = { false },
            visible = listOf("b", "a", "c"),
            current = "a",
            anchorMs = anchor,
        )
        assertEquals(listOf("a", "b", "c"), plan.reask)
    }

    @Test
    fun `only stamps of the current window are dropped`() {
        val plan = GuideDataRefreshPolicy.plan(
            requestedStamps = setOf("b@$anchor", "b@999"),
            hasProgrammes = { false },
            visible = listOf("b"),
            current = "a",
            anchorMs = anchor,
        )
        assertEquals(setOf("b@$anchor"), plan.dropStamps)
    }

    @Test
    fun `a tile with no programme re-asks on new guide data and a tile with one never does`() {
        assertEquals(7L, GuideDataRefreshPolicy.tileHealKey(hasProgramme = false, generation = 7L))
        assertEquals(
            GuideDataRefreshPolicy.tileHealKey(hasProgramme = true, generation = 7L),
            GuideDataRefreshPolicy.tileHealKey(hasProgramme = true, generation = 8L),
        )
    }
}
