package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.FallbackTrigger
import com.nuvio.app.core.telemetry.api.PlaybackEngine
import com.nuvio.app.core.telemetry.api.PlaybackFailureProps
import com.nuvio.app.core.telemetry.api.PlaybackKind
import com.nuvio.app.core.telemetry.api.PlaybackSignal
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryBuckets
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryKeys
import com.nuvio.app.core.telemetry.api.QaMirror
import com.nuvio.app.core.telemetry.api.StallOutcome
import com.nuvio.app.core.telemetry.api.TeardownOutcome
import com.nuvio.app.core.telemetry.internal.MemoryContext
import com.nuvio.app.core.telemetry.internal.MemoryRecord
import com.nuvio.app.core.telemetry.internal.PlaybackTelemetryPrivacy
import com.nuvio.app.core.telemetry.internal.TelemetryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTelemetryBucketsTest {
    @Test
    fun `ttff buckets switch exactly on their lower bound`() {
        val cases = listOf(
            0L to "lt250", 249L to "lt250", 250L to "lt500", 499L to "lt500", 500L to "lt1000", 999L to "lt1000",
            1_000L to "lt2000", 1_999L to "lt2000", 2_000L to "lt4000", 3_999L to "lt4000", 4_000L to "lt8000",
            7_999L to "lt8000", 8_000L to "ge8000", 600_000L to "ge8000", -5L to "lt250",
        )
        for ((ms, bucket) in cases) assertEquals(bucket, PlaybackTelemetryBuckets.ttff(ms), "ttff $ms")
    }

    @Test
    fun `stall buckets switch exactly on their lower bound`() {
        val cases = listOf(
            0L to "lt2000", 1_999L to "lt2000", 2_000L to "lt5000", 4_999L to "lt5000", 5_000L to "lt15000",
            14_999L to "lt15000", 15_000L to "lt45000", 44_999L to "lt45000", 45_000L to "ge45000",
        )
        for ((ms, bucket) in cases) assertEquals(bucket, PlaybackTelemetryBuckets.stall(ms), "stall $ms")
    }

    @Test
    fun `teardown is slow only strictly over 1500 ms and failed wins`() {
        assertEquals(TeardownOutcome.CLEAN, PlaybackTelemetryBuckets.teardown(1_500L, failed = false))
        assertEquals(TeardownOutcome.SLOW, PlaybackTelemetryBuckets.teardown(1_501L, failed = false))
        assertEquals(TeardownOutcome.FAILED, PlaybackTelemetryBuckets.teardown(10L, failed = true))
        assertEquals(TeardownOutcome.FAILED, PlaybackTelemetryBuckets.teardown(9_000L, failed = true))
    }

    @Test
    fun `ram class boundaries`() {
        assertEquals("lt1_2", PlaybackTelemetryBuckets.ramClass(1_228L))
        assertEquals("lt2", PlaybackTelemetryBuckets.ramClass(1_229L))
        assertEquals("lt2", PlaybackTelemetryBuckets.ramClass(2_047L))
        assertEquals("lt3", PlaybackTelemetryBuckets.ramClass(2_048L))
        assertEquals("lt3", PlaybackTelemetryBuckets.ramClass(3_071L))
        assertEquals("ge3", PlaybackTelemetryBuckets.ramClass(3_072L))
    }

    @Test
    fun `failure classes map to their closed sets`() {
        assertEquals("m3u8", PlaybackFailureProps.extClass("https://h.example/live/1.m3u8?token=x"))
        assertEquals("ts", PlaybackFailureProps.extClass("http://h/live/1.TS"))
        assertEquals("none", PlaybackFailureProps.extClass("http://h/live/1"))
        assertEquals("none", PlaybackFailureProps.extClass(null))
        assertEquals("other", PlaybackFailureProps.extClass("http://h/a.avi"))
        assertEquals("sd", PlaybackFailureProps.resClass(576))
        assertEquals("hd720", PlaybackFailureProps.resClass(720))
        assertEquals("fhd", PlaybackFailureProps.resClass(1_080))
        assertEquals("uhd", PlaybackFailureProps.resClass(2_160))
        assertEquals("unknown", PlaybackFailureProps.resClass(0))
        assertEquals("401_403", PlaybackFailureProps.httpClass("http_403"))
        assertEquals("404", PlaybackFailureProps.httpClass("http_404"))
        assertEquals("456", PlaybackFailureProps.httpClass("http_456"))
        assertEquals("4xx_other", PlaybackFailureProps.httpClass("http_429"))
        assertEquals("5xx", PlaybackFailureProps.httpClass("http_503"))
        assertEquals("none", PlaybackFailureProps.httpClass("network"))
        assertEquals("hls", PlaybackFailureProps.contentTypeClass("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals("html", PlaybackFailureProps.contentTypeClass("text/html"))
        assertEquals("stalker", PlaybackFailureProps.sourceType("stalker:1", isStalker = true))
        assertEquals("xtream", PlaybackFailureProps.sourceType("xtream:7", isStalker = false))
    }

    @Test
    fun `engine labels and fallback triggers are closed`() {
        assertEquals(PlaybackEngine.MEDIA3, PlaybackEngine.fromLabel("ExoPlayer"))
        assertEquals(PlaybackEngine.LIBMPV, PlaybackEngine.fromLabel("libmpv"))
        assertEquals(PlaybackEngine.OTHER, PlaybackEngine.fromLabel("https://evil.example"))
        assertEquals(PlaybackEngine.OTHER, PlaybackEngine.fromLabel(null))
        assertEquals(FallbackTrigger.TIMEOUT, FallbackTrigger.fromFailureCategory("timeout"))
        assertEquals(FallbackTrigger.NETWORK, FallbackTrigger.fromFailureCategory("http_500"))
        assertEquals(FallbackTrigger.DECODER_FAILED, FallbackTrigger.fromFailureCategory("decoder"))
        assertEquals(FallbackTrigger.OTHER, FallbackTrigger.fromFailureCategory("unknown"))
    }
}

class PlaybackTelemetryKeysTest {
    @Test
    fun `count keys are the contract strings`() {
        assertEquals("ttff:live:media3:lt2000", PlaybackTelemetryKeys.ttff(PlaybackKind.LIVE, PlaybackEngine.MEDIA3, 1_840L))
        assertEquals("ttff:vod:libmpv:ge8000", PlaybackTelemetryKeys.ttff(PlaybackKind.VOD, PlaybackEngine.LIBMPV, 9_000L))
        assertEquals(
            "fallback:media3:libmpv:decoder_failed",
            PlaybackTelemetryKeys.fallback(PlaybackEngine.MEDIA3, PlaybackEngine.LIBMPV, FallbackTrigger.DECODER_FAILED),
        )
        assertEquals("stall:live:gave_up", PlaybackTelemetryKeys.stall(PlaybackKind.LIVE, StallOutcome.GAVE_UP))
        assertEquals("stall:live:abandoned", PlaybackTelemetryKeys.stall(PlaybackKind.LIVE, StallOutcome.ABANDONED))
        assertEquals("stall_ms:live:lt15000", PlaybackTelemetryKeys.stallMs(PlaybackKind.LIVE, 9_000L))
        assertEquals("teardown:libmpv:slow", PlaybackTelemetryKeys.teardown(PlaybackEngine.LIBMPV, TeardownOutcome.SLOW))
    }

    @Test
    fun `a stall signal makes an outcome key and a duration key`() {
        assertEquals(
            listOf("stall:live:recovered", "stall_ms:live:lt5000"),
            PlaybackTelemetryKeys.forSignal(PlaybackSignal.Stall(PlaybackKind.LIVE, StallOutcome.RECOVERED, 3_000L)),
        )
    }

    @Test
    fun `the abandoned stall outcome is a valid closed key with its duration bucket`() {
        assertEquals(
            listOf("stall:live:abandoned", "stall_ms:live:ge45000"),
            PlaybackTelemetryKeys.forSignal(PlaybackSignal.Stall(PlaybackKind.LIVE, StallOutcome.ABANDONED, 60_000L)),
        )
        assertTrue(PlaybackTelemetryKeys.isValidKey("stall:vod:abandoned"))
        assertFalse(PlaybackTelemetryKeys.isValidKey("stall:live:left"))
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("n_stall:live:abandoned" to 1)).isEmpty())
        assertEquals(1, PlaybackTelemetryPrivacy.violations(mapOf("n_stall:live:left" to 1)).size)
    }

    @Test
    fun `only keys built from the closed vocabularies are valid`() {
        assertTrue(PlaybackTelemetryKeys.isValidKey("ttff:live:media3:lt250"))
        assertFalse(PlaybackTelemetryKeys.isValidKey("ttff:live:media3:lt251"))
        assertFalse(PlaybackTelemetryKeys.isValidKey("ttff:live:evil.example.com:lt250"))
        assertFalse(PlaybackTelemetryKeys.isValidKey("fallback:media3:libmpv"))
        assertFalse(PlaybackTelemetryKeys.isValidKey("playback_started:exo"))
        assertFalse(PlaybackTelemetryKeys.isValidKey(""))
    }

    @Test
    fun `every signal the app can build yields valid keys`() {
        val signals = buildList<PlaybackSignal> {
            for (k in PlaybackKind.entries) for (e in PlaybackEngine.entries) for (ms in listOf(0L, 400L, 3_000L, 99_999L)) add(PlaybackSignal.Ttff(k, e, ms))
            for (f in PlaybackEngine.entries) for (t in PlaybackEngine.entries) for (tr in FallbackTrigger.entries) add(PlaybackSignal.Fallback(f, t, tr))
            for (k in PlaybackKind.entries) for (o in StallOutcome.entries) for (ms in listOf(0L, 6_000L, 60_000L)) add(PlaybackSignal.Stall(k, o, ms))
            for (e in PlaybackEngine.entries) for (ms in listOf(10L, 2_000L)) for (failed in listOf(false, true)) add(PlaybackSignal.Teardown(e, ms, failed))
        }
        for (s in signals) for (key in PlaybackTelemetryKeys.forSignal(s)) assertTrue(PlaybackTelemetryKeys.isValidKey(key), key)
    }
}

