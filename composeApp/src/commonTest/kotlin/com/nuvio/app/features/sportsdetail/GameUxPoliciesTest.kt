package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.internal.ChannelLabelPolicy
import com.nuvio.app.features.sportsdetail.internal.GameStartLabel
import com.nuvio.app.features.sportsdetail.internal.Iso
import com.nuvio.app.features.sportsdetail.internal.StartTimeFormat
import com.nuvio.app.features.sportsdetail.internal.roundLabel
import com.nuvio.app.features.sportsdetail.internal.WinChanceSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sim-pass UX batch 2026-11-16: UX364 start time, clean channel label, chart visibility. */
class GameUxPoliciesTest {
    /** A fake device: the zone is a fixed offset, "today" is [nowMs]'s local day. */
    private class FakeFormat(private val offsetMin: Int, private val nowMs: Long) : StartTimeFormat {
        private fun local(ms: Long) = ms + offsetMin * 60_000L
        private fun day(ms: Long) = local(ms).floorDiv(86_400_000L)
        override fun dayLabel(epochMs: Long): String = when (day(epochMs) - day(nowMs)) {
            0L -> "Today"; 1L -> "Tomorrow"; else -> "D${day(epochMs)}"
        }
        override fun formatTime(epochMs: Long): String {
            val m = local(epochMs).mod(86_400_000L) / 60_000
            val h = (m / 60).toInt(); val mm = (m % 60).toInt()
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "$h12:${mm.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
        }
    }
    // 2026-11-16T20:00:00Z
    private val now = Iso.parseMs("2026-11-16T20:00:00Z")!!

    @Test fun `start - today reads Today and the device-local time not UTC`() {
        val f = FakeFormat(offsetMin = -300, nowMs = now)        // UTC-5: now is 15:00 local on the 16th
        assertEquals("Today 8:15 PM", GameStartLabel.of("2026-11-17T01:15:00Z", f), "01:15Z is 20:15 on the 16th at UTC-5")
    }

    @Test fun `start - the date shows only when it is not today`() {
        val f = FakeFormat(offsetMin = 0, nowMs = now)
        assertEquals("Tomorrow 1:15 AM", GameStartLabel.of("2026-11-17T01:15Z", f))
        assertTrue(GameStartLabel.of("2026-11-20T18:00Z", f)!!.startsWith("D"), "a later day carries its date label")
    }

    @Test fun `start - accepts the offset and space forms and never throws on junk`() {
        val f = FakeFormat(0, now)
        assertEquals("Today 9:30 PM", GameStartLabel.of("2026-11-16 21:30", f))
        assertEquals("Today 9:30 PM", GameStartLabel.of("2026-11-16T22:30:00+01:00", f))
        assertNull(GameStartLabel.of("", f))
        assertNull(GameStartLabel.of("soon", f))
    }

    @Test fun `channel - provider and country prefixes and quality tags go`() {
        assertEquals("Sky Sports Main Event", ChannelLabelPolicy.clean("UK || SKY SPORTS MAIN EVENT"))
        assertEquals("ESPN 2", ChannelLabelPolicy.clean("US | ESPN 2 HD"))
        assertEquals("Sky Sports F1", ChannelLabelPolicy.clean("[UK] Sky Sports F1 FHD"))
        assertEquals("NFL Network", ChannelLabelPolicy.clean("USA: NFL Network"))
        assertEquals("BT Sport 1", ChannelLabelPolicy.clean("|UK| BT SPORT 1 HD"))
        assertEquals("Canal+ Football", ChannelLabelPolicy.clean("FR | CANAL+ FOOTBALL HD"))
        assertEquals("DAZN 1", ChannelLabelPolicy.clean("(DE) DAZN 1 4K"))
    }

    @Test fun `channel - mixed case and brand names are left alone`() {
        assertEquals("beIN Sports 1", ChannelLabelPolicy.clean("beIN Sports 1"))
        assertEquals("A-League TV", ChannelLabelPolicy.clean("A-League TV"))
        assertEquals("ESPN", ChannelLabelPolicy.clean("ESPN"))
    }

    @Test fun `channel - cleaning that leaves nothing falls back to the raw text`() {
        assertEquals("UK ||", ChannelLabelPolicy.clean("UK ||"))
        assertEquals("[HD]", ChannelLabelPolicy.clean("[HD]"))
        assertEquals("", ChannelLabelPolicy.clean("   "))
    }

    @Test fun `chart - hidden until two points`() {
        assertFalse(WinChanceSeries.isVisible(emptyList()))
        assertFalse(WinChanceSeries.isVisible(listOf(WinPoint(1, 50))), "one point would draw a dot")
        assertTrue(WinChanceSeries.isVisible(listOf(WinPoint(1, 50), WinPoint(2, 52))))
        assertEquals(2, WinChanceSeries.MIN_POINTS)
    }
    // ---- gaps G2-G5 (review 2026-11-16) ----

    @Test fun `G2 - trailing separators are stripped`() {
        assertEquals("TSN 1", ChannelLabelPolicy.clean("TSN 1 |"))
        assertEquals("TSN 1", ChannelLabelPolicy.clean("TSN 1 ||"))
        assertEquals("TSN 1", ChannelLabelPolicy.clean("TSN 1 :"))
        assertEquals("TSN 1", ChannelLabelPolicy.clean("TSN 1 -"))
        assertEquals("Sky Sports Main Event", ChannelLabelPolicy.clean("UK || SKY SPORTS MAIN EVENT HD |"))
        assertEquals("A-League TV", ChannelLabelPolicy.clean("A-League TV"), "an inner hyphen stays")
        assertEquals("|", ChannelLabelPolicy.clean("|"), "nothing left falls back to the raw text")
    }

    @Test fun `G3 - a flat series is hidden and a moving one is shown`() {
        assertFalse(WinChanceSeries.isVisible(listOf(WinPoint(1, 50), WinPoint(2, 50))), "flat pre-kickoff line says nothing")
        assertFalse(WinChanceSeries.isVisible(listOf(WinPoint(1, 50))))
        assertTrue(WinChanceSeries.isVisible(listOf(WinPoint(1, 50), WinPoint(2, 51))))
        assertTrue(WinChanceSeries.isVisible(listOf(WinPoint(1, 40, draw = 30), WinPoint(2, 40, draw = 31))), "a moving draw share is movement too")
    }

    @Test fun `G4 - round label is Week for American football and Round otherwise`() {
        assertEquals("Week 11", roundLabel("American Football", "11"))
        assertEquals("Week 11", roundLabel("american football", "11"))
        assertEquals("Round 11", roundLabel("Soccer", "11"))
        assertEquals("Round 3", roundLabel("Australian Football", "3"))
        assertEquals("Round 7", roundLabel(null, "7"))
    }
}
