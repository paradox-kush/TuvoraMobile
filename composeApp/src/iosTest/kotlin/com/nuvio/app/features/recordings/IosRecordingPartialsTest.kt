package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.IosPartialStaging
import com.nuvio.app.features.recordings.internal.IosRecordingFiles
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
class IosRecordingPartialsTest {
    private val fm = NSFileManager.defaultManager
    private lateinit var root: String
    private lateinit var recs: String
    private lateinit var downloads: String
    private val link = "http://panel.example/a.ts"

    @BeforeTest
    fun setUp() {
        root = NSTemporaryDirectory().trimEnd('/') + "/rec-ios-" + NSUUID().UUIDString
        recs = "$root/recs"; downloads = "$root/downloads"
        fm.createDirectoryAtPath(recs, true, null, null)
        fm.createDirectoryAtPath(downloads, true, null, null)
    }

    @AfterTest
    fun tearDown() { fm.removeItemAtPath(root, null) }

    private fun put(path: String, text: String) { NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null) }
    private fun exists(path: String) = fm.fileExistsAtPath(path)

    @Test
    fun a_partial_pinned_to_the_same_link_is_handed_to_the_downloader() {
        val part = "$recs/show.ts.part"; val work = "$downloads/rec-show.ts.part"
        put(part, "0123456789")
        val staging = IosPartialStaging()
        staging.writePin(part, link)
        assertTrue(staging.stageForResume(link, part, work, resumeFromBytes = 10))
        assertTrue(exists(work)); assertFalse(exists(part))
    }

    @Test
    fun a_partial_from_another_link_is_deleted_and_the_next_link_starts_at_zero() {
        val part = "$recs/show.ts.part"; val work = "$downloads/rec-show.ts.part"
        put(part, "0123456789")
        val staging = IosPartialStaging()
        staging.writePin(part, "http://other.example/b.ts")
        assertFalse(staging.stageForResume(link, part, work, resumeFromBytes = 10))
        assertFalse(exists(work), "no bytes of the other link reach the downloader")
        assertFalse(exists(part)); assertFalse(exists("$part.meta"))
    }

    @Test
    fun a_stale_downloader_leftover_is_cleared_when_starting_fresh() {
        val part = "$recs/show.ts.part"; val work = "$downloads/rec-show.ts.part"
        put(work, "stale")
        assertFalse(IosPartialStaging().stageForResume(link, part, work, resumeFromBytes = 0))
        assertFalse(exists(work))
    }

    @Test
    fun a_stopped_save_parks_its_partial_and_link_back_for_the_repository() {
        val part = "$recs/show.ts.part"; val work = "$downloads/rec-show.ts.part"
        put(work, "0123")
        val staging = IosPartialStaging()
        assertEquals(4L, staging.parkAfterStop(link, part, work))
        assertTrue(exists(part)); assertFalse(exists(work))
        assertEquals(link, staging.readPin(part))
    }

    @Test
    fun parking_twice_is_harmless() {
        val part = "$recs/show.ts.part"; val work = "$downloads/rec-show.ts.part"
        put(work, "0123")
        val staging = IosPartialStaging()
        staging.parkAfterStop(link, part, work)
        assertEquals(0L, staging.parkAfterStop(link, part, work))
        assertTrue(exists(part), "the second call must not delete what the first parked")
    }

    @Test
    fun an_empty_downloader_file_is_dropped_not_parked() {
        val part = "$recs/show.ts.part"; val work = "$downloads/rec-show.ts.part"
        put(work, "")
        IosPartialStaging().parkAfterStop(link, part, work)
        assertFalse(exists(part)); assertFalse(exists(work))
    }

    @Test
    fun deleting_a_part_also_removes_its_sidecar_and_the_downloaders_copy() = runTest {
        val files = IosRecordingFiles(stateDir = "$root/state", recordingsDir = recs, downloadsDir = downloads)
        val part = "$recs/show.ts.part"
        put(part, "x"); put("$part.meta", link); put("$downloads/rec-show.ts.part", "y"); put("$downloads/rec-show.ts", "z")
        files.delete(part)
        assertFalse(exists(part)); assertFalse(exists("$part.meta"))
        assertFalse(exists("$downloads/rec-show.ts.part"), "the copy the downloader keeps must not be orphaned")
        assertFalse(exists("$downloads/rec-show.ts"))
    }
}
