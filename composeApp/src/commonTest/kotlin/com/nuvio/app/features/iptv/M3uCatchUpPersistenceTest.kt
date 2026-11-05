package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.content.IptvStreamRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3U catch-up carried from the parsed entry to the guide channel, and the gate that offers a replay. */
class M3uCatchUpPersistenceTest {

    private fun parse(text: String): M3UParser.Entry {
        val out = ArrayList<M3UParser.Entry>()
        val p = M3UParser.StreamingParser { out.add(it) }
        text.trimIndent().lineSequence().forEach { p.onLine(it) }
        return out.single()
    }

    private fun account(sourceType: String) = XtreamAccount(
        id = "a", name = "A", baseUrl = "http://h", username = "u", password = "p", sourceType = sourceType,
    )

    private val info = M3uCatchUpInfo(M3uCatchUpType.APPEND, "?utc={utc}", 3)

    @Test
    fun `ingest keeps a live entry's catch-up on its row`() {
        val entry = parse(
            """
            #EXTINF:-1 tvg-id="a" catchup="append" catchup-source="?utc={utc}" catchup-days="3",A
            http://h/live/u/p/1.ts
            """,
        )
        val mapped = M3uIngestMapping.map(entry, null, 0)
        assertIs<M3uIngestRow.Channel>(mapped)
        assertEquals(info, mapped.row.catchUp)
    }

    @Test
    fun `a live entry without catch-up attributes maps to a row without it`() {
        val mapped = M3uIngestMapping.map(parse("#EXTINF:-1 tvg-id=\"a\",A\nhttp://h/live/u/p/1.ts"), null, 0)
        assertIs<M3uIngestRow.Channel>(mapped)
        assertNull(mapped.row.catchUp)
    }

    @Test
    fun `a stored catch-up channel reaches the guide with its archive flag and days`() {
        val ch = with(M3UClient) {
            IptvStreamRow(1, "A", null, "a", "g", "http://h/1.ts", null, catchUp = info).toChannel()
        }
        assertTrue(ch.hasArchive)
        assertEquals(3, ch.catchUpDays)
        assertEquals(info, ch.m3uCatchUp)
    }

    @Test
    fun `a channel without catch-up has no archive flag and no days`() {
        val ch = with(M3UClient) { IptvStreamRow(1, "A", null, "a", "g", "http://h/1.ts", null).toChannel() }
        assertFalse(ch.hasArchive)
        assertEquals(0, ch.catchUpDays)
        assertNull(ch.m3uCatchUp)
    }

    @Test
    fun `the gate accepts url and file m3u playlists on a catch-up channel`() {
        assertTrue(CatchUpEpgRepository.supportsCatchUp(account(SOURCE_TYPE_M3U_URL), info))
        assertTrue(CatchUpEpgRepository.supportsCatchUp(account(SOURCE_TYPE_M3U_FILE), info))
    }

    @Test
    fun `the gate refuses an m3u channel without catch-up and a stalker portal`() {
        assertFalse(CatchUpEpgRepository.supportsCatchUp(account(SOURCE_TYPE_M3U_URL), null))
        assertFalse(CatchUpEpgRepository.supportsCatchUp(account(SOURCE_TYPE_M3U_FILE), null))
        assertFalse(CatchUpEpgRepository.supportsCatchUp(account(SOURCE_TYPE_STALKER), info))
    }

    @Test
    fun `the gate still accepts an xtream playlist and the history fetch stays xtream only`() {
        assertTrue(CatchUpEpgRepository.supportsCatchUp(account(SOURCE_TYPE_XTREAM), null))
        assertFalse(CatchUpEpgRepository.supportsCatchUp(account(SOURCE_TYPE_M3U_URL)), "M3U history comes from XMLTV, not the table fetch")
    }
}
