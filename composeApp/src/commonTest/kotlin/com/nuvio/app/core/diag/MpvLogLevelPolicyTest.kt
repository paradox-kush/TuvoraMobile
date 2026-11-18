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
    fun theHttpStatusLineIsOnlyAskedForWhileALiveOpenIsPending() {
        // Device pass (Android emulator, verbose log): FFmpeg's status arrives as module "ffmpeg" (NOT "ffmpeg/http"),
        // text "http: HTTP error 458", level warn. mpv cannot narrow below the module, so the whole ffmpeg module is
        // let through for the open only; the steady state stays silent (hls/tcp warnings carry segment URLs and hosts).
        assertEquals("warn", MpvLogLevelPolicy.levelFor("ffmpeg", MpvLogLevelPolicy.specFor(liveOpen = true)), "status line during a live open")
        assertEquals("no", MpvLogLevelPolicy.levelFor("ffmpeg", MpvLogLevelPolicy.specFor(liveOpen = false)), "steady state")
        assertEquals("no", MpvLogLevelPolicy.levelFor("ffmpeg", MpvLogLevelPolicy.MSG_LEVEL), "init level")
        assertEquals("no", MpvLogLevelPolicy.levelFor("stream", MpvLogLevelPolicy.specFor(liveOpen = true)), "stream formats the URL")
        assertEquals("no", MpvLogLevelPolicy.levelFor("cplayer", MpvLogLevelPolicy.specFor(liveOpen = true)), "cplayer prints the URL")
        assertEquals("warn", MpvLogLevelPolicy.levelFor("vo/gpu", MpvLogLevelPolicy.specFor(liveOpen = true)), "outputs keep their level")
    }
}
