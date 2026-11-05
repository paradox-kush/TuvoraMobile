package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Catch-up attributes on `#EXTINF` and `#EXTM3U`. Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0). */
class M3uCatchUpParseTest {

    private fun parse(text: String): List<M3UParser.Entry> {
        val out = ArrayList<M3UParser.Entry>()
        val p = M3UParser.StreamingParser { out.add(it) }
        text.trimIndent().lineSequence().forEach { p.onLine(it) }
        return out
    }

    @Test
    fun entryWithoutCatchUpAttributesHasNone() {
        val e = parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="a",A
            http://h/live/u/p/1.ts
        """).single()
        assertNull(e.catchUp)
    }

    @Test
    fun catchupAttributeSetsTypeAndSourceAndDaysDefaultsToOne() {
        val c = parse("""
            #EXTINF:-1 catchup="append" catchup-source="?utc={utc}",A
            http://h/live/u/p/1.ts
        """).single().catchUp
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.APPEND, "?utc={utc}", 1), c)
    }

    @Test
    fun catchupTypeAttributeIsAlsoRead() {
        val c = parse("""
            #EXTINF:-1 catchup-type="flussonic" catchup-days="7",A
            http://h/c/index.m3u8
        """).single().catchUp
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.FLUSSONIC, null, 7), c)
    }

    @Test
    fun daysAloneEnableDefaultStyle() {
        val c = parse("""
            #EXTINF:-1 tvg-rec="3",A
            http://h/c/index.m3u8
        """).single().catchUp
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.DEFAULT, null, 3), c)
    }

    @Test
    fun timeshiftAttributeIsDaysToo() {
        val c = parse("""
            #EXTINF:-1 timeshift="2" catchup="shift",A
            http://h/c/index.m3u8
        """).single().catchUp
        assertEquals(2, c?.days)
        assertEquals(M3uCatchUpType.SHIFT, c?.type)
    }

    @Test
    fun catchupDaysWinsOverTvgRec() {
        val c = parse("""
            #EXTINF:-1 catchup="default" catchup-days="5" tvg-rec="9",A
            http://h/c/index.m3u8
        """).single().catchUp
        assertEquals(5, c?.days)
    }

    @Test
    fun zeroDaysWithoutTypeMeansNoCatchUp() {
        assertNull(parse("""
            #EXTINF:-1 tvg-rec="0",A
            http://h/c/index.m3u8
        """).single().catchUp)
    }

    @Test
    fun sourceAloneEnablesDefaultStyle() {
        val c = parse("""
            #EXTINF:-1 catchup-source="?utc={utc}",A
            http://h/c/index.m3u8
        """).single().catchUp
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.DEFAULT, "?utc={utc}", 1), c)
    }

    @Test
    fun unknownTypeWithSourceIsDefaultAndWithoutSourceIsNone() {
        assertEquals(
            M3uCatchUpType.DEFAULT,
            parse("#EXTINF:-1 catchup=\"weird\" catchup-source=\"?x={utc}\",A\nhttp://h/c/a.m3u8").single().catchUp?.type,
        )
        assertNull(parse("#EXTINF:-1 catchup=\"weird\",A\nhttp://h/c/a.m3u8").single().catchUp)
    }

    @Test
    fun attributeKeysAreCaseInsensitiveOnEntryAndHeader() {
        val es = parse("""
            #EXTM3U Catchup="shift" Catchup-Days="7"
            #EXTINF:-1 CATCHUP-SOURCE="?x={utc}",A
            http://h/c/a.m3u8
        """)
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.SHIFT, "?x={utc}", 7), es.single().catchUp)
    }

    @Test
    fun vodAndSeriesEntriesGetNoCatchUp() {
        val es = parse("""
            #EXTM3U catchup="shift" catchup-days="7"
            #EXTINF:-1,Film
            http://h/movie/u/p/1.mp4
            #EXTINF:-1,Show S01E01
            http://h/series/u/p/2.mkv
        """)
        assertEquals(2, es.size)
        for (e in es) assertNull(e.catchUp)
    }

    @Test
    fun headerDefaultsApplyToEveryEntry() {
        val es = parse("""
            #EXTM3U catchup="shift" catchup-days="4"
            #EXTINF:-1,A
            http://h/c/a.m3u8
            #EXTINF:-1,B
            http://h/c/b.m3u8
        """)
        for (e in es) assertEquals(M3uCatchUpInfo(M3uCatchUpType.SHIFT, null, 4), e.catchUp)
    }

    @Test
    fun entryOverridesHeaderPerAttribute() {
        val es = parse("""
            #EXTM3U url-tvg="http://e/a.xml,http://e/b.xml" catchup="shift" catchup-days="4" catchup-source="?x={utc}"
            #EXTINF:-1 catchup="append",A
            http://h/c/a.m3u8
            #EXTINF:-1 catchup-days="9",B
            http://h/c/b.m3u8
        """)
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.APPEND, "?x={utc}", 4), es[0].catchUp)
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.SHIFT, "?x={utc}", 9), es[1].catchUp)
    }

    @Test
    fun headerTypeDefaultsToDefaultWhenOnlyDaysGiven() {
        val e = parse("""
            #EXTM3U tvg-rec="2"
            #EXTINF:-1,A
            http://h/c/a.m3u8
        """).single()
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.DEFAULT, null, 2), e.catchUp)
    }

    @Test
    fun parseEntryTakesHeaderMapDirectly() {
        val e = M3UParser.parseEntry(
            """#EXTINF:-1,A""", "http://h/c/a.m3u8",
            catchUpHeader = mapOf("catchup" to "xc", "catchup-days" to "6"),
        )
        assertEquals(M3uCatchUpInfo(M3uCatchUpType.XTREAM, null, 6), e?.catchUp)
    }
}
