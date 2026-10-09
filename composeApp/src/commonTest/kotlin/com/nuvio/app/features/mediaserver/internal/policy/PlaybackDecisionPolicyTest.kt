package com.nuvio.app.features.mediaserver.internal.policy

import com.nuvio.app.core.contracts.PlaybackPlayMethod
import com.nuvio.app.features.mediaserver.internal.policy.PlaybackDecisionPolicy.Plan
import com.nuvio.app.features.mediaserver.internal.policy.PlaybackDecisionPolicy.SourceFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackDecisionPolicyTest {
    private fun source(
        id: String? = "src1", protocol: String? = "File", container: String? = "mkv",
        direct: Boolean = true, stream: Boolean = true, transcode: Boolean = true,
        directUrl: String? = null, transcodeUrl: String? = "/videos/1/master.m3u8?MediaSourceId=src1&ApiKey=server-built",
    ) = SourceFacts(id, protocol, container, direct, stream, transcode, directUrl, transcodeUrl)

    @Test
    fun jellyfinHttpDirectPlayUsesTheServerProxyWithoutOptionalUrls() {
        val decision = PlaybackDecisionPolicy.decide(source(protocol="Http", directUrl=null, transcodeUrl=null), null, false, supportsStaticHttp=true)
        assertEquals(Plan.StaticStream("src1"), decision.plan)
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, decision.method)
        val other = PlaybackDecisionPolicy.decide(source(protocol="Rtmp", directUrl=null, transcodeUrl=null), null, false, supportsStaticHttp=true)
        assertTrue(other.plan is Plan.NotPlayable)
        val disabled = PlaybackDecisionPolicy.decide(source(protocol="Http", direct=false, stream=false, transcode=false, transcodeUrl=null), null, false, supportsStaticHttp=true)
        assertTrue(disabled.plan is Plan.NotPlayable)
    }

    @Test
    fun directPlayIsTheDefaultAndPinsTheMediaSource() {
        val d = PlaybackDecisionPolicy.decide(source(), userBitrateCap = null, directPlayFailed = false)
        assertEquals(Plan.StaticStream("src1"), d.plan)
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, d.method)
    }

    @Test
    fun aUserQualityCapTranscodes() {
        val d = PlaybackDecisionPolicy.decide(source(), userBitrateCap = 4_000_000, directPlayFailed = false)
        assertEquals(PlaybackPlayMethod.TRANSCODE, d.method)
        assertEquals(Plan.ServerUrl("/videos/1/master.m3u8?MediaSourceId=src1&ApiKey=server-built"), d.plan)
    }

    @Test
    fun aFailedDirectPlayFallsBackToATranscode() {
        val d = PlaybackDecisionPolicy.decide(source(), userBitrateCap = null, directPlayFailed = true)
        assertEquals(PlaybackPlayMethod.TRANSCODE, d.method)
    }

    @Test
    fun aCapThatTheServerCannotHonourStillPlays() {
        val d = PlaybackDecisionPolicy.decide(source(transcode = false, transcodeUrl = null), userBitrateCap = 1_000_000, directPlayFailed = false)
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, d.method, "better the original than nothing")
    }

    @Test
    fun aNonFileSourceNeverUsesTheStaticStream() {
        // Unverified dialects keep explicit server URLs; Jellyfin HTTP is tested separately.
        val d = PlaybackDecisionPolicy.decide(source(protocol = "Http", directUrl = "https://cdn.example/x.mp4"), null, false)
        assertEquals(Plan.ServerUrl("https://cdn.example/x.mp4"), d.plan)
        assertEquals(PlaybackPlayMethod.DIRECT_STREAM, d.method)
        val viaTranscode = PlaybackDecisionPolicy.decide(source(protocol = "Http", directUrl = null), null, false)
        assertEquals(PlaybackPlayMethod.TRANSCODE, viaTranscode.method)
        val nothing = PlaybackDecisionPolicy.decide(source(protocol = "Http", directUrl = null, transcodeUrl = null), null, false)
        assertEquals(Plan.NotPlayable(PlaybackDecisionPolicy.Reason.NO_PLAYABLE_PATH), nothing.plan)
        assertNull(nothing.method)
    }

    @Test
    fun aRepackageableButNotDirectPlayableFileIsADirectStream() {
        val d = PlaybackDecisionPolicy.decide(source(direct = false, stream = true), null, false)
        assertEquals(PlaybackPlayMethod.DIRECT_STREAM, d.method)
        assertEquals(Plan.ServerUrl("/videos/1/master.m3u8?MediaSourceId=src1&ApiKey=server-built"), d.plan)
        val noUrl = PlaybackDecisionPolicy.decide(source(direct = false, stream = true, transcodeUrl = null), null, false)
        assertEquals(Plan.StaticStream("src1"), noUrl.plan)
    }

    @Test
    fun onlyTranscodableMeansTranscode() {
        val d = PlaybackDecisionPolicy.decide(source(direct = false, stream = false), null, false)
        assertEquals(PlaybackPlayMethod.TRANSCODE, d.method)
        val none = PlaybackDecisionPolicy.decide(source(direct = false, stream = false, transcode = false), null, false)
        assertEquals(Plan.NotPlayable(PlaybackDecisionPolicy.Reason.NO_PLAYABLE_PATH), none.plan)
    }

    @Test
    fun aMissingProtocolIsTreatedAsAFile() {
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, PlaybackDecisionPolicy.decide(source(protocol = null), null, false).method)
    }

    @Test
    fun wireMethodNamesAndTicks() {
        assertEquals("DirectPlay", PlaybackDecisionPolicy.wireMethod(PlaybackPlayMethod.DIRECT_PLAY))
        assertEquals("DirectStream", PlaybackDecisionPolicy.wireMethod(PlaybackPlayMethod.DIRECT_STREAM))
        assertEquals("Transcode", PlaybackDecisionPolicy.wireMethod(PlaybackPlayMethod.TRANSCODE))
        assertEquals("DirectPlay", PlaybackDecisionPolicy.wireMethod(null))
        assertEquals(1_000L, PlaybackDecisionPolicy.ticksToMs(10_000_000L))
        assertEquals(10_000_000L, PlaybackDecisionPolicy.msToTicks(1_000L))
    }

    private fun offer(sp: Long?, spAt: Long? = 2_000, tp: Long? = 0, tpAt: Long? = 1_000, dur: Long? = 7_200_000) =
        PlaybackDecisionPolicy.resumeOffer(sp, spAt, tp, tpAt, dur)

    @Test
    fun watchedElsewhereOffersTheServerPositionWhenTuvoraHasItsOwnRecord() {
        assertEquals(PlaybackDecisionPolicy.ResumeOffer(1_800_000, startAutomatically = false), offer(1_800_000, spAt = 5_000, tp = 600_000, tpAt = 1_000))
    }

    @Test
    fun withNoLocalProgressTheServerPositionStartsAutomatically() {
        // the references pass the server's position up front (StartTimeTicks): nothing to ask when Tuvora knows nothing
        val auto = PlaybackDecisionPolicy.ResumeOffer(1_800_000, startAutomatically = true)
        assertEquals(auto, offer(1_800_000, tp = null, tpAt = null), "no Tuvora record at all")
        assertEquals(auto, offer(1_800_000, tp = 0, tpAt = 1_000), "a record at 0:00")
        assertEquals(auto, offer(1_800_000, tp = 4_000, tpAt = 1_000), "a few seconds in is not progress worth keeping")
        assertEquals(auto, offer(1_800_000, spAt = null, tp = null, tpAt = null), "no timestamps")
    }

    @Test
    fun noOfferWhenTuvoraIsNewerOrThePositionsAgree() {
        assertNull(offer(1_800_000, spAt = 500, tp = 600_000, tpAt = 1_000), "Tuvora's record is newer")
        assertNull(offer(1_800_000, tp = 1_790_000), "within 30 s")
        assertNull(offer(5_000), "less than 10 s in")
        assertNull(offer(null))
        assertNull(offer(7_000_000), "server has it practically finished")
    }

    @Test
    fun withoutTimestampsTheFurtherPositionWins() {
        assertEquals(PlaybackDecisionPolicy.ResumeOffer(1_800_000, startAutomatically = false), offer(1_800_000, spAt = null, tp = 100_000, tpAt = null))
        assertNull(offer(100_000, spAt = null, tp = 1_800_000, tpAt = null))
    }
}
