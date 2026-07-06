package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M3U ingestion contract: entry classification (path segment then extension), #EXTINF attribute
 * extraction, season/episode + series-key derivation, and the stable sid hashing that lets an M3U
 * catalog reuse the same `xtream:{acc}:{kind}:{id}` registry scheme Xtream uses. Pure — no IO — so
 * it runs on the JVM host test task alongside the existing 323 tests.
 */
class M3UParserTest {

    // A small but representative provider-style playlist covering all three kinds + edge cases.
    private val fixture = """
        #EXTM3U
        #EXTINF:-1 tvg-id="cnn.us" tvg-name="CNN HD" tvg-logo="http://logo/cnn.png" group-title="News",CNN HD
        http://host:8080/live/user/pass/101.ts
        #EXTINF:-1 tvg-id="" tvg-name="Sky Sports" tvg-logo="http://logo/sky.png" group-title="Sports",Sky Sports 1
        http://host:8080/live/user/pass/102.m3u8
        #EXTINF:-1 tvg-logo="http://logo/dune.jpg" group-title="Movies EN",Dune Part Two (2024)
        http://host:8080/movie/user/pass/5001.mp4
        #EXTINF:-1 group-title="Movies EN",Some Film Without Path
        http://cdn.example.com/vod/somefilm.mkv
        #EXTINF:-1 tvg-name="Breaking Bad S01E01" group-title="Series EN",Breaking Bad S01 E01
        http://host:8080/series/user/pass/9001.mp4
        #EXTINF:-1 tvg-name="Breaking Bad S01E02" group-title="Series EN",Breaking Bad S01E02
        http://host:8080/series/user/pass/9002.mp4
    """.trimIndent()

    private fun parseAll(text: String): List<M3UParser.Entry> {
        val out = ArrayList<M3UParser.Entry>()
        val parser = M3UParser.StreamingParser { out.add(it) }
        text.lineSequence().forEach { parser.onLine(it) }
        return out
    }

    @Test
    fun streamingParserPairsExtinfWithNextUrlAndClassifies() {
        val entries = parseAll(fixture)
        assertEquals(6, entries.size)

        assertEquals(M3UKind.LIVE, entries[0].kind)       // /live/ + .ts
        assertEquals(M3UKind.LIVE, entries[1].kind)       // /live/ + .m3u8
        assertEquals(M3UKind.MOVIE, entries[2].kind)      // /movie/ path
        assertEquals(M3UKind.MOVIE, entries[3].kind)      // no path hint, .mkv ext
        assertEquals(M3UKind.SERIES, entries[4].kind)     // /series/ path
        assertEquals(M3UKind.SERIES, entries[5].kind)
    }

    @Test
    fun extractsExtinfAttributesAndDisplayName() {
        val cnn = parseAll(fixture).first()
        assertEquals("CNN HD", cnn.name)                  // text after the comma
        assertEquals("cnn.us", cnn.tvgId)
        assertEquals("http://logo/cnn.png", cnn.logo)
        assertEquals("News", cnn.group)
        assertEquals("ts", cnn.ext)
    }

    @Test
    fun blankAttributeValuesBecomeNull() {
        val sky = parseAll(fixture)[1]
        assertNull(sky.tvgId)                             // tvg-id="" -> null
        assertEquals("Sports", sky.group)
    }

    @Test
    fun parseAttributesHandlesQuotedAndSpacing() {
        val attrs = M3UParser.parseAttributes(
            """#EXTINF:-1 tvg-id="id1" tvg-name="Name With Spaces" tvg-logo="http://a/b.png?x=1&y=2" group-title="Grp",Display"""
        )
        assertEquals("id1", attrs["tvg-id"])
        assertEquals("Name With Spaces", attrs["tvg-name"])
        assertEquals("http://a/b.png?x=1&y=2", attrs["tvg-logo"])
        assertEquals("Grp", attrs["group-title"])
    }

