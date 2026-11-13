package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.player.LiveRemintSpacingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * commonTest (kotlin.test) - `assertEquals(expected, actual, message)`.
 *
 * B259 follow-up: a real provider answers HTTP 401 at the first hop of a portal-host stream whenever the request
 * carries the MAG set-top-box User-Agent, and 200 + video for any other UA. Before 1.20.3 the KMP apps never sent the
 * MAG identity to a stream, so NEUTRAL is the default again; a portal-host live stream rejected before its first frame
 * is re-minted once with the other profile (a one-shot trial), and a trial's first frame is what the playlist learns.
 */
class StalkerStreamIdentityPolicyTest {
    private val portal = "http://portal.example"
    private val sameHost = "http://portal.example/play/live.php?stream=1&play_token=abc"
    private val identity = StalkerStreamIdentity(
        portalBaseUrl = portal,
        cookie = "mac=00%3A1A%3A79%3AAB%3ACD%3AEF; stb_lang=en",
        magUserAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp",
        xUserAgent = "Model: MAG250; Link: WiFi",
        token = "TOKEN123",
    )

    private val unauthorized = "Source error (HTTP 401)"

    private fun tune(memory: StalkerStreamProfileMemory = StalkerStreamProfileMemory(), playlist: String = "p1") =
        StalkerStreamFallback(playlist, memory)

    private fun opened(memory: StalkerStreamProfileMemory = StalkerStreamProfileMemory(), playlist: String = "p1") =
        tune(memory, playlist).also { it.onOpened(profileFallbackEligible = true) }

    @Test
    fun `a playlist opens neutral by default as before 1_20_3`() {
        val memory = StalkerStreamProfileMemory()
        assertEquals(StalkerStreamProfile.NEUTRAL, memory.profileFor("p1"), "nothing learned yet")
        assertEquals(StalkerStreamProfile.NEUTRAL, tune(memory).profile, "a fresh tune")
    }

    @Test
    fun `an auth rejection before the first frame tries the identity and re-mints at once`() {
        val fallback = opened()
        assertTrue(fallback.onOpenFailure(unauthorized), "a 401 at open on the neutral profile switches")
        assertEquals(StalkerStreamProfile.IDENTITY, fallback.profile, "the next attempt of this tune carries the MAG identity")
        // The switch re-mint skips the one-connection spacing: the provider refused the request, it holds nothing.
        assertEquals(
            0L,
            StalkerStreamIdentityPolicy.remintDelayMs(switchingProfile = true, singleUseLink = true, connectionEndedAtMs = 1_000L, nowMs = 1_000L),
            "the switch re-mint is immediate",
        )
        assertEquals(
            LiveRemintSpacingPolicy.MIN_GAP_MS,
            StalkerStreamIdentityPolicy.remintDelayMs(switchingProfile = false, singleUseLink = true, connectionEndedAtMs = 1_000L, nowMs = 1_000L),
            "every other automatic re-mint keeps the spacing",
        )
    }

    @Test
    fun `a 403 and an unnamed failed open also switch`() {
        assertTrue(opened().onOpenFailure("Source error (HTTP 403)"), "403 at open")
        // libmpv (Android mpv lane, iOS, Apple TV, desktop) ends a failed open with no status at all.
        assertTrue(opened().onOpenFailure("[mpv] loading failed"), "mpv names no status: still one switch")
        assertTrue(opened().onOpenFailure(null), "an ended-at-open with no message")
    }