class QaMirrorTest {
    @Test
    fun `only the value 1 enables the mirror`() {
        assertTrue(QaMirror.isEnabledValue("1"))
        assertTrue(QaMirror.isEnabledValue(" 1 "))
        assertFalse(QaMirror.isEnabledValue("0"))
        assertFalse(QaMirror.isEnabledValue(""))
        assertFalse(QaMirror.isEnabledValue("true"))
        assertFalse(QaMirror.isEnabledValue(null))
    }

    @Test
    fun `an off mirror writes nothing and an on mirror writes one json line with exact values`() {
        val lines = ArrayList<String>()
        val (ev, fields) = QaMirror.fieldsFor(PlaybackSignal.Ttff(PlaybackKind.LIVE, PlaybackEngine.MEDIA3, 1_840L))
        QaMirror.mirror(false, { lines += it }, ev, fields)
        assertTrue(lines.isEmpty())
        QaMirror.mirror(true, { lines += it }, ev, fields)
        assertEquals(listOf("""{"ev":"ttff","kind":"live","engine":"media3","ms":1840}"""), lines)
    }

    @Test
    fun `a throwing sink never reaches the caller`() {
        QaMirror.mirror(true, { error("log down") }, "ttff", mapOf("ms" to 1L))
    }
}

class PlaybackTelemetryServiceTest {
    private fun service(host: FakeTelemetryHost) = TelemetryService(host, scope = CoroutineScope(Dispatchers.Unconfined))