    @Test
    fun classifyPrefersPathSegmentOverExtension() {
        // A /movie/ path with a .ts extension is still a movie (path wins).
        assertEquals(M3UKind.MOVIE, M3UParser.classify("http://h/movie/u/p/1.ts", "ts", null))
        // A /live/ path with a .mp4 extension is still live.
        assertEquals(M3UKind.LIVE, M3UParser.classify("http://h/live/u/p/1.mp4", "mp4", null))
        // No path hint -> extension decides.
        assertEquals(M3UKind.MOVIE, M3UParser.classify("http://h/x/1.avi", "avi", null))
        assertEquals(M3UKind.LIVE, M3UParser.classify("http://h/x/1.ts", "ts", null))
        // No extension, no path -> reads as a channel.
        assertEquals(M3UKind.LIVE, M3UParser.classify("http://h/token12345", null, null))
    }

    @Test
    fun seasonEpisodeParsedFromVariousFormats() {
        assertEquals(1 to 1, M3UParser.seasonEpisodeOf("Breaking Bad S01 E01"))
        assertEquals(1 to 2, M3UParser.seasonEpisodeOf("Breaking Bad S01E02"))
        assertEquals(2 to 5, M3UParser.seasonEpisodeOf("Show Name s02e05 1080p"))
        assertEquals(3 to 10, M3UParser.seasonEpisodeOf("Something 3x10"))
        assertNull(M3UParser.seasonEpisodeOf("A Movie Title 2024"))
    }

    @Test
    fun episodesOfOneShowShareASeriesKey() {
        val entries = parseAll(fixture).filter { it.kind == M3UKind.SERIES }
        assertEquals(2, entries.size)
        assertNotNull(entries[0].seriesKey)
        // Both Breaking Bad episodes collapse to the same key so they group into one series row.
        assertEquals(entries[0].seriesKey, entries[1].seriesKey)
        assertTrue(entries[0].seriesKey!!.contains("breaking bad"))
        assertEquals(1, entries[0].season)
        assertEquals(1, entries[0].episode)
        assertEquals(2, entries[1].episode)
    }

    @Test
    fun seriesKeyStripsQualityAndYearTags() {
        val key = M3UParser.seriesKeyOf("The Wire S02E03 [FHD] (2004)", "The Wire", "Series")
        assertEquals("the wire", key)
    }

    @Test
    fun bareUrlWithoutExtinfIsSkipped() {
        val entries = parseAll(
            """
            http://host/orphan/1.ts
            #EXTINF:-1 group-title="X",Real One
            http://host/live/u/p/1.ts
            """.trimIndent()
        )
        assertEquals(1, entries.size)
        assertEquals("Real One", entries.single().name)
    }

    @Test
    fun sidHashIsStableNonNegativeAndDistinct() {
        val a = M3UClient.sidOf("http://host/live/u/p/101.ts")
        val b = M3UClient.sidOf("http://host/live/u/p/101.ts")
        val c = M3UClient.sidOf("http://host/live/u/p/102.ts")
        assertEquals(a, b)                                // stable
        assertTrue(a >= 0)                                // non-negative (registry ids are positive)
        assertTrue(a != c)                                // different urls -> different sids
    }

    @Test
    fun categoryIdStableAndUngroupedFixed() {
        assertEquals(M3UClient.categoryId("News"), M3UClient.categoryId("News"))
        assertEquals("0", M3UClient.categoryId(null))
        assertEquals("0", M3UClient.categoryId(""))
    }

