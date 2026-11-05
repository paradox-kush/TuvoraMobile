package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.AndroidRecordingFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidRecordingFilesTest {
    private val root = Files.createTempDirectory("rec-files").toFile()
    private val files = AndroidRecordingFiles(File(root, "state"), File(root, "rec"))

    @AfterTest
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun `a write replaces the old text whole and leaves no temporary file`() = runBlocking {
        assertNull(files.read("a.json"))
        files.write("a.json", "one")
        files.write("a.json", "two")
        assertEquals("two", files.read("a.json"))
        assertTrue(File(root, "state").list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `keepAside moves the file and never deletes it`() = runBlocking {
        files.write("a.json", "broken")
        files.keepAside("a.json")
        assertNull(files.read("a.json"))
        val kept = File(root, "state").listFiles()!!.single()
        assertTrue(kept.name.startsWith("a.json.corrupt-"))
        assertEquals("broken", kept.readText())
    }

    @Test
    fun `only plain file names are accepted for recordings`() {
        assertTrue(files.pathFor("show.ts").endsWith("/rec/show.ts"))
        val failed = runCatching { files.pathFor("../x") }.isFailure
        assertTrue(failed)
    }

    @Test
    fun `a live recording's playlist is one plain directory deep and nothing can climb out of the recordings folder`() {
        assertTrue(files.pathFor("live-ab12/index.m3u8").endsWith("/rec/live-ab12/index.m3u8"))
        for (bad in listOf("../x", "a/../b", "/abs/x", "a//b", "./x", "a/./b", "a/", "", "a/b/c/d")) {
            assertTrue(runCatching { files.pathFor(bad) }.isFailure, "rejected: '$bad'")
        }
    }

    @Test
    fun `treeSizeOf sums the files under a directory and deleteTree removes it whole`() = runBlocking {
        val dir = File(root, "rec/live-x")
        File(dir, "p0").mkdirs()
        File(dir, "p0/seg0.ts").writeBytes(ByteArray(1_000))
        File(dir, "p0/seg1.ts").writeBytes(ByteArray(500))
        File(dir, "index.m3u8").writeText("x".repeat(10))
        val path = files.pathFor("live-x")
        assertEquals(1_510L, files.treeSizeOf(path))
        files.deleteTree(path)
        assertTrue(!dir.exists())
        assertEquals(0L, files.treeSizeOf(path))
        files.deleteTree(path)   // already gone: no error
    }

    @Test
    fun `a plain delete still removes only the one file`() = runBlocking {
        File(root, "rec").mkdirs()
        val f = File(root, "rec/a.ts").also { it.writeText("x") }
        files.delete(files.pathFor("a.ts"))
        assertTrue(!f.exists())
    }
}
