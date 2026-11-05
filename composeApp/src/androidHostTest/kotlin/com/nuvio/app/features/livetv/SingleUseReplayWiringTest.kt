package com.nuvio.app.features.livetv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The glue around a single-use (Stalker archive) replay link needs an Android player and a Compose screen to run, so
 * each guard is pinned on the source (the way NuvioTV's SingleUseReplayWiringTest does). Each of these failed on the code
 * before the 2026-11-05 review fixes.
 */
class SingleUseReplayWiringTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(path: String): String =
        File(root, "composeApp/src/$path").also { assertTrue(it.isFile, "missing $path") }.readText()

    private val engine get() = read("androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt")
    private val data get() = read("commonMain/kotlin/com/nuvio/app/features/livetv/LiveTvData.kt")
    private val screen get() = read("commonMain/kotlin/com/nuvio/app/features/livetv/LiveTvScreen.kt")
    private val epg get() = read("commonMain/kotlin/com/nuvio/app/features/iptv/CatchUpEpgRepository.kt")

    @Test
    fun `the exo to mpv failover is gated on the reconnect mode`() {
        val block = engine.substringAfter("onError = { message, linkAuthFailure ->").substringBefore("Log.w(TAG, \"ExoPlayer failed")
        assertTrue(block.contains("LiveLinkReconnectPolicy.allowsEngineFailover(linkReconnectMode)"))
    }

    @Test
    fun `the decoder fallback rebuild is gated and fed from the policy`() {
        val report = engine.substringAfter("fun reportPlayerError(error: PlaybackException) {").substringBefore("decoderPriorityOverride =")
        assertTrue(report.contains("allowDecoderFallback &&"), "reportPlayerError checks allowDecoderFallback first")
        assertTrue(engine.contains("allowDecoderFallback = LiveLinkReconnectPolicy.allowsDecoderFallback(linkReconnectMode)"))
    }

    @Test
    fun `the media cache and the transport retry are gated on the reconnect mode`() {
        assertTrue(engine.contains("allowMediaCache = LiveLinkReconnectPolicy.allowsMediaCache(linkReconnectMode)"))
        assertTrue(engine.contains("allowTransportRetry = LiveLinkReconnectPolicy.allowsTransportRetry(linkReconnectMode)"))
        assertTrue(engine.contains("!allowMediaCache"), "the data source skips the cache when not allowed")
    }

    @Test
    fun `both dns resolves of a link skip the redirect probe for a single-use link`() {
        assertEquals(2, Regex("resolveLivePlaybackUrl\\(").findAll(data).count())
        assertEquals(2, Regex("followRedirects = LiveLinkReconnectPolicy.allowsRedirectProbe\\(").findAll(data).count())
    }

    @Test
    fun `the archive link reads the guide so a failure throws instead of reading as no recording`() {
        val body = data.substringAfter("suspend fun stalkerArchiveLink(").substringBefore("/** The learned-winner memory")
        assertTrue(body.contains("ensureStalkerGuide("))
        assertTrue(body.contains("windowOrThrow("))
        assertFalse(body.contains("CatchUpEpgRepository.window("), "the lenient window turns a database error into empty")
    }

    @Test
    fun `guide reads never swallow cancellation`() {
        assertFalse(epg.contains("runCatching { com.nuvio.app.features.iptv.stalker.StalkerClient.ensureGuide"))
        assertTrue(epg.contains("GuideRead.softly("))
    }

    @Test
    fun `retry goes through the replay retry policy and a single-use replay mints`() {
        val retry = screen.substringAfter("val onRetry: () -> Unit = {").substringBefore("// One AUTOMATIC fresh re-resolve")
        assertTrue(retry.contains("ReplayRetryPolicy.stepFor("))
        val mint = retry.substringAfter("ReplayRetryPolicy.Step.MINT_FRESH").substringBefore("ReplayRetryPolicy.Step.RE_RESOLVE_REPLAY")
        assertTrue(mint.contains("openReplay("), "a fresh create_link, never the spent url")
        assertFalse(mint.contains("catchUpRetryTick"))
    }

    @Test
    fun `a single-use replay has no scrub handle`() {
        assertTrue(screen.contains("CatchUpPlayback.isSeekable(streamUrl, singleUse = session.singleUse)"))
    }
}
