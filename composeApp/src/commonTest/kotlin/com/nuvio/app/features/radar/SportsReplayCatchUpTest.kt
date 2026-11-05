package com.nuvio.app.features.radar

import com.nuvio.app.core.contracts.SportsReplay

import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.CatchUpPlayback
import com.nuvio.app.features.player.LivePlaybackRejoinPolicy
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.livetv.beginLaunchReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sports Centre replays must enter the docked Live TV screen as CATCH-UP — the same session the
 * guide's replays create (WP5) — never as a live tune wearing a timeshift URL.
 *
 * Before this lane, replayFor registered a synthetic live item carrying one hardcoded timeshift
 * shape and the launch carried no replay bounds: the screen treated it as live (zapping allowed,
 * live-edge rejoin on foreground, freeze watchdog armed) and a panel speaking another catch-up
 * dialect simply never played.
 */
class SportsReplayCatchUpTest {

    @Test
    fun `a sports replay carries the catch-up flag`() {
        val replay = RadarChannelMatcher.replayDescriptor(
            match(hasArchive = true), STARTED_FIXTURE, xtreamAccount(), NOW_MS,
        )
        assertNotNull(replay, "an archived xtream channel must offer a replay")

        val session = beginLaunchReplay(
            walk = walk(),
            request = requestFor(replay),
            contentId = replay.contentId,
            programmeTitle = replay.programmeTitle,
            startMs = replay.programmeStartMs,
            endMs = replay.programmeEndMs,
            nowMs = NOW_MS,
        )
        assertNotNull(session, "the launch replay must begin a catch-up session")
        // The player surface's isCatchUpPlayback is exactly `session != null` on this screen.
        assertEquals(
            replay.programmeStartMs, session.startMs,
            "the programme's start must thread through to the session",
        )
        assertEquals(
            replay.programmeEndMs, session.endMs,
            "the programme's end must thread through to the session",
        )
        assertEquals(
            replay.contentId, session.contentId,
            "the session must keep the LIVE channel's id - the flag carries the difference",
        )
    }

    @Test
    fun `zapping is inert during a sports replay`() {
        val replay = RadarChannelMatcher.replayDescriptor(
            match(hasArchive = true), STARTED_FIXTURE, xtreamAccount(), NOW_MS,
        )!!
        val session = beginLaunchReplay(
            walk = walk(),
            request = requestFor(replay),
            contentId = replay.contentId,
            programmeTitle = replay.programmeTitle,
            startMs = replay.programmeStartMs,
            endMs = replay.programmeEndMs,
            nowMs = NOW_MS,
        )

        val isCatchUpPlayback = session != null

        assertFalse(
            CatchUpPlayback.allowsChannelChange(isCatchUpPlayback),
            "guide taps must not zap away a sports replay",
        )
        assertFalse(
            LivePlaybackRejoinPolicy.rejoinsLiveEdge("live", isCatchUpPlayback),
            "foregrounding must not rejoin the live edge during a sports replay",
        )
        assertFalse(
            CatchUpPlayback.armsFreezeWatchdog(isCatchUpPlayback),
            "the freeze watchdog must stay disarmed against a recording",
        )
    }

    @Test
    fun `a sports replay walks dialects on transport failure`() {
        val replay = RadarChannelMatcher.replayDescriptor(
            match(hasArchive = true), STARTED_FIXTURE, xtreamAccount(), NOW_MS,
        )!!
        val walk = walk()
        val session = beginLaunchReplay(
            walk = walk,
            request = requestFor(replay),
            contentId = replay.contentId,
            programmeTitle = replay.programmeTitle,
            startMs = replay.programmeStartMs,
            endMs = replay.programmeEndMs,
            nowMs = NOW_MS,
        )!!

        // What LiveTvScreen's onError does with a transport-shaped failure.
        val step = walk.onFailure(session.attempt.token, CatchUpPlayback.failureKind("HTTP 404"))

        assertTrue(step is CatchUpDialectWalk.Step.Next, "a transport failure must advance the ladder but was $step")
        assertNotEquals(
            session.attempt.url, (step as CatchUpDialectWalk.Step.Next).attempt.url,
            "the walk must offer a DIFFERENT URL shape",
        )
    }

