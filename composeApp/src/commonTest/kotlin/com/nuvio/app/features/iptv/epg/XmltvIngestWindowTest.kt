package com.nuvio.app.features.iptv.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The XMLTV ingest kept every programme a feed contained — no time bound at all. Survivable while
 * it ran for M3U playlists only; not survivable as the general rule, and the whole-guide lane for
 * Xtream panels would have multiplied it across every account. These pin the bound.
 */
class XmltvIngestWindowTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun keeps(startOffsetMs: Long, durationMs: Long = hour) =
        XmltvIngestWindow.keeps(now + startOffsetMs, now + startOffsetMs + durationMs, now)

    @Test
    fun `a programme airing now is kept`() {
        assertTrue(keeps(-30 * 60 * 1000))
    }

    @Test
    fun `recent history is kept so the guide opens with a visible past`() {
        // The docked guide anchors an hour back, so that hour has to be on disk.
        assertTrue(keeps(-2 * hour), "two hours ago is inside the six-hour lookback")
        assertTrue(keeps(-5 * hour), "five hours ago is still inside it")
    }

    @Test
    fun `history older than the lookback is refused`() {
        assertFalse(keeps(-8 * hour), "eight hours ago is past the bound")
        assertFalse(keeps(-3 * day), "deep history belongs to the catch-up lane, not this one")
    }

    @Test
    fun `two days ahead is kept and a week ahead is refused`() {
        assertTrue(keeps(36 * hour), "a day and a half ahead is inside the forward horizon")
        assertFalse(keeps(7 * day), "a week of schedule is rows a budget device cannot afford")
    }

    @Test
    fun `a programme straddling the window edge is kept`() {
        // Started before the lookback but still running: the guide needs it to fill the row.
        assertTrue(XmltvIngestWindow.keeps(now - 9 * hour, now - 1 * hour, now))
    }

    @Test
    fun `a zero length programme is refused`() {
        // A feed with a broken date format produces thousands of these, and every window read
        // that spans the instant returns them.
        assertFalse(XmltvIngestWindow.keeps(now, now, now))
    }

    @Test
    fun `a backwards programme is refused`() {
        assertFalse(XmltvIngestWindow.keeps(now + hour, now, now))
    }

    @Test
    fun `an epoch-zero programme is refused`() {
        assertFalse(XmltvIngestWindow.keeps(0L, hour, now))
    }
}

/** M3U catch-up: a channel that replays N days keeps N days of guide history instead of six hours. */
class XmltvCatchUpHistoryWindowTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    @Test
    fun `the lookback follows catchup-days and is capped at a week`() {
        assertTrue(XmltvIngestWindow.backMsForCatchUpDays(3) == 3 * day)
        assertTrue(XmltvIngestWindow.backMsForCatchUpDays(30) == 7 * day, "capped at 7 days")
        assertTrue(XmltvIngestWindow.backMsForCatchUpDays(0) == XmltvIngestWindow.BACK_MS, "no archive keeps the default")
        assertTrue(XmltvIngestWindow.backMsForCatchUpDays(-2) == XmltvIngestWindow.BACK_MS)
    }

    @Test
    fun `a programme from two days ago is kept with a three day lookback and refused by default`() {
        val start = now - 2 * day
        assertTrue(XmltvIngestWindow.keeps(start, start + hour, now, XmltvIngestWindow.backMsForCatchUpDays(3)))
        assertFalse(XmltvIngestWindow.keeps(start, start + hour, now), "the default window still refuses it")
    }

    @Test
    fun `the deepest archive among channels sharing one guide id wins`() {
        val byGuide = XmltvIngestWindow.backMsByGuide(
            listOf(1 to "Shared.Id", 2 to "shared.id", 3 to "other"),
            mapOf(1 to 2, 2 to 5),
        )
        assertTrue(byGuide[normalizeChannelId("Shared.Id")] == 5 * day)
        assertFalse("other" in byGuide, "a channel with no catch-up adds no entry")
    }

    @Test
    fun `an ingest replay keeps past programmes only for the catch-up channel`() {
        val nowMs = parseXmltvTime("20261010120000 +0000")!!
        fun at(offsetMs: Long): String {
            // Build the XMLTV stamp from epoch ms through the same parser's inverse: day-granular offsets only.
            val days = (-offsetMs) / day
            val d = 10 - days.toInt()
            return "202610${d.toString().padStart(2, '0')}120000 +0000"
        }
        fun ch(id: String, name: String) = "<channel id=\"$id\"><display-name>$name</display-name></channel>"
        fun prog(id: String, title: String, daysBack: Int) =
            "<programme channel=\"$id\" start=\"${at(-daysBack * day)}\" stop=\"${at(-daysBack * day + hour)}\"><title>$title</title></programme>"
        val xml = "<tv>" + ch("replayable", "Replayable") + ch("plain", "Plain") +
            prog("replayable", "Replayable 2d", 2) + prog("plain", "Plain 2d", 2) + "</tv>"
        val replay = XmltvGuideReplay(
            listOf(
                com.nuvio.app.features.epg.GuideChannelMatcher.LineupChannel(1, "Replayable", "replayable"),
                com.nuvio.app.features.epg.GuideChannelMatcher.LineupChannel(2, "Plain", "plain"),
            ),
            emptySet(), com.nuvio.app.features.epg.ChannelNameCleaner.Rules.DEFAULT, nowMs,
            catchUpDaysBySid = mapOf(1 to 3),
        )
        try {
            replay.feed(xml)
            val rows = mutableListOf<XmltvProgramme>()
            replay.finish(rows::add)
            assertTrue(rows.map { it.title } == listOf("Replayable 2d"), "only the catch-up channel keeps its past: ${rows.map { it.title }}")
        } finally { replay.dispose() }
    }

    @Test
    fun `a guide id picked by hand keeps its channel's archive depth`() {
        val nowMs = parseXmltvTime("20261010120000 +0000")!!
        // The channel's own tvg-id matches nothing in the guide; the viewer picked "custom" for it.
        val xml = "<tv><channel id=\"custom\"><display-name>Custom Feed</display-name></channel>" +
            "<programme channel=\"custom\" start=\"20261008120000 +0000\" stop=\"20261008130000 +0000\"><title>Two days ago</title></programme></tv>"
        fun run(manual: List<Pair<Int, String>>): List<String> {
            val replay = XmltvGuideReplay(
                listOf(com.nuvio.app.features.epg.GuideChannelMatcher.LineupChannel(1, "Zzz Qqq", "no-such-id")),
                setOf("custom"), com.nuvio.app.features.epg.ChannelNameCleaner.Rules.DEFAULT, nowMs,
                catchUpDaysBySid = mapOf(1 to 3), manualAssignments = manual,
            )
            try {
                replay.feed(xml)
                val rows = mutableListOf<XmltvProgramme>()
                replay.finish(rows::add)
                return rows.map { it.title }
            } finally { replay.dispose() }
        }
        assertEquals(listOf("Two days ago"), run(listOf(1 to "custom")), "the picked guide keeps the channel's three days")
        assertEquals(emptyList(), run(emptyList()), "without the pick it falls back to six hours")
    }

    @Test
    fun `manual picks join the lineup through the entity id and span profiles`() {
        val picks = listOf("e1" to "guideA", "e1" to "guideB", "e9" to "elsewhere")
        assertEquals(
            listOf(1 to "guideA", 1 to "guideB"),
            XmltvIngestWindow.manualAssignments(mapOf(1 to "e1", 2 to "e2"), picks),
        )
        assertEquals(emptyList(), XmltvIngestWindow.manualAssignments(mapOf(1 to "e1"), emptyList()))
    }
}
