package com.nuvio.app.features.iptv.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * XMLTV parsing contract: `YYYYMMDDHHMMSS ±HHMM` -> UTC epoch-ms (with + without offset), the
 * streaming tokenizer's channel/programme extraction, the channel-id allow-set filter, id
 * normalization, and entity decoding. Pure — no IO — so it runs on the JVM host test task with no
 * android.util.Xml / NSXMLParser mock (the whole reason the parser is hand-rolled in commonMain).
 */
class XmltvParserTest {

    // --- time -> UTC ---------------------------------------------------------

    @Test
    fun timeWithoutOffsetIsAssumedUtc() {
        // 2026-07-02 18:30:00 UTC. Cross-checked against a known epoch.
        val ms = parseXmltvTime("20260702183000")
        assertEquals(1_783_017_000_000L, ms)
    }

    @Test
    fun timeWithPositiveOffsetConvertsToUtc() {
        // 20:30 at +0200 is the SAME instant as 18:30 UTC.
        val utc = parseXmltvTime("20260702183000")
        val plus2 = parseXmltvTime("20260702203000 +0200")
        assertEquals(utc, plus2)
    }

    @Test
    fun timeWithNegativeOffsetConvertsToUtc() {
        // 13:30 at -0500 is the SAME instant as 18:30 UTC.
        val utc = parseXmltvTime("20260702183000")
        val minus5 = parseXmltvTime("20260702133000 -0500")
        assertEquals(utc, minus5)
    }

    @Test
    fun timeAcceptsColonOffsetAndZuluAndMissingSeconds() {
        val utc = parseXmltvTime("20260702183000")
        assertEquals(utc, parseXmltvTime("20260702203000 +02:00")) // colon in offset
        assertEquals(utc, parseXmltvTime("20260702183000 Z"))      // explicit UTC
        assertEquals(utc, parseXmltvTime("202607021830"))          // seconds omitted (YYYYMMDDHHMM)
    }

    @Test
    fun timeRejectsGarbageAndTooShort() {
        assertNull(parseXmltvTime(null))
        assertNull(parseXmltvTime(""))
        assertNull(parseXmltvTime("2026070218"))       // < 12 digits
        assertNull(parseXmltvTime("notatimestamp"))
        assertNull(parseXmltvTime("20261302183000"))   // month 13
    }

    // --- channel id normalization -------------------------------------------

    @Test
    fun normalizeChannelIdFoldsCaseAndWhitespace() {
        assertEquals("cnn.us", normalizeChannelId("CNN.us"))
        assertEquals("cnn.us", normalizeChannelId("  cnn.us  "))
        assertEquals("skysportshd", normalizeChannelId("Sky Sports HD"))
        assertEquals(normalizeChannelId("BBC One"), normalizeChannelId("bbcone"))
    }

    // --- streaming parse -----------------------------------------------------

    private val guide = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="cnn.us"><display-name>CNN</display-name></channel>
          <channel id="sky.sports.1"><display-name>Sky Sports 1</display-name></channel>
          <programme start="20260702180000 +0000" stop="20260702183000 +0000" channel="cnn.us">
            <title>News Now</title>
            <desc>Headlines &amp; analysis</desc>
          </programme>
          <programme start="20260702183000 +0000" stop="20260702190000 +0000" channel="cnn.us">
            <title>World Report</title>
          </programme>
          <programme start="20260702180000 +0000" stop="20260702200000 +0000" channel="espn.us">
            <title>Should Be Filtered Out</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun parse(text: String, keep: Set<String>?): Pair<List<Pair<String, String?>>, List<XmltvProgramme>> {
        val channels = ArrayList<Pair<String, String?>>()
        val programmes = ArrayList<XmltvProgramme>()
        val parser = XmltvStreamingParser(
            keepChannelIds = keep,
            onChannel = { id, name -> channels.add(id to name) },
            onProgramme = { programmes.add(it) },
        )
        text.lineSequence().forEach { parser.feed(it); parser.feed("\n") }
        parser.finish()
        return channels to programmes
    }

    @Test
    fun parsesChannelsAndProgrammes() {
        val (channels, programmes) = parse(guide, keep = null)
        assertEquals(listOf("cnn.us" to "CNN", "sky.sports.1" to "Sky Sports 1"), channels)
        // All three programmes kept when no filter (cnn x2 + espn).
        assertEquals(3, programmes.size)
        val first = programmes.first { it.title == "News Now" }
        assertEquals("cnn.us", first.channelId)
        assertEquals("Headlines & analysis", first.desc) // &amp; decoded
        assertEquals(parseXmltvTime("20260702180000 +0000"), first.startMs)
        assertEquals(parseXmltvTime("20260702183000 +0000"), first.endMs)
    }

    @Test
    fun filtersProgrammesToTheChannelAllowSet() {
        // Only keep cnn.us (normalized). The espn.us programme must be dropped at parse time.
        val (_, programmes) = parse(guide, keep = hashSetOf("cnn.us"))
        assertEquals(2, programmes.size)
        assertTrue(programmes.all { it.channelId == "cnn.us" })
        assertTrue(programmes.none { it.title.contains("Filtered") })
    }

    @Test
    fun channelIdFilterIsCaseInsensitive() {
        // The guide's channel is "cnn.us"; the allow-set here is upper-case but pre-normalized by the
        // parser, so it still matches (the parser normalizes the programme's channel before compare).
        val (_, programmes) = parse(guide, keep = hashSetOf(normalizeChannelId("CNN.US")))
        assertEquals(2, programmes.size)
    }

    @Test
    fun chunkBoundariesMidTagAreHandled() {
        // Feed the document in tiny slices so token boundaries fall mid-tag / mid-text.
        val programmes = ArrayList<XmltvProgramme>()
        val parser = XmltvStreamingParser(keepChannelIds = null, onProgramme = { programmes.add(it) })
        var i = 0
        while (i < guide.length) {
            parser.feed(guide.substring(i, minOf(i + 3, guide.length)))
            i += 3
        }
        parser.finish()
        assertEquals(3, programmes.size)
        assertTrue(programmes.any { it.title == "News Now" && it.desc == "Headlines & analysis" })
    }

    @Test
    fun missingStopGetsAnHourFallback() {
        val text = """
            <tv>
              <programme start="20260702180000 +0000" channel="cnn.us"><title>No Stop</title></programme>
            </tv>
        """.trimIndent()
        val (_, programmes) = parse(text, keep = null)
        assertEquals(1, programmes.size)
        val p = programmes.single()
        assertEquals(p.startMs + 60L * 60 * 1000, p.endMs)
    }

    @Test
    fun selfClosingAndAttributeQuotingVariants() {
        val text = """
            <tv>
              <channel id='bbc.one'/>
              <programme channel='bbc.one' start="202607021900" stop="202607022000"><title>Quoted</title></programme>
            </tv>
        """.trimIndent()
        val (channels, programmes) = parse(text, keep = null)
        assertEquals(listOf("bbc.one" to null), channels) // self-closing channel, no display-name
        assertEquals(1, programmes.size)
        assertEquals("Quoted", programmes.single().title)
        assertEquals("bbc.one", programmes.single().channelId)
    }

    @Test
    fun decodesNumericAndNamedEntities() {
        assertEquals("A & B < C > D \" E ' F", decodeEntities("A &amp; B &lt; C &gt; D &quot; E &apos; F"))
        assertEquals("café", decodeEntities("caf&#233;"))       // decimal
        assertEquals("café", decodeEntities("caf&#xe9;"))       // hex
        assertEquals("no entities here", decodeEntities("no entities here"))
    }
}