    @Test
    fun `a live sports tune stays live`() {
        // No replay offered before kickoff, without an archive, for a missing account, or for a
        // Stalker portal (its archive links stay server-minted - no dialect applies).
        assertNull(
            RadarChannelMatcher.replayDescriptor(match(hasArchive = true), UPCOMING_FIXTURE, xtreamAccount(), NOW_MS),
            "a fixture that has not started must not offer a replay",
        )
        assertNull(
            RadarChannelMatcher.replayDescriptor(match(hasArchive = false), STARTED_FIXTURE, xtreamAccount(), NOW_MS),
            "a channel without an archive must not offer a replay",
        )
        assertNull(
            RadarChannelMatcher.replayDescriptor(match(hasArchive = true), STARTED_FIXTURE, account = null, nowMs = NOW_MS),
            "a channel whose playlist is gone must not offer a replay",
        )
        assertNull(
            RadarChannelMatcher.replayDescriptor(match(hasArchive = true), STARTED_FIXTURE, stalkerAccount(), NOW_MS),
            "stalker replays stay server-minted - never walked",
        )

        // And a launch with no replay request is a plain live tune: no session, gates open.
        val session = beginLaunchReplay(
            walk = walk(),
            request = null,
            contentId = CONTENT_ID,
            programmeTitle = "",
            startMs = 0L,
            endMs = 0L,
            nowMs = NOW_MS,
        )
        assertNull(session, "a live launch must not begin a catch-up session")
        assertTrue(
            CatchUpPlayback.allowsChannelChange(isCatchUpPlayback = false),
            "zapping must stay available on a live sports tune",
        )
    }

    @Test
    fun `a replay without a programme spans the default window from before kickoff`() {
        // No EPG hit: the window opens 15 minutes before kickoff and runs the default 165 minutes,
        // the same bounds the old single-dialect URL asked the panel for.
        val replay = RadarChannelMatcher.replayDescriptor(
            match(hasArchive = true), STARTED_FIXTURE, xtreamAccount(), NOW_MS,
        )!!
        val kickoff = STARTED_FIXTURE.startEpochMs!!
        assertEquals(
            kickoff - 15 * 60_000L, replay.programmeStartMs,
            "without EPG the replay opens 15 minutes before kickoff",
        )
        assertEquals(
            replay.programmeStartMs + 165 * 60_000L, replay.programmeEndMs,
            "without EPG the replay spans the default 165 minutes",
        )
    }

    // --- helpers ---------------------------------------------------------------

    private class FakeMemory : CatchUpDialectWalk.WinnerMemory {
        val stored = mutableMapOf<String, CatchUpDialectWalk.StoredWinner>()
        override fun recall(accountId: String): CatchUpDialectWalk.StoredWinner? = stored[accountId]
        override fun remember(accountId: String, winner: CatchUpDialectWalk.StoredWinner) {
            stored[accountId] = winner
        }
    }

    private fun walk() = CatchUpDialectWalk(FakeMemory())

    /** The walk request the screen builds for a replay launch (LiveTvData.catchUpRequest's shape). */
    private fun requestFor(replay: SportsReplay): CatchUpDialectWalk.Request {
        val account = xtreamAccount()
        return CatchUpDialectWalk.Request(
            accountId = account.id,
            baseUrl = account.baseUrl,
            username = account.username,
            password = account.password,
            streamId = STREAM_ID,
            startMs = replay.programmeStartMs,
            endMs = replay.programmeEndMs,
            preferM3u8 = account.catchUpPreferM3u8,
        )
    }

    private fun xtreamAccount() = XtreamAccount(
        id = ACCOUNT_ID,
        name = "Panel",
        baseUrl = "http://panel.example:8080",
        username = "user",
        password = "pass",
    )

    private fun stalkerAccount() = XtreamAccount(
        id = ACCOUNT_ID,
        name = "Portal",
        baseUrl = "http://portal.example",
        username = "",
        password = "",
        sourceType = SOURCE_TYPE_STALKER,
        macAddress = "00:1A:79:00:00:01",
    )

    private fun match(hasArchive: Boolean) = RadarChannelMatcher.ChannelMatch(
        channel = RadarChannelMatcher.CandidateChannel(
            playlistId = ACCOUNT_ID,
            playlistName = "Playlist",
            contentId = CONTENT_ID,
            name = "Sports 1",
            logo = null,
            streamId = STREAM_ID,
            hasArchive = hasArchive,
        ),
        programme = null,
        score = 50,
    )

    private companion object {
        const val ACCOUNT_ID = "http://panel.example:8080|user"
        const val STREAM_ID = 4242
        const val CONTENT_ID = "xtream:$ACCOUNT_ID:live:$STREAM_ID"

        /** 2024-03-09 16:00 UTC — safely after the started fixture, before the upcoming one. */
        const val NOW_MS = 1_710_000_000_000L

        val STARTED_FIXTURE = RadarFixture(
            id = "1",
            home = "Spain",
            away = "Austria",
            ts = "2020-05-01T12:00:00",
        )

        val UPCOMING_FIXTURE = RadarFixture(
            id = "2",
            home = "Spain",
            away = "Austria",
            ts = "2030-05-01T12:00:00",
        )
    }
}
