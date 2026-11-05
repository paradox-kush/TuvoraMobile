package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the M3U catch-up URL builder. Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0).
 * Fixed instant: 2024-01-02T03:04:05Z = 1704164645 s; the programme lasts 30 min; "now" is 2 h in.
 */
class M3uCatchUpTest {
    private val startS = 1_704_164_645L
    private val startMs = startS * 1000
    private val endS = startS + 1800
    private val endMs = endS * 1000
    private val nowS = startS + 7200
    private val nowMs = nowS * 1000
    private val live = "http://h.example:8080/live/user/pass/123.ts"
    private val flat = "http://h.example/chan/index.m3u8"

    private fun info(type: M3uCatchUpType, source: String? = null, days: Int = 3) =
        M3uCatchUpInfo(type, source, days)

    private fun build(i: M3uCatchUpInfo, url: String = live, offset: Long = 0L) =
        M3uCatchUp.build(i, url, startMs, endMs, nowMs, offset)

    // ---- type mapping -------------------------------------------------------------------

    @Test
    fun typeAliasesMapToStyles() {
        assertEquals(M3uCatchUpType.APPEND, M3uCatchUp.catchUpType("append", null))
        assertEquals(M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType("default", null))
        assertEquals(M3uCatchUpType.SHIFT, M3uCatchUp.catchUpType("shift", null))
        assertEquals(M3uCatchUpType.SHIFT, M3uCatchUp.catchUpType("TimeShift", null))
        for (a in listOf("flussonic", "flussonic-hls", "flussonic-ts", "fs")) {
            assertEquals(M3uCatchUpType.FLUSSONIC, M3uCatchUp.catchUpType(a, null), a)
        }
        assertEquals(M3uCatchUpType.XTREAM, M3uCatchUp.catchUpType("xc", null))
        assertEquals(M3uCatchUpType.XTREAM, M3uCatchUp.catchUpType("xtream", null))
    }

    @Test
    fun missingTypeDefaultsAndUnknownNeedsSource() {
        assertEquals(M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType(null, null))
        assertEquals(M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType("  ", "?x={utc}"))
        assertEquals(M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType("weird", "http://a/{utc}"))
        assertNull(M3uCatchUp.catchUpType("weird", null), "unknown style with no template cannot be built")
    }

    // ---- append / default ---------------------------------------------------------------

    @Test
    fun appendConcatenatesFilledTemplate() {
        val r = build(info(M3uCatchUpType.APPEND, "?utc={utc}&lutc={lutc}"))
        assertEquals("$live?utc=$startS&lutc=$nowS", r)
    }

    @Test
    fun appendWithoutSourceIsNull() {
        assertNull(build(info(M3uCatchUpType.APPEND, null)))
    }

    @Test
    fun defaultAbsoluteTemplateReplacesLiveUrl() {
        val r = build(info(M3uCatchUpType.DEFAULT, "http://arch.example/{utc}/{duration}.m3u8"))
        assertEquals("http://arch.example/$startS/1800.m3u8", r)
    }

    @Test
    fun defaultRelativeTemplateIsAppendedToLiveUrl() {
        val r = build(info(M3uCatchUpType.DEFAULT, "?start=\${start}&end=\${end}"))
        assertEquals("$live?start=$startS&end=$endS", r)
    }

    @Test
    fun defaultAbsoluteDetectionIsCaseInsensitive() {
        val r = build(info(M3uCatchUpType.DEFAULT, "HTTPS://a.example/{utc}"))
        assertEquals("HTTPS://a.example/$startS", r)
    }

    @Test
    fun defaultWithoutSourceOnXtreamUrlUsesXtreamShape() {
        val r = build(info(M3uCatchUpType.DEFAULT, null))
        assertEquals("http://h.example:8080/timeshift/user/pass/30/2024-01-02:03-04/123.ts", r)
    }

    @Test
    fun defaultWithoutSourceOnOtherUrlFallsBackToShift() {
        val r = build(info(M3uCatchUpType.DEFAULT, ""), flat)
        assertEquals("$flat?utc=$startS&lutc=$nowS", r)
    }

    // ---- shift --------------------------------------------------------------------------

    @Test
    fun shiftAddsUtcAndLutcQuery() {
        assertEquals("$flat?utc=$startS&lutc=$nowS", build(info(M3uCatchUpType.SHIFT, "x"), flat))
    }

