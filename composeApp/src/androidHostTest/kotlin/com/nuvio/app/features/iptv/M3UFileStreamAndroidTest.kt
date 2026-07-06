package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The local-file byte source ([streamFileLines], the m3u_file ingest input) exercised on the JVM host
 * test — it's a pure java.io actual (no Android Context), so it runs here alongside the common tests.
 * Covers a plain-file round-trip through the SAME [M3UParser] the URL path uses, gzip transparency,
 * and the missing-file error the ingest surfaces as a "re-import" prompt.
 */
class M3UFileStreamAndroidTest {

    private val playlist = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
        #EXTINF:-1 tvg-id="cnn.us" tvg-logo="http://logo/cnn.png" group-title="News",CNN HD
        http://host:8080/live/user/pass/101.ts
        #EXTINF:-1 group-title="Movies",Dune Part Two (2024)
        http://host:8080/movie/user/pass/5001.mp4
        #EXTINF:-1 tvg-name="Breaking Bad S01E01" group-title="Series",Breaking Bad S01 E01
        http://host:8080/series/user/pass/9001.mp4
    """.trimIndent()

    // createTempFile requires a >= 3-char prefix, so pad the caller's short name.
    private fun tempFile(name: String): File =
        File.createTempFile("m3u_$name", ".m3u").also { it.deleteOnExit() }

    private fun parseFile(path: String): Pair<List<M3UParser.Entry>, String?> {
        val entries = ArrayList<M3UParser.Entry>()
        val parser = M3UParser.StreamingParser { entries.add(it) }
        runBlocking { streamFileLines(path) { line -> parser.onLine(line) } }
        return entries to parser.epgUrl
    }

    @Test
    fun plainFileStreamsAndParsesThroughSameParser() {
        val f = tempFile("plain")
        f.writeText(playlist)
        val (entries, epgUrl) = parseFile(f.absolutePath)

        assertEquals(3, entries.size)
        assertEquals(M3UKind.LIVE, entries[0].kind)
        assertEquals("CNN HD", entries[0].name)
        assertEquals("cnn.us", entries[0].tvgId)
        assertEquals(M3UKind.MOVIE, entries[1].kind)
        assertEquals(M3UKind.SERIES, entries[2].kind)
        // The #EXTM3U url-tvg header is captured for EPG-source resolution.
        assertEquals("http://epg.example/guide.xml.gz", epgUrl)
    }

    @Test
    fun gzippedFileIsTransparentlyDecompressed() {
        val f = File.createTempFile("m3u_gz", ".m3u.gz").also { it.deleteOnExit() }
        val gz = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(playlist.toByteArray()) }
        }.toByteArray()
        f.writeBytes(gz)
        // Gzip magic sniffed -> same three entries as the plain file.
        val (entries, epgUrl) = parseFile(f.absolutePath)
        assertEquals(3, entries.size)
        assertEquals("http://epg.example/guide.xml.gz", epgUrl)
    }

    @Test
    fun missingFileThrows() {
        val missing = File(System.getProperty("java.io.tmpdir"), "definitely-not-here-${System.nanoTime()}.m3u")
        assertTrue(!missing.exists())
        // The ingest catches this and surfaces a "re-import on this device" message.
        assertFailsWith<IllegalStateException> {
            runBlocking { streamFileLines(missing.absolutePath) { } }
        }
    }

    @Test
    fun fileExistsReflectsDisk() {
        // fileExists backs the "synced file playlist with no local copy" detection.
        val f = tempFile("exists")
        f.writeText("#EXTM3U")
        assertTrue(fileExists(f.absolutePath))
        f.delete()
        assertTrue(!fileExists(f.absolutePath))
    }
}