    private val signals = listOf(
        PlaybackSignal.Ttff(PlaybackKind.LIVE, PlaybackEngine.MEDIA3, 1_840L),
        PlaybackSignal.Ttff(PlaybackKind.LIVE, PlaybackEngine.MEDIA3, 1_100L),
        PlaybackSignal.Fallback(PlaybackEngine.MEDIA3, PlaybackEngine.LIBMPV, FallbackTrigger.TIMEOUT),
        PlaybackSignal.Stall(PlaybackKind.LIVE, StallOutcome.RECOVERED, 3_000L),
        PlaybackSignal.Teardown(PlaybackEngine.LIBMPV, 2_000L, failed = false),
    )

    @Test
    fun `routine playback successes only increment session health counts and send no event`() {
        val host = FakeTelemetryHost()
        val s = service(host)
        signals.forEach(s::recordPlayback)
        assertTrue(host.sent.isEmpty(), "nothing is sent before the summary")
        s.onBackground()
        assertEquals(listOf("session_health"), host.sent.map { it.event }.filter { it == "session_health" })
        assertTrue(host.sent.none { it.event.startsWith("ttff") || it.event.startsWith("fallback") || it.event.startsWith("stall") || it.event.startsWith("teardown") })
        val p = host.sent.single { it.event == "session_health" }.props
        assertEquals(2, p["n_ttff:live:media3:lt2000"])
        assertEquals(1, p["n_fallback:media3:libmpv:timeout"])
        assertEquals(1, p["n_stall:live:recovered"])
        assertEquals(1, p["n_stall_ms:live:lt5000"])
        assertEquals(1, p["n_teardown:libmpv:slow"])
        assertTrue(PlaybackTelemetryPrivacy.violations(p).isEmpty(), PlaybackTelemetryPrivacy.violations(p).toString())
    }