    @Test
    fun extm3uHeaderTvgUrlIsCaptured() {
        // The #EXTM3U header's url-tvg is the EPG source when no explicit epgUrl — captured as it streams.
        val parser = M3UParser.StreamingParser { }
        parser.onLine("""#EXTM3U url-tvg="http://epg.example/guide.xml.gz" x-tvg-url="http://other/ignored.xml"""")
        parser.onLine("""#EXTINF:-1 group-title="X",Ch""")
        parser.onLine("http://host/live/u/p/1.ts")
        assertEquals("http://epg.example/guide.xml.gz", parser.epgUrl)
    }

    @Test
    fun extm3uHeaderXTvgUrlSpellingAndCommaList() {
        val p1 = M3UParser.StreamingParser { }
        p1.onLine("""#EXTM3U x-tvg-url="http://epg.example/x.xml"""")
        assertEquals("http://epg.example/x.xml", p1.epgUrl)

        // A comma-separated list -> the first url wins.
        val p2 = M3UParser.StreamingParser { }
        p2.onLine("""#EXTM3U url-tvg="http://a/1.xml,http://b/2.xml"""")
        assertEquals("http://a/1.xml", p2.epgUrl)

        // No tvg url on the header -> null.
        val p3 = M3UParser.StreamingParser { }
        p3.onLine("#EXTM3U")
        assertNull(p3.epgUrl)
    }

    @Test
    fun m3uAccountFromFormBuildsM3uIdentity() {
        val account = m3uAccountFromForm(
            XtreamFormInput(
                serverUrl = "", username = "", password = "", name = "  My M3U  ",
                epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
                sourceType = SOURCE_TYPE_M3U_URL,
                m3uUrl = "http://host:8080/get.php?username=u&password=p&type=m3u_plus",
                userAgent = "  VLC/3.0  ",
            ),
        )
        assertNotNull(account)
        assertEquals(SOURCE_TYPE_M3U_URL, account.sourceType)
        assertEquals("http://host:8080/get.php?username=u&password=p&type=m3u_plus", account.baseUrl)
        assertEquals("m3u|http://host:8080/get.php?username=u&password=p&type=m3u_plus", account.id)
        assertEquals("My M3U", account.name)              // trimmed
        assertEquals("VLC/3.0", account.userAgent)        // trimmed
        assertEquals("", account.username)
        assertEquals("", account.password)
    }

    @Test
    fun m3uAccountFromFormDefaultsSchemeAndNameToHost() {
        val account = m3uAccountFromForm(
            XtreamFormInput(
                serverUrl = "", username = "", password = "", name = null,
                epgUrl = "  ", dnsProvider = "system", autoRefreshHours = 24,
                sourceType = SOURCE_TYPE_M3U_URL,
                m3uUrl = "myhost.example.com/playlist.m3u",
                userAgent = "   ",
            ),
        )
        assertNotNull(account)
        assertEquals("http://myhost.example.com/playlist.m3u", account.baseUrl)   // http:// prepended
        assertEquals("myhost.example.com", account.name)                          // falls back to host
        assertNull(account.userAgent)                                             // blank -> null
        assertNull(account.epgUrl)

        // Blank URL -> no account (form Save is gated on this too).
        assertNull(
            m3uAccountFromForm(
                XtreamFormInput(serverUrl = "", username = "", password = "", name = null, epgUrl = null, dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = "   ", userAgent = null),
            ),
        )
    }

    @Test
    fun m3uAccountRoundTripsThroughJsonWithDefaults() {
        // The new userAgent field is additive — old JSON (no userAgent) still decodes, and an M3U
        // account serializes + decodes unchanged (same storage, no migration).
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val account = m3uAccountFromForm(
            XtreamFormInput(
                serverUrl = "", username = "", password = "", name = "M3U",
                epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
                sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = "http://h/p.m3u", userAgent = "UA",
            ),
        )!!
        val decoded = json.decodeFromString<List<XtreamAccount>>(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(XtreamAccount.serializer()), listOf(account))).single()
        assertEquals(account, decoded)

        // Pre-userAgent persisted JSON decodes with userAgent = null.
        val legacy = """[{"id":"m3u|http://h/p.m3u","name":"M3U","baseUrl":"http://h/p.m3u","username":"","password":"","sourceType":"m3u_url"}]"""
        val legacyAcc = json.decodeFromString<List<XtreamAccount>>(legacy).single()
        assertEquals(SOURCE_TYPE_M3U_URL, legacyAcc.sourceType)
        assertNull(legacyAcc.userAgent)
    }
}