    @Test
    fun shiftUsesAmpersandWhenUrlAlreadyHasQuery() {
        assertEquals("$flat?tok=1&utc=$startS&lutc=$nowS", build(info(M3uCatchUpType.SHIFT, "x"), "$flat?tok=1"))
    }

    @Test
    fun shiftWithoutSourceGuessesXtreamOnXtreamUrls() {
        assertEquals(
            "http://h.example:8080/timeshift/user/pass/30/2024-01-02:03-04/123.ts",
            build(info(M3uCatchUpType.SHIFT, null)),
        )
        assertEquals("$flat?utc=$startS&lutc=$nowS", build(info(M3uCatchUpType.SHIFT, null), flat))
    }

    // ---- flussonic ----------------------------------------------------------------------

    @Test
    fun flussonicHlsForm() {
        assertEquals(
            "http://h.example/chan/index-$startS-1800.m3u8",
            build(info(M3uCatchUpType.FLUSSONIC), flat),
        )
    }

    @Test
    fun flussonicHlsFormKeepsQuery() {
        assertEquals(
            "http://h.example/chan/index-$startS-1800.m3u8?token=a",
            build(info(M3uCatchUpType.FLUSSONIC), "$flat?token=a"),
        )
    }

    @Test
    fun flussonicTsForm() {
        assertEquals(
            "http://h.example/chan/timeshift_abs-$startS.ts",
            build(info(M3uCatchUpType.FLUSSONIC), "http://h.example/chan/mpegts"),
        )
    }

    @Test
    fun flussonicTrailingSlashUsesIndexName() {
        assertEquals(
            "http://h.example/chan/index-$startS-1800.m3u8",
            build(info(M3uCatchUpType.FLUSSONIC), "http://h.example/chan/"),
        )
    }

    @Test
    fun flussonicRejectsUnparsableUrl() {
        assertNull(build(info(M3uCatchUpType.FLUSSONIC), "http://h.example"))
    }

    // ---- xtream (delegates to XtreamCatchUp) --------------------------------------------

    @Test
    fun xtreamMatchesXtreamCatchUpFirstCandidate() {
        val expected = XtreamCatchUp.candidateUrls("http://h.example:8080", "user", "pass", 123, startMs, endMs, "ts").first()
        assertEquals(expected, build(info(M3uCatchUpType.XTREAM)))
    }

    @Test
    fun xtreamDecodesCredentialsBeforeReEncoding() {
        assertEquals(
            "http://h.example/timeshift/us%20er/pa%2Fss/30/2024-01-02:03-04/9.ts",
            build(info(M3uCatchUpType.XTREAM), "http://h.example/us%20er/pa%2Fss/9.ts"),
        )
    }

    @Test
    fun xtreamKeepsUrlExtensionAndAcceptsNoLiveSegment() {
        assertEquals(
            "http://h.example/timeshift/user/pass/30/2024-01-02:03-04/9.m3u8",
            build(info(M3uCatchUpType.XTREAM), "http://h.example/user/pass/9.m3u8"),
        )
    }

    @Test
    fun xtreamServerOffsetShiftsStamp() {
        assertEquals(
            "http://h.example:8080/timeshift/user/pass/30/2024-01-02:08-04/123.ts",
            build(info(M3uCatchUpType.XTREAM), offset = 5 * 3_600_000L),
        )
    }

    @Test
    fun xtreamRejectsNonXtreamUrl() {
        assertNull(build(info(M3uCatchUpType.XTREAM), flat))
    }

    // ---- placeholders -------------------------------------------------------------------

    private fun fill(t: String, offset: Long = 0L) =
        M3uCatchUp.fill(t, startS, endS, nowS, 1800L, offset)

    @Test
    fun simplePlaceholders() {
        assertEquals("$startS $startS", fill("{utc} {start}"))
        assertEquals("$endS $endS", fill("{utcend} {end}"))
        assertEquals("$nowS $nowS $nowS", fill("{lutc} {now} {timestamp}"))
        assertEquals("1800", fill("{duration}"))
        assertEquals("7200", fill("{offset}"))
    }

    @Test
    fun dollarBracePlaceholdersAreSupported() {
        assertEquals("$startS-$endS-$nowS-1800-7200", fill("\${utc}-\${utcend}-\${lutc}-\${duration}-\${offset}"))
    }