    @Test
    fun `with the reports switch off nothing is counted or sent`() {
        val host = FakeTelemetryHost(consent = false)
        val s = service(host)
        signals.forEach(s::recordPlayback)
        s.onBackground()
        assertTrue(host.sent.isEmpty())
        host.consent = true
        s.onBackground()
        assertTrue(host.sent.none { it.event == "session_health" }, "counts recorded while off must not appear later")
    }

    @Test
    fun `the qa mirror is gated by the host switch and carries exact values`() {
        val off = FakeTelemetryHost()
        service(off).recordPlayback(signals[0])
        assertTrue(off.qaLines.isEmpty())

        val on = FakeTelemetryHost().also { it.qaMirrorEnabled = true }
        val s = service(on)
        s.recordPlayback(signals[0])
        assertEquals("""{"ev":"ttff","kind":"live","engine":"media3","ms":1840}""", on.qaLines.single())
        s.sampleMemoryOnce()
        assertTrue(on.qaLines.last().startsWith("""{"ev":"mem","""), on.qaLines.last())
        assertTrue(on.sent.isEmpty(), "the mirror never sends")
    }

    @Test
    fun `an app exit carries the previous run memory and the prev breadcrumb`() {
        val host = FakeTelemetryHost()
        host.memory = mapOf("pss_mb" to 410L, "java_mb" to 120L, "native_mb" to 90L, "avail_mb" to 300L, "threads" to 88L, "fds" to 210L)
        service(host).sampleMemoryOnce() // the run that is about to be killed
        val next = service(host) // the next launch
        next.emit(
            "app_exit",
            mapOf(
                "reason" to "crash", "importance" to 100, "exit_timestamp_ms" to host.now + 5_000L,
                "was_playing" to true, "playback_kind" to "live", "playback_engine" to "ExoPlayer",
            ),
        )
        val sent = host.sent.single { it.event == "app_exit" }.props
        assertEquals(410L, sent["mem_pss_mb"])
        assertEquals(88L, sent["mem_threads"])
        assertEquals(210L, sent["mem_fds"])
        assertEquals(300L, sent["mem_avail_mb"])
        assertEquals(true, sent["prev_playing"])
        assertEquals("live", sent["prev_kind"])
        assertEquals("media3", sent["prev_engine"])
        val added = sent.filterKeys { it.startsWith("mem_") || it.startsWith("prev_") }
        assertTrue(PlaybackTelemetryPrivacy.violations(added).isEmpty(), PlaybackTelemetryPrivacy.violations(added).toString())
    }

    @Test
    fun `an exit older than the sample does not borrow its memory`() {
        val host = FakeTelemetryHost()
        service(host).sampleMemoryOnce()
        val next = service(host)
        next.emit("app_exit", mapOf("reason" to "crash", "importance" to 100, "exit_timestamp_ms" to host.now - 60_000L))
        assertNull(host.sent.single { it.event == "app_exit" }.props["mem_pss_mb"])
    }

