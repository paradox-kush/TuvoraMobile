package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The live-only behaviours a replay must NOT inherit, plus the scrub bar's live-edge clamp.
 *
 * A catch-up stream is a recording that happens to arrive down the live pipe. Treated as live it
 * zaps to another channel when the viewer presses up, rejoins "the live edge" of a finished
 * programme after backgrounding, and arms a freeze watchdog against something that cannot freeze.
 */
class CatchUpPlaybackTest {

    private val now = 1_710_000_000_000L
    private val minute = 60_000L

    // --- the gates ------------------------------------------------------------------------

    /**
     * Channel zapping is the loudest of the three: up/down mid-replay throws the viewer onto
     * another channel and loses their place, with no way back to where they were.
     */
    @Test
    fun `channel zapping is suppressed during a replay`() {
        assertTrue(CatchUpPlayback.allowsChannelChange(isCatchUpPlayback = false))
        assertFalse(CatchUpPlayback.allowsChannelChange(isCatchUpPlayback = true))
    }

    /**
     * The invariant the gate above exists to protect, checked where the two pieces of state meet.
     *
     * Found on a real panel: tapping another channel mid-replay moved the guide selection, the
     * channel logo and the now-bar to BBC Two while BBC One's recording kept playing, because the
     * click handler had outlived the composition it read the catch-up flag from and so skipped the
     * tear-down. Whoever moves the channel, a session that no longer belongs to it must not
     * survive — that is checkable without asking any caller whether it thinks it is catching up.
     */
    @Test
    fun `a replay does not survive the channel moving out from under it`() {
        val bbcOne = "xtream:acct:live:814709"
        val bbcTwo = "xtream:acct:live:11957"
        assertTrue(CatchUpPlayback.sessionSurvivesChannel(bbcOne, bbcOne))
        assertFalse(CatchUpPlayback.sessionSurvivesChannel(bbcOne, bbcTwo))
    }

    /**
     * The freeze watchdog reports `live_playback_freeze` and reconnects by re-resolving. Armed
     * against a replay it would both pollute the freeze telemetry we are still reading and burn a
     * provider connection re-minting a URL that was never broken.
     */
    @Test
    fun `the freeze watchdog is disarmed during a replay`() {
        assertTrue(CatchUpPlayback.armsFreezeWatchdog(isCatchUpPlayback = false))
        assertFalse(CatchUpPlayback.armsFreezeWatchdog(isCatchUpPlayback = true))
    }

    // --- the scrub bar --------------------------------------------------------------------

    /** A panel answering `.m3u8` sends a playlist with every segment's duration, so it seeks. */
    @Test
    fun `an m3u8 replay is seekable`() {
        assertTrue(CatchUpPlayback.isSeekable("http://p.tv/timeshift/u/p/60/2026-08-15:18-30/12.m3u8"))
        // ...but a single-use link answers only its first open, so a scrub (a new Range request) would fail it.
        assertFalse(CatchUpPlayback.isSeekable("http://p.tv/timeshift/u/p/60/2026-08-15:18-30/12.m3u8", singleUse = true))
        assertTrue(CatchUpPlayback.isSeekable("http://p.tv/streaming/timeshift.php?stream=12&extension=m3u8"))
    }

    /**
     * A `.ts` replay is a progressive stream with no duration and usually no byte ranges. The
     * artifact's flat no-scrub treatment exists so this case doesn't render a handle that ignores
     * drags — a dead handle reads as a broken app, an absent one reads as a provider fact.
     */
    @Test
    fun `a ts replay is not seekable`() {
        assertFalse(CatchUpPlayback.isSeekable("http://p.tv/timeshift/u/p/60/2026-08-15:18-30/12.ts"))
        // Extension-less php forms serve whatever the panel defaults to — assume the safe answer.
        assertFalse(CatchUpPlayback.isSeekable("http://p.tv/timeshift.php?stream=12&duration=60"))
        assertFalse(CatchUpPlayback.isSeekable(null))
    }

    /** A FINISHED programme is wholly recorded, so the bar spans the whole thing. */
    @Test
    fun `a finished programme scrubs to its own end`() {
        val start = now - 3 * 60 * minute
        val end = now - 2 * 60 * minute
        assertEquals(60 * minute, CatchUpPlayback.maxSeekPositionMs(start, end, now))
    }