    @Test
    fun divisorPlaceholders() {
        assertEquals("30", fill("{duration:60}"))
        assertEquals("120", fill("{offset:60}"))
        assertEquals("1800", fill("{duration:0}"), "zero divisor is clamped to 1")
    }

    @Test
    fun formattedPlaceholdersAreUtc() {
        assertEquals("2024-01-02 03:04:05", fill("{utc:Y-m-d H:M:S}"))
        assertEquals("20240102T030405", fill("{start:YmdTHMS}"))
        assertEquals("2024010203", fill("{start:YmdH}"))
        assertEquals("03:34:05", fill("{utcend:H:M:S}"))
        assertEquals("05:04", fill("{now:H:M}"))
        assertEquals("2024-01-02", fill("\${start:Y-m-d}"))
    }

    @Test
    fun formattedLiteralLettersSurvive() {
        assertEquals("T20240102Z", fill("{utc:TYmdZ}"))
    }

    @Test
    fun bareLocalPlaceholdersUseExplicitOffset() {
        assertEquals("2024-01-02 03:04:05", fill("{Y}-{m}-{d} {H}:{M}:{S}"))
        assertEquals("2024-01-02 08:34:05", fill("{Y}-{m}-{d} {H}:{M}:{S}", 5 * 3_600_000L + 30 * 60_000L))
        assertEquals("2024-01-01 22:04:05", fill("{Y}-{m}-{d} {H}:{M}:{S}", -5 * 3_600_000L))
    }

    @Test
    fun unknownPlaceholdersAreLeftAlone() {
        assertEquals("{foo}", fill("{foo}"))
    }

    @Test
    fun durationIsAtLeastOneMinute() {
        val r = M3uCatchUp.build(info(M3uCatchUpType.APPEND, "?d={duration}"), live, startMs, startMs + 5_000, nowMs)
        assertEquals("$live?d=60", r)
    }

    // ---- alternatives -------------------------------------------------------------------

    private val primary = "http://h.example:8080/timeshift/user/pass/30/2024-01-02:03-04/123.ts"
    private val php = "http://h.example:8080/streaming/timeshift.php?username=user&password=pass&stream=123&start=2024-01-02:03-04&duration=30"

    @Test
    fun alternativesTsPrimaryOffersHlsThenPhp() {
        assertEquals(
            listOf("http://h.example:8080/timeshift/user/pass/30/2024-01-02:03-04/123.m3u8", php),
            M3uCatchUp.alternatives(primary),
        )
    }

    @Test
    fun alternativesHlsPrimaryOffersTsFirst() {
        val hls = primary.replace(".ts", ".m3u8")
        assertEquals(primary, M3uCatchUp.alternatives(hls).first())
    }

    @Test
    fun alternativesAiredBeforeShortBeforePhpBeforeClock() {
        val r = M3uCatchUp.alternatives(
            primary, showMinutes = 20, airedMinutes = 10, offsetMs = 0L, otherOffsetMs = 3_600_000L,
        )
        val b = "http://h.example:8080/timeshift/user/pass"
        assertEquals(
            listOf(
                "$b/30/2024-01-02:03-04/123.m3u8",
                "$b/10/2024-01-02:03-04/123.ts",
                "$b/10/2024-01-02:03-04/123.m3u8",
                "$b/20/2024-01-02:03-04/123.ts",
                "$b/20/2024-01-02:03-04/123.m3u8",
                php,
                "$b/30/2024-01-02:04-04/123.ts",
            ),
            r,
        )
    }

    @Test
    fun alternativesDefaultOtherClockIsUtcWhenStampHasAnOffset() {
        // Stamp written in a +1 h panel clock (04:04 shown for 03:04 UTC): the other clock is UTC.
        val p = "http://h.example/timeshift/u/p/30/2024-01-02:04-04/5.ts"
        val r = M3uCatchUp.alternatives(p, offsetMs = 3_600_000L)
        assertTrue(r.contains("http://h.example/timeshift/u/p/30/2024-01-02:03-04/5.ts"), r.toString())
    }

    @Test
    fun alternativesDefaultHasNoClockLinkForUtcStamp() {
        assertEquals(2, M3uCatchUp.alternatives(primary).size)
    }

    @Test
    fun alternativesPhpFormComesFromXtreamCatchUp() {
        val fromXtream = XtreamCatchUp.candidateUrls(
            "http://h.example:8080", "user", "pass", 123, startS * 1000 / 60_000 * 60_000, startS * 1000 / 60_000 * 60_000 + 30 * 60_000L, null,
        ).first { "/streaming/timeshift.php?" in it && "extension=" !in it }
        assertEquals(fromXtream, M3uCatchUp.alternatives(primary)[1])
    }

