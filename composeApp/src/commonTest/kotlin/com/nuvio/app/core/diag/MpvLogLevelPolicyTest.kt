package com.nuvio.app.core.diag

import kotlin.test.Test
import kotlin.test.assertEquals

/** B116: raw mpv/FFmpeg modules that format provider URLs must print nothing (twin: TV JUnit). */
class MpvLogLevelPolicyTest {
    @Test
    fun urlBearingModulesAreSilenced() {
        listOf("cplayer", "stream", "ffmpeg", "ffmpeg/demuxer", "lavf", "demux", "file", "osd/libass").forEach { module ->
            assertEquals("no", MpvLogLevelPolicy.levelFor(module), "module $module")
        }
    }

    @Test
    fun decoderAndOutputModulesStayVisible() {
        assertEquals("warn", MpvLogLevelPolicy.levelFor("vo/gpu"), "video output")
        assertEquals("warn", MpvLogLevelPolicy.levelFor("vd"), "video decoder")
        assertEquals("warn", MpvLogLevelPolicy.levelFor("ao/audiotrack"), "audio output")
        assertEquals("warn", MpvLogLevelPolicy.levelFor("ffmpeg/video"), "ffmpeg video decoder")
    }

    @Test
    fun lastMatchingEntryWinsLikeMpvMsgC() {
        assertEquals("warn", MpvLogLevelPolicy.levelFor("ffmpeg/video", "all=no,ffmpeg=no,ffmpeg/video=warn"), "override order")
        assertEquals("no", MpvLogLevelPolicy.levelFor("vdx", "all=no,vd=warn"), "prefix needs a slash boundary")
        assertEquals("warn", MpvLogLevelPolicy.levelFor("stream", "all=warn"), "the old Android setting reproduces the leak")
    }

    @Test
    fun ffmpegHttpStatusLineReachesTheErrorLogButSiblingsStaySilent() {
        // Device pass: libmpv only ends a refused live open with "loading failed"; the status ("HTTP error 458")
        // is FFmpeg's http module warning, which all=no swallowed, so the screen could not name it.
        assertEquals("warn", MpvLogLevelPolicy.levelFor("ffmpeg/http"), "the HTTP status line")
        assertEquals("no", MpvLogLevelPolicy.levelFor("ffmpeg/hls"), "hls warns with segment URLs")
        assertEquals("no", MpvLogLevelPolicy.levelFor("ffmpeg/tcp"), "tcp lines carry hosts")
        assertEquals("no", MpvLogLevelPolicy.levelFor("stream"), "stream formats the URL")
    }
}
