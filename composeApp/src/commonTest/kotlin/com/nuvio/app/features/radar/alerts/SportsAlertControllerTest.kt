package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.features.radar.RevealedGames
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.NowWatching
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import com.nuvio.app.features.sportsdetail.api.WatchOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state holder on virtual time: one banner, one local timer for held alerts, auto-dismiss, the Watch upgrade, and
 * telemetry that never names a team, league or event. kotlin.test → assertEquals(expected, actual, message).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsAlertControllerTest {
    private val t0 = 1_800_000_000_000L

    private class FakeInputs(private val clock: () -> Long) : SportsAlertInputs {
        var settings = SportsAlertSettings()
        var spoiler = ScoreSpoilerSettings()
        var playing: Set<String> = emptySet()
        var playback: PlaybackContext = PlaybackContext.None
        override fun nowMs(): Long = clock()
        override fun localMinuteOfDay(nowMs: Long): Int = 15 * 60
        override fun playback(): PlaybackContext = playback
        override fun settings(): SportsAlertSettings = settings
        override fun followedLeagueIds(): Set<String> = emptySet()
        override fun followedTeamIds(): Set<String> = setOf("133604")
        override fun spoiler(): ScoreSpoilerSettings = spoiler
        override fun revealed(): RevealedGames = RevealedGames()
        override fun playingChannelIds(): Set<String> = playing
    }

    private class FakeWatch(private val result: WatchLookup) : GameWatchPort {
        var lookups = 0
        override val nowWatching: StateFlow<NowWatching?> = MutableStateFlow(null)
        override suspend fun lookup(ref: GameRef): WatchLookup {
            lookups++
            return result
        }
    }

    private fun alert(eventId: String = "e1", key: String = "$eventId:score", at: Long = t0, kind: AlertKind = AlertKind.Score) = SportsAlert(
        key = key, eventId = eventId, kind = kind, sport = "soccer", leagueId = "4328", league = "Premier League",
        homeId = "133604", awayId = "133610", home = "Arsenal", away = "Chelsea", homeScore = 1, awayScore = 0,
        progress = null, corrected = false, atMs = at,
    )

    private class Harness(
        test: TestScope,
        scope: CoroutineScope,
        watch: GameWatchPort,
    ) {
        val sent = ArrayList<Map<String, Any>>()
        val inputs = FakeInputs { 1_800_000_000_000L + test.testScheduler.currentTime }
        val actions = SportsAlertActions(watch, scope) { id -> GameRef("tsdb:$id", "4328", id, null, "Arsenal", "Chelsea", null, 0L) }
        val controller = SportsAlertController(inputs, scope, actions, report = { sent += it })
    }

    @Test
    fun `a shown banner dismisses itself after 8 s`() = runTest {
        val h = Harness(this, backgroundScope, FakeWatch(WatchLookup.NoMatch))
        h.controller.onAlert(alert())
        assertEquals("Goal · Arsenal 1–0 Chelsea", h.controller.banner.value?.title, "shown at once")
        advanceTimeBy(SportsAlertController.PHONE_DISMISS_MS - 1)
        runCurrent()
        assertTrue(h.controller.banner.value != null, "still up just before")
        advanceTimeBy(2)
        runCurrent()
        assertNull(h.controller.banner.value, "gone after 8 s")
    }

    @Test
    fun `a held alert flushes on the one local timer`() = runTest {
        val h = Harness(this, backgroundScope, FakeWatch(WatchLookup.NoMatch))
        h.inputs.spoiler = ScoreSpoilerSettings(shown = true, delaySeconds = 30)
        h.controller.onAlert(alert())
        assertNull(h.controller.banner.value, "held for the delay")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("Goal · Arsenal 1–0 Chelsea", h.controller.banner.value?.title, "released when due")
    }

    @Test
    fun `the button reads Open game and becomes Watch when the lookup finds the channel`() = runTest {
        val watch = FakeWatch(WatchLookup.Found(WatchOffer("live:42", "Sky Sports")))
        val h = Harness(this, backgroundScope, watch)
        h.controller.onAlert(alert())
        assertEquals(BannerAction.OpenGame("e1"), h.controller.banner.value?.action, "never waits")
        runCurrent()
        assertEquals(BannerAction.Watch("e1", "live:42", "Sky Sports"), h.controller.banner.value?.action, "upgraded")
        assertEquals(1, watch.lookups, "one lookup per banner")
        assertEquals(BannerAction.Watch("e1", "live:42", "Sky Sports"), h.controller.act(), "the action the shell runs")
        assertNull(h.controller.banner.value, "acting dismisses")
    }

    @Test
    fun `an alert for the channel now playing is dropped once its channel is known`() = runTest {
        val h = Harness(this, backgroundScope, FakeWatch(WatchLookup.Found(WatchOffer("live:42", "Sky Sports"))))
        h.controller.onAlert(alert(key = "first"))
        runCurrent()
        h.controller.dismiss()
        h.inputs.playing = setOf("live:42")
        h.inputs.settings = SportsAlertSettings(batchSeconds = 0)
        h.controller.onAlert(alert(key = "second"))
        assertNull(h.controller.banner.value, "the viewer is watching this game")
        assertEquals("watching_this_game", h.sent.last()["reason"], "reported")
    }

    @Test
    fun `an unknown channel over live tv waits for the lookup then shows`() = runTest {
        val watch = FakeWatch(WatchLookup.NoMatch)
        val h = Harness(this, backgroundScope, watch)
        h.inputs.playback = PlaybackContext.LiveTv
        h.inputs.playing = setOf("live:9")
        h.controller.onAlert(alert())
        assertNull(h.controller.banner.value, "held for the watch check")
        runCurrent()
        assertEquals(1, watch.lookups, "the hold started the lookup")
        assertEquals("Goal · Arsenal 1–0 Chelsea", h.controller.banner.value?.title, "no channel carries it: shown at once")
    }

    @Test
    fun `a full time banner starts no lookup`() = runTest {
        val watch = FakeWatch(WatchLookup.Found(WatchOffer("live:42", "Sky Sports")))
        val h = Harness(this, backgroundScope, watch)
        h.controller.onAlert(alert(kind = AlertKind.Final, key = "e1:final"))
        runCurrent()
        assertEquals(0, watch.lookups, "Open game only")
        assertEquals(BannerAction.OpenGame("e1"), h.controller.banner.value?.action, "no Watch")
    }

    @Test
    fun `a game's lookup result is reused for 15 minutes`() = runTest {
        val watch = FakeWatch(WatchLookup.NoMatch)
        val h = Harness(this, backgroundScope, watch)
        h.inputs.settings = SportsAlertSettings(batchSeconds = 0)
        h.controller.onAlert(alert(key = "a"))
        runCurrent()
        h.controller.dismiss()
        h.controller.onAlert(alert(key = "b"))
        runCurrent()
        assertEquals(1, watch.lookups, "a no-match is cached too")
        h.controller.dismiss()
        advanceTimeBy(15 * 60_000L + 1)
        h.controller.onAlert(alert(key = "c", at = t0 + 15 * 60_000L + 1))
        runCurrent()
        assertEquals(2, watch.lookups, "asked again after 15 minutes")
    }

    @Test
    fun `telemetry sends only the ruled outcomes and never an id or a name`() = runTest {
        val h = Harness(this, backgroundScope, FakeWatch(WatchLookup.NoMatch))
        h.controller.onAlert(alert())
        h.controller.onAlert(alert())                      // duplicate: never emitted
        h.controller.onAlert(alert(eventId = "x"))         // held for the batch: counted
        h.controller.onAlert(alert(eventId = "y").copy(homeId = "nobody", awayId = "none")) // not followed: never emitted
        h.controller.act()
        val allowed = setOf("outcome", "reason", "kind", "sport", "batch", "action")
        h.sent.forEach { props -> assertTrue(props.keys.all { it in allowed }, "only the shared schema: $props") }
        assertEquals(listOf("shown", "held", "action"), h.sent.map { it["outcome"] }, "duplicate and not_followed are never emitted")
        assertTrue(h.sent.none { p -> p.values.any { it == "e1" || it == "x" || it == "Arsenal" || it == "4328" } }, "no ids or names")
    }

    @Test
    fun `a flush that needs the watch check asks before dropping`() = runTest {
        val watch = FakeWatch(WatchLookup.NoMatch)
        val h = Harness(this, backgroundScope, watch)
        h.inputs.spoiler = ScoreSpoilerSettings(shown = true, delaySeconds = 30)
        h.controller.onAlert(alert())
        h.inputs.playback = PlaybackContext.LiveTv
        h.inputs.playing = setOf("live:9")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, watch.lookups, "the flush started the watch lookup")
        assertEquals("Goal · Arsenal 1–0 Chelsea", h.controller.banner.value?.title, "no channel carries it: shown")
    }

    @Test
    fun `going to the background clears what is held`() = runTest {
        val h = Harness(this, backgroundScope, FakeWatch(WatchLookup.NoMatch))
        h.inputs.spoiler = ScoreSpoilerSettings(shown = true, delaySeconds = 30)
        h.controller.onAlert(alert())
        h.controller.clearHeld()
        advanceTimeBy(31_000)
        runCurrent()
        assertNull(h.controller.banner.value, "nothing surfaces on return")
    }

    @Test
    fun `any channel that carries the game counts even when the watch button has another`() = runTest {
        val found = WatchLookup.Found(WatchOffer("live:1", "Sky Sports"), carriers = setOf("live:1", "live:9"))
        val h = Harness(this, backgroundScope, FakeWatch(found))
        h.inputs.playback = PlaybackContext.LiveTv
        h.inputs.playing = setOf("live:9")
        h.controller.onAlert(alert())
        runCurrent()
        assertNull(h.controller.banner.value, "the viewer is on a second carrier")
        assertEquals("watching_this_game", h.sent.last()["reason"], "reported")
    }

    @Test
    fun `unconfirmed carriers count for gate 3`() = runTest {
        val h = Harness(this, backgroundScope, FakeWatch(WatchLookup.Unconfirmed(setOf("live:9"))))
        h.inputs.playback = PlaybackContext.LiveTv
        h.inputs.playing = setOf("live:9")
        h.controller.onAlert(alert())
        runCurrent()
        assertNull(h.controller.banner.value, "a channel that may carry it is on screen")
    }
}