    @Test
    fun `memory is not persisted when the reports switch is off`() {
        val host = FakeTelemetryHost(consent = false)
        service(host).sampleMemoryOnce()
        assertNull(host.store.read(MemoryContext.FILE))
    }

    @Test
    fun `unclean exit sends one clean app exit and nothing with the switch off`() {
        val host = FakeTelemetryHost()
        service(host).sampleMemoryOnce()
        val next = service(host)
        next.recordUncleanExit(wasPlaying = true, kind = "live", engine = "libmpv")
        val sent = host.sent.single { it.event == "app_exit" }.props
        assertEquals("unclean_exit_suspected", sent["reason"])
        assertEquals(true, sent["was_playing"])
        assertEquals("libmpv", sent["prev_engine"])
        assertEquals(410L, sent["mem_pss_mb"])
        val checked = sent - "diag_trail"
        assertTrue(PlaybackTelemetryPrivacy.violations(checked).isEmpty(), PlaybackTelemetryPrivacy.violations(checked).toString())

        val off = FakeTelemetryHost(consent = false)
        service(off).recordUncleanExit(wasPlaying = true, kind = "live", engine = "libmpv")
        assertTrue(off.sent.isEmpty())
    }

    @Test
    fun `memory context round trips and drops unknown keys from events`() {
        val record = MemoryRecord(42L, mapOf("pss_mb" to 1L, "secret" to 2L))
        val back = MemoryContext.decode(MemoryContext.encode(record))
        assertEquals(record, back)
        assertEquals(mapOf("mem_pss_mb" to 1L), MemoryContext.asProperties(record.sample))
        assertNull(MemoryContext.decode("not json"))
    }
}

class PlaybackTelemetryPrivacyTest {
    @Test
    fun `the validator rejects forbidden names and open strings`() {
        val bad = mapOf<String, Any>(
            "url" to "http://h/x", "token" to "t", "code" to "x", "state" to "s", "host" to "h", "mac" to "m",
            "ext_class" to "mp3", "source_type" to "portal.example.com", "extra" to "free text", "mem_pss_mb" to "410",
        )
        val v = PlaybackTelemetryPrivacy.violations(bad)
        assertEquals(10, v.size, v.toString())
    }

    @Test
    fun `every failure property value is inside its enum`() {
        val urls = listOf(null, "", "http://h/a.m3u8", "http://h/a.ts?x=1", "http://h/a", "http://h/a.avi", "rtmp://h/live")
        for (u in urls) {
            val p = mapOf<String, Any>("ext_class" to PlaybackFailureProps.extClass(u))
            assertTrue(PlaybackTelemetryPrivacy.violations(p).isEmpty(), "ext $u")
        }
        for (h in listOf(-1, 0, 100, 576, 720, 1_080, 1_440, 2_160, 4_320)) {
            assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("res_class" to PlaybackFailureProps.resClass(h))).isEmpty(), "res $h")
        }
        for (c in listOf(null, "network", "timeout", "http_200", "http_301", "http_401", "http_404", "http_456", "http_418", "http_502", "http_999")) {
            assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("http_class" to PlaybackFailureProps.httpClass(c))).isEmpty(), "http $c")
        }
        for (mb in listOf(0L, 1_000L, 1_500L, 2_500L, 8_000L)) {
            assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("ram_class" to PlaybackTelemetryBuckets.ramClass(mb))).isEmpty(), "ram $mb")
        }
        for (t in listOf("a.example.com", "Content-Type: x", null)) {
            assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("content_type_class" to PlaybackFailureProps.contentTypeClass(t))).isEmpty())
        }
    }

    @Test
    fun `an open count key is a violation and a closed one is not`() {
        assertEquals(1, PlaybackTelemetryPrivacy.violations(mapOf("n_ttff:live:evil.com:lt250" to 1)).size)
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("n_ttff:live:media3:lt250" to 1)).isEmpty())
    }
}
