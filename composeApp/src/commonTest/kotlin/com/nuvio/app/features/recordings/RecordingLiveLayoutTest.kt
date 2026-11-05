package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.RecordingLiveLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingLiveLayoutTest {
    @Test
    fun `the directory name is one plain segment whatever the schedule id holds`() {
        val dir = RecordingLiveLayout.dirNameFor("xtream:acc/1:live:42:1794000000000")
        assertTrue(dir.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }, dir)
        assertTrue(!dir.startsWith("."), "never a hidden or relative name: $dir")
    }

    @Test
    fun `the same schedule always gets the same directory so a retry continues into it`() {
        assertEquals(RecordingLiveLayout.dirNameFor("ch1:100"), RecordingLiveLayout.dirNameFor("ch1:100"))
    }

    @Test
    fun `ids that differ only in punctuation do not share a directory`() {
        assertNotEquals(RecordingLiveLayout.dirNameFor("ch1:100"), RecordingLiveLayout.dirNameFor("ch1/100"))
    }

    @Test
    fun `the playlist file lives inside the directory`() {
        val dir = RecordingLiveLayout.dirNameFor("L")
        assertEquals("$dir/index.m3u8", RecordingLiveLayout.fileNameFor("L"))
        assertEquals(dir, RecordingLiveLayout.dirOf(RecordingLiveLayout.fileNameFor("L")))
    }

    @Test
    fun `a catch-up file name has no live directory`() {
        assertNull(RecordingLiveLayout.dirOf("Show-1794000000000.ts"))
    }
}