    /**
     * Start-over: only the part that has already aired exists, and iptvsimple refuses seeks within
     * 1–2 minutes of the live edge because those segments have not been written yet. So the right
     * edge clamps short rather than offering a seek that returns nothing.
     */
    @Test
    fun `an airing programme clamps two minutes short of live`() {
        val start = now - 30 * minute
        val end = now + 30 * minute
        assertEquals(28 * minute, CatchUpPlayback.maxSeekPositionMs(start, end, now))
    }

    /** A programme that only just started has nothing seekable at all — never a negative bar. */
    @Test
    fun `a just-started programme clamps to zero rather than negative`() {
        val start = now - 30_000L
        val end = now + 60 * minute
        assertEquals(0L, CatchUpPlayback.maxSeekPositionMs(start, end, now))
    }

    /** The clamp never exceeds the programme's own length, whatever the clock says. */
    @Test
    fun `the clamp never exceeds the programme duration`() {
        val start = now - 10 * 60 * minute
        val end = now - 9 * 60 * minute
        assertEquals(60 * minute, CatchUpPlayback.maxSeekPositionMs(start, end, now))
    }

    // --- failure classification -----------------------------------------------------------

    /**
     * The walk advances on TRANSPORT only. A decode failure means the URL reached a stream whose
     * content is broken — another URL shape replays the same broken recording, so walking on is
     * both futile and a way to burn a max_connections=1 account's single slot.
     */
    @Test
    fun `transport-shaped failures advance the walk`() {
        val transport = listOf(
            "HTTP 404 Not Found",
            "Response code: 403",
            "java.net.SocketTimeoutException: timeout",
            "Unable to connect to host",
            "Connection refused",
            "Source error",
            "HTTP 500",
        )
        transport.forEach {
            assertEquals(
                CatchUpDialectWalk.FailureKind.TRANSPORT,
                CatchUpPlayback.failureKind(it),
                "expected TRANSPORT for: $it",
            )
        }
    }

    @Test
    fun `decode-shaped failures stop the walk`() {
        val decode = listOf(
            "MediaCodecRenderer decoder init failed",
            "Decoder init failed for video/avc",
            "Unsupported codec",
            "Renderer error: audio track",
        )
        decode.forEach {
            assertEquals(
                CatchUpDialectWalk.FailureKind.DECODE,
                CatchUpPlayback.failureKind(it),
                "expected DECODE for: $it",
            )
        }
    }

    /**
     * An unrecognised or absent message advances the walk. The dominant real failure IS the wrong
     * URL shape, and the walk terminates on its own after every dialect; guessing DECODE here
     * would stop on the first dialect and tell the viewer "unavailable" for a programme the panel
     * would happily have served in another form.
     */
    @Test
    fun `an unknown failure advances rather than terminating`() {
        assertEquals(CatchUpDialectWalk.FailureKind.TRANSPORT, CatchUpPlayback.failureKind(null))
        assertEquals(CatchUpDialectWalk.FailureKind.TRANSPORT, CatchUpPlayback.failureKind(""))
        assertEquals(CatchUpDialectWalk.FailureKind.TRANSPORT, CatchUpPlayback.failureKind("something went wrong"))
    }

    // --- Stalker's session cap ------------------------------------------------------------

    /**
     * `{error: 'limit'}` is the portal saying the account's concurrent-session cap is hit — the
     * user must close another device, and no amount of retrying will help. Reported as a generic
     * failure it sends people to Discord blaming the app for a subscription limit.
     */
    @Test
    fun `the stalker session cap is recognised as its own failure`() {
        assertTrue(CatchUpPlayback.isSessionLimit("""{"error":"limit"}"""))
        assertTrue(CatchUpPlayback.isSessionLimit("""{"js":"","error":"limit"}"""))
        assertTrue(CatchUpPlayback.isSessionLimit("{error: 'limit'}"))
    }

    @Test
    fun `an ordinary stalker fault is not the session cap`() {
        assertFalse(CatchUpPlayback.isSessionLimit("""{"error":"link_fault"}"""))
        assertFalse(CatchUpPlayback.isSessionLimit("HTTP 404"))
        assertFalse(CatchUpPlayback.isSessionLimit(null))
        // The word alone, without the error shape, must not trip it — channel names contain
        // anything at all ("No Limit TV").
        assertFalse(CatchUpPlayback.isSessionLimit("No Limit TV is unavailable"))
    }
}