    @Test
    fun alternativesSkipAiredAndShortWhenNotShorter() {
        val r = M3uCatchUp.alternatives(primary, showMinutes = 30, airedMinutes = 45)
        assertEquals(2, r.size)
    }

    @Test
    fun alternativesClockAcrossMidnight() {
        val p = "http://h.example/timeshift/u/p/30/2024-01-02:23-30/5.ts"
        val r = M3uCatchUp.alternatives(p, offsetMs = 0L, otherOffsetMs = 3_600_000L)
        assertTrue(r.contains("http://h.example/timeshift/u/p/30/2024-01-03:00-30/5.ts"), r.toString())
    }

    @Test
    fun alternativesNeverIncludeTheInputUrl() {
        val all = M3uCatchUp.alternatives(primary, showMinutes = 10, airedMinutes = 5, otherOffsetMs = 1000L * 3600)
        assertFalse(all.contains(primary))
    }

    @Test
    fun alternativesForNonTimeshiftUrlOnlyTsFallback() {
        assertEquals(emptyList(), M3uCatchUp.alternatives("http://a/b/c.m3u8"))
        assertEquals(
            listOf("http://a/timeshift/x.ts?k=1"),
            M3uCatchUp.alternatives("http://a/timeshift/x.m3u8?k=1"),
        )
    }

    // ---- link forms ---------------------------------------------------------------------

    @Test
    fun linkFormClassifiesAgainstPrimary() {
        val b = "http://h.example:8080/timeshift/user/pass"
        assertEquals("ts", M3uCatchUp.linkForm(primary, primary))
        assertEquals("hls", M3uCatchUp.linkForm("$b/30/2024-01-02:03-04/123.m3u8", primary))
        assertEquals("php", M3uCatchUp.linkForm(php, primary))
        assertEquals("ts+clock", M3uCatchUp.linkForm("$b/30/2024-01-02:04-04/123.ts", primary))
        assertEquals("hls+short", M3uCatchUp.linkForm("$b/20/2024-01-02:03-04/123.m3u8", primary))
        assertEquals("ts+clock+short", M3uCatchUp.linkForm("$b/20/2024-01-02:04-04/123.ts", primary))
    }

    @Test
    fun preferFormMovesWinnerFirst() {
        val alts = M3uCatchUp.alternatives(primary)
        assertEquals(listOf(php, primary, alts[0]), M3uCatchUp.preferForm(primary, alts, "php"))
        assertEquals(listOf(primary) + alts, M3uCatchUp.preferForm(primary, alts, null))
    }

    @Test
    fun preferFormDoesNotReturnToHlsWhenPrimaryIsTs() {
        val alts = M3uCatchUp.alternatives(primary)
        assertEquals(listOf(primary) + alts, M3uCatchUp.preferForm(primary, alts, "hls"))
    }

    @Test
    fun preferFormFallsBackToFormWithoutShort() {
        val p = primary.replace(".ts", ".m3u8")
        val alts = M3uCatchUp.alternatives(p)
        val r = M3uCatchUp.preferForm(p, alts, "ts+short")
        assertEquals(primary, r.first())
    }

    @Test
    fun wholeMinutesRecognisesXtreamForms() {
        assertTrue(M3uCatchUp.wholeMinutes(primary))
        assertTrue(M3uCatchUp.wholeMinutes(php))
        assertFalse(M3uCatchUp.wholeMinutes(flat))
    }

    // ---- availability -------------------------------------------------------------------

    @Test
    fun availabilityRespectsDaysAndFuture() {
        val day = 86_400_000L
        val i = info(M3uCatchUpType.DEFAULT, days = 2)
        assertTrue(M3uCatchUp.isAvailable(i, nowMs - day, nowMs))
        assertFalse(M3uCatchUp.isAvailable(i, nowMs - 3 * day, nowMs))
        assertFalse(M3uCatchUp.isAvailable(i, nowMs + 1, nowMs))
        assertFalse(M3uCatchUp.isAvailable(null, nowMs - 1, nowMs))
        assertTrue(M3uCatchUp.isAvailable(info(M3uCatchUpType.DEFAULT, days = 0), nowMs - day / 2, nowMs), "days below 1 count as 1")
    }
}
