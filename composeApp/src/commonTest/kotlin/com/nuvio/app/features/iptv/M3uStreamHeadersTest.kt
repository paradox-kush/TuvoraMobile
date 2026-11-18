package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Per-channel playlist headers: parsed from every common form, allow-listed, persisted and merged in order. */
class M3uStreamHeadersTest {

    private fun parse(text: String): List<M3UParser.Entry> {
        val out = ArrayList<M3UParser.Entry>()
        val p = M3UParser.StreamingParser { out.add(it) }
        text.trimIndent().lineSequence().forEach { p.onLine(it) }
        return out
    }

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"

    @Test
    fun `extinf attributes set the user agent and referer`() {
        val e = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" http-user-agent="$ua" http-referrer="https://ref.example/",Channel A
            http://h/live/1.ts
            """,
        ).single()
        assertEquals(mapOf("User-Agent" to ua, "Referer" to "https://ref.example/"), e.headers)
        assertEquals("Channel A", e.name, "a comma inside the quoted UA does not move the display name")
    }

    @Test
    fun `a comma inside a quoted user agent does not lose the attributes after it`() {
        val e = parse(
            "#EXTINF:-1 tvg-id=\"bp.comma\" user-agent=\"Comma, Agent/2.0\" group-title=\"Branch Pass\",BP Comma, Name\nhttp://h/live/1.ts",
        ).single()
        assertEquals("Branch Pass", e.group)
        assertEquals("BP Comma, Name", e.name)
        assertEquals(mapOf("User-Agent" to "Comma, Agent/2.0"), e.headers)
        assertEquals("bp.comma", e.tvgId)
    }

    @Test
    fun `the alternative attribute spellings are read`() {
        val e = parse("#EXTINF:-1 user-agent=\"VLC/3.0.20\" referer=\"https://r/\",A\nhttp://h/1.ts").single()
        assertEquals(mapOf("User-Agent" to "VLC/3.0.20", "Referer" to "https://r/"), e.headers)
        val e2 = parse("#EXTINF:-1 http-referer=\"https://r2/\",B\nhttp://h/2.ts").single()
        assertEquals(mapOf("Referer" to "https://r2/"), e2.headers)
    }

    @Test
    fun `extvlcopt lines after the extinf apply to that entry only`() {
        val entries = parse(
            """
            #EXTINF:-1,A
            #EXTVLCOPT:http-user-agent=VLC/3.0.20 LibVLC/3.0.20
            #EXTVLCOPT:http-referrer=https://ref/
            #EXTVLCOPT:network-caching=1000
            http://h/1.ts
            #EXTINF:-1,B
            http://h/2.ts
            """,
        )
        assertEquals(mapOf("User-Agent" to "VLC/3.0.20 LibVLC/3.0.20", "Referer" to "https://ref/"), entries[0].headers)
        assertEquals(emptyMap(), entries[1].headers, "options never leak to the next entry")
    }

    @Test
    fun `extvlcopt lines before the extinf apply to the next entry`() {
        val entries = parse(
            """
            #EXTINF:-1,A
            http://h/1.ts
            #EXTVLCOPT:http-user-agent=Kodi
            #EXTINF:-1,B
            http://h/2.ts
            """,
        )
        assertEquals(emptyMap(), entries[0].headers)
        assertEquals(mapOf("User-Agent" to "Kodi"), entries[1].headers)
    }

    @Test
    fun `exthttp json is allow-listed and never carries a cookie`() {
        val e = parse(
            """
            #EXTINF:-1,A
            #EXTHTTP:{"User-Agent":"TiviMate/5","Referer":"https://r/","Origin":"https://o","Cookie":"session=secret","X-Forwarded-For":"1.2.3.4"}
            http://h/1.ts
            """,
        ).single()
        assertEquals(mapOf("User-Agent" to "TiviMate/5", "Referer" to "https://r/", "Origin" to "https://o"), e.headers)
    }

    @Test
    fun `bad exthttp json is ignored and the entry still parses`() {
        val e = parse("#EXTINF:-1,A\n#EXTHTTP:{\"User-Agent\": \n#EXTHTTP:not json\nhttp://h/1.ts").single()
        assertEquals(emptyMap(), e.headers)
        assertEquals("http://h/1.ts", e.url)
    }

    @Test
    fun `an explicit line wins over the extinf attribute`() {
        val e = parse("#EXTINF:-1 http-user-agent=\"Attr\",A\n#EXTVLCOPT:http-user-agent=Line\nhttp://h/1.ts").single()
        assertEquals(mapOf("User-Agent" to "Line"), e.headers)
    }

    @Test
    fun `a value with a line break or over the length cap is dropped`() {
        assertEquals(emptyMap(), M3uStreamHeaders.fromExtHttp("#EXTHTTP:{\"User-Agent\":\"a\\r\\nX-Evil: 1\"}"))
        assertEquals(emptyMap(), M3uStreamHeaders.fromVlcOpt("#EXTVLCOPT:http-user-agent=" + "x".repeat(600)))
    }

    @Test
    fun `channel header beats the playlist user agent which beats the default`() {
        val dns = mapOf("Host" to "provider.example")
        assertEquals(dns, M3uStreamHeaders.merge(dns, null, emptyMap()), "nothing set: the engine default UA")
        assertEquals(dns + ("User-Agent" to "Playlist"), M3uStreamHeaders.merge(dns, "Playlist", emptyMap()))
        assertEquals(
            dns + ("User-Agent" to "Channel") + ("Referer" to "https://r/"),
            M3uStreamHeaders.merge(dns, "Playlist", mapOf("User-Agent" to "Channel", "Referer" to "https://r/")),
        )
        val replaced = M3uStreamHeaders.merge(mapOf("user-agent" to "old"), null, mapOf("User-Agent" to "Channel"))
        assertEquals(mapOf("User-Agent" to "Channel"), replaced, "names compare case-insensitively")
    }

    @Test
    fun `stored headers round-trip and are re-checked on the way out`() {
        val headers = mapOf("User-Agent" to "VLC, like \"quotes\"", "Referer" to "https://r/")
        assertEquals(headers, M3uStreamHeaders.decode(M3uStreamHeaders.encode(headers)))
        assertEquals(null, M3uStreamHeaders.encode(emptyMap()))
        assertEquals(emptyMap(), M3uStreamHeaders.decode("{\"Cookie\":\"x\"}"))
        assertEquals(emptyMap(), M3uStreamHeaders.decode("garbage"))
    }

    @Test
    fun `ingest puts a live entry's headers on its row and the channel the player reads`() {
        val e = parse("#EXTINF:-1 http-user-agent=\"VLC\",A\nhttp://h/live/u/p/1.ts").single()
        val mapped = M3uIngestMapping.map(e, null, 0)
        assertIs<M3uIngestRow.Channel>(mapped)
        assertEquals(mapOf("User-Agent" to "VLC"), mapped.row.streamHeaders)
        val channel = with(M3UClient) { mapped.row.toChannel() }
        assertEquals(mapOf("User-Agent" to "VLC"), channel.streamHeaders)
        val item = XtreamItemRegistry.resolvedChannel("acc", channel)
        assertTrue(item.requestHeaders == mapOf("User-Agent" to "VLC"), "the registry item carries them to playback")
    }
}
