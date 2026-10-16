package com.nuvio.app.core.analytics

import com.nuvio.app.features.iptv.stalker.StalkerPlaybackTraffic
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BreadcrumbsTest {

    private val captured = mutableListOf<Pair<String, Map<String, Any>>>()

    private fun arm() {
        captured.clear()
        Breadcrumbs.resetForTest { event, properties -> captured += event to properties }
    }

    @AfterTest
    fun tearDown() {
        Breadcrumbs.resetForTest()
    }

    @Test
    fun resetLeavesNoPlaybackFlagForOtherSuites() {
        // playbackStarted flips the process-global Stalker "something is playing" flag. Left set, every
        // later Stalker browse call in the same test JVM waited MAX_DEFER_MS (3 s) per request: the
        // "stalled" full JVM suite of 2026-10-16 (StalkerMinistraSeriesTest alone took 121 s).
        arm()
        Breadcrumbs.playbackStarted("vod", "mpv", "player", "mkv", nowMs = 0L)
        Breadcrumbs.resetForTest()
        assertFalse(StalkerPlaybackTraffic.isPlaybackActive, "reset must clear the playback flag it set")
    }

    @Test
    fun screenChangedDedupesConsecutiveRepeats() {
        arm()
        Breadcrumbs.screenChanged("Tabs.Home")
        Breadcrumbs.screenChanged("Tabs.Home")
        Breadcrumbs.screenChanged("DetailRoute")
        Breadcrumbs.screenChanged("Tabs.Home")
        assertEquals(
            listOf("Tabs.Home", "DetailRoute", "Tabs.Home"),
            captured.map { it.second[Breadcrumbs.SCREEN_NAME_PROPERTY] },
        )
        assertEquals(setOf(Breadcrumbs.SCREEN_EVENT), captured.map { it.first }.toSet())
    }

    @Test
    fun identicalStartsInsideTheDedupeWindowCollapse() {
        arm()
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 0L)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 5_000L)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 60_000L)
        assertEquals(2, captured.size)
        assertEquals(setOf(Breadcrumbs.PLAYBACK_EVENT), captured.map { it.first }.toSet())
    }

    @Test
    fun differentChannelsAreNotDeduped() {
        arm()
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 0L)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "m3u8", nowMs = 1_000L)
        assertEquals(2, captured.size)
    }

    @Test
    fun hourlyCapBoundsAZapper() {
        arm()
        var now = 0L
        repeat(60) { index ->
            Breadcrumbs.playbackStarted("live", "mpv", "player", "c$index", nowMs = now)
            now += 20_000L
        }
        assertEquals(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR, captured.size)
    }

    @Test
    fun capacityReturnsOnceTheWindowSlides() {
        arm()
        repeat(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR) { index ->
            Breadcrumbs.playbackStarted("live", "mpv", "player", "c$index", nowMs = index * 1_000L)
        }
        Breadcrumbs.playbackStarted("live", "mpv", "player", "late", nowMs = 30 * 60_000L)
        assertEquals(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR, captured.size)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "later", nowMs = 62 * 60_000L)
        assertEquals(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR + 1, captured.size)
    }
}