    @Test
    fun `an open failure that names another cause does not switch`() {
        for (message in listOf("Source error (HTTP 404)", "Source error (HTTP 503)", "Connection timed out", "unsupported codec")) {
            val fallback = opened()
            assertFalse(fallback.onOpenFailure(message), "$message is not the profile being refused")
            assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile, "$message keeps the default")
        }
    }

    @Test
    fun `a named failure ends eligibility so a later unnamed one does not switch`() {
        val fallback = opened()
        assertFalse(fallback.onOpenFailure("[ffmpeg] http: HTTP error 404 Not Found"), "404 is not a refusal")
        assertFalse(fallback.onOpenFailure(null), "the same attempt's ended snapshot must not switch after the text")
        assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile)
    }

    @Test
    fun `an identity first frame is remembered for that playlist only`() {
        val memory = StalkerStreamProfileMemory()
        val fallback = opened(memory, playlist = "p1")
        assertTrue(fallback.onOpenFailure(unauthorized))
        assertEquals(StalkerStreamProfile.NEUTRAL, memory.profileFor("p1"), "nothing remembered before a frame")
        fallback.onOpened(profileFallbackEligible = true)
        fallback.onFirstFrame()
        assertEquals(StalkerStreamProfile.IDENTITY, memory.profileFor("p1"), "the identity played")
        assertEquals(StalkerStreamProfile.IDENTITY, tune(memory, playlist = "p1").profile, "the next tune opens with the identity")
        assertEquals(StalkerStreamProfile.NEUTRAL, memory.profileFor("p2"), "another playlist keeps the default")
        assertEquals(StalkerStreamProfile.NEUTRAL, tune(memory, playlist = "p2").profile, "another playlist tune")
    }

    @Test
    fun `a neutral first frame remembers nothing`() {
        val memory = StalkerStreamProfileMemory()
        opened(memory).onFirstFrame()
        assertEquals(StalkerStreamProfile.NEUTRAL, memory.profileFor("p1"))
    }

    @Test
    fun `an identity trial that fails goes back to neutral`() {
        val memory = StalkerStreamProfileMemory()
        val fallback = opened(memory)
        assertTrue(fallback.onOpenFailure(unauthorized))
        fallback.onOpened(profileFallbackEligible = true)
        assertFalse(fallback.onOpenFailure(unauthorized), "the identity failing takes the normal failure path")
        assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile, "the trial is one shot: later re-mints are neutral again")
        assertEquals(StalkerStreamProfile.NEUTRAL, memory.profileFor("p1"), "an identity that never played is not remembered")
        fallback.onOpened(profileFallbackEligible = true)
        assertFalse(fallback.onOpenFailure(unauthorized), "and no second switch in the tune")
    }

    @Test
    fun `the viewer retry starts a fresh tune`() {
        val fallback = opened()
        assertTrue(fallback.onOpenFailure(unauthorized))
        fallback.reset()
        assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile, "back to the learned profile")
        fallback.onOpened(profileFallbackEligible = true)
        assertTrue(fallback.onOpenFailure(unauthorized), "the fresh tune may switch once again")
    }

    @Test
    fun `a remembered identity tries neutral once when it fails before a frame`() {
        val memory = StalkerStreamProfileMemory().apply { rememberIdentity("p1") }
        val fallback = opened(memory)
        assertEquals(StalkerStreamProfile.IDENTITY, fallback.profile, "the tune starts on the learned profile")
        assertTrue(fallback.onOpenFailure(unauthorized), "identity refused at open: try neutral once")
        assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile)
        fallback.onOpened(profileFallbackEligible = true)
        fallback.onFirstFrame()
        assertEquals(StalkerStreamProfile.NEUTRAL, memory.profileFor("p1"), "a neutral first frame forgets the identity")
    }

    @Test
    fun `a remembered identity whose neutral trial fails stays on the identity`() {
        val memory = StalkerStreamProfileMemory().apply { rememberIdentity("p1") }
        val fallback = opened(memory)
        assertTrue(fallback.onOpenFailure(unauthorized))
        fallback.onOpened(profileFallbackEligible = true)
        assertFalse(fallback.onOpenFailure(unauthorized))
        assertEquals(StalkerStreamProfile.IDENTITY, fallback.profile, "back to the starting profile")
        assertEquals(StalkerStreamProfile.IDENTITY, memory.profileFor("p1"), "still learned")
    }

    @Test
    fun `a user agent override disables switching`() {
        val split = StalkerStreamHeaderPolicy.headersFor(identity, sameHost, "IBOPlayer/1.0")
        assertFalse(split.profileFallback, "the viewer chose the UA: never second-guessed")
        val fallback = tune()
        fallback.onOpened(profileFallbackEligible = split.profileFallback)
        assertFalse(fallback.onOpenFailure(unauthorized), "no switch with an override")
        assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile)
    }

    @Test
    fun `only a portal host open with no override may switch`() {
        assertTrue(StalkerStreamHeaderPolicy.headersFor(identity, sameHost, null).profileFallback, "same host, MAG identity")
        assertTrue(
            StalkerStreamHeaderPolicy.headersFor(identity, sameHost, null, StalkerStreamProfile.NEUTRAL).profileFallback,
            "a remembered neutral on the portal host may try identity",
        )
        assertFalse(
            StalkerStreamHeaderPolicy.headersFor(identity, "http://edge.cdn.example/live/1.ts", null).profileFallback,
            "a foreign host is always neutral",
        )
    }

    @Test
    fun `a failure after the first frame does not switch`() {
        val fallback = opened()
        fallback.onFirstFrame()
        assertFalse(fallback.onOpenFailure("[mpv] loading failed"), "the channel played: a drop is not the profile")
        assertFalse(fallback.onOpenFailure(unauthorized), "nor a later 401 once a frame showed")
        assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile)
    }

    @Test
    fun `a frame before this tune opened anything does not count`() {
        // The previous channel's surface can still tick (positionMs > 0) after a zap made this tune's fallback.
        val fallback = tune()
        fallback.onFirstFrame()
        fallback.onOpened(profileFallbackEligible = true)
        assertTrue(fallback.onOpenFailure(unauthorized), "the stale frame did not mark this tune started")
    }

    @Test
    fun `a source that is not eligible never switches`() {
        val fallback = tune()
        fallback.onOpened(profileFallbackEligible = false)
        assertFalse(fallback.onOpenFailure(unauthorized))
        assertFalse(tune().onOpenFailure(unauthorized), "nothing opened yet")
    }

    @Test
    fun `neutral headers on the portal host carry no identity at all`() {
        val split = StalkerStreamHeaderPolicy.headersFor(identity, sameHost, null, StalkerStreamProfile.NEUTRAL)
        assertEquals(
            mapOf("User-Agent" to StalkerStreamHeaderPolicy.PLAYER_USER_AGENT, "Accept" to "*/*"),
            split.public,
            "the credential-free player profile",
        )
        assertEquals(emptyMap(), split.originBound, "no Cookie, Authorization, Referer or Origin")
        val keys = (split.public + split.originBound).keys.map { it.lowercase() }
        for (banned in listOf("x-user-agent", "cookie", "authorization", "referer", "origin")) {
            assertFalse(banned in keys, "neutral sends no $banned")
        }
        assertFalse(split.public.values.any { "MAG" in it || "QtEmbedded" in it }, "no MAG user agent")
        assertFalse(split.public.values.any { "00%3A1A" in it || "TOKEN123" in it }, "no mac or token")
    }

    @Test
    fun `neutral keeps a user agent override`() {
        val split = StalkerStreamHeaderPolicy.headersFor(identity, sameHost, "IBOPlayer/1.0", StalkerStreamProfile.NEUTRAL)
        assertEquals("IBOPlayer/1.0", split.public["User-Agent"])
        assertEquals(emptyMap(), split.originBound)
    }

    @Test
    fun `the identity profile is unchanged`() {
        val split = StalkerStreamHeaderPolicy.headersFor(identity, sameHost, null, StalkerStreamProfile.IDENTITY)
        assertEquals(identity.magUserAgent, split.public["User-Agent"])
        assertEquals(identity.xUserAgent, split.public["X-User-Agent"])
        assertEquals(identity.cookie, split.originBound["Cookie"])
    }
}
