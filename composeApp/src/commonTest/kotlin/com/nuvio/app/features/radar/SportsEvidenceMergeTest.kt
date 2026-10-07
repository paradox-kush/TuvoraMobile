package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportsEvidenceMergeTest {
    private val channel = RadarChannelMatcher.CandidateChannel("p","Provider","channel","ESPN",null,1)
    @Test fun merge_keeps_programme_and_confidence_with_their_source() {
        val listing = RadarChannelMatcher.ChannelMatch(channel,null,80,via=RadarChannelMatcher.MatchVia.LISTING,
            confidence=MatchConfidence.CONFIRMED,evidence=SportsMatchEvidence(SportsEvidenceSource.LISTING,MatchConfidence.CONFIRMED,80,listOf("listed")))
        val weakGuide = RadarChannelMatcher.ChannelMatch(channel,SportsProgramme("NBA Studio","",1,2),999,
            via=RadarChannelMatcher.MatchVia.EPG,confidence=MatchConfidence.LEAGUE,
            evidence=SportsMatchEvidence(SportsEvidenceSource.PROGRAMME,MatchConfidence.LEAGUE,20,listOf("competition_only")))
        val winner=RadarChannelMatcher.mergeMatches(listOf(weakGuide,listing)).single()
        assertEquals(RadarChannelMatcher.MatchVia.LISTING,winner.via)
        assertNull(winner.programme)
        assertEquals(listing.evidence,winner.evidence)
    }
    @Test fun preference_cannot_admit_rejected_or_outrank_identity() {
        val rejected=RadarChannelMatcher.ChannelMatch(channel,null,999,
            evidence=SportsMatchEvidence(SportsEvidenceSource.EVENT_NAME,MatchConfidence.LEAGUE,0,listOf("insufficient_identity")))
        val competition=RadarChannelMatcher.ChannelMatch(channel,null,999,
            evidence=SportsMatchEvidence(SportsEvidenceSource.EVENT_NAME,MatchConfidence.LEAGUE,20,listOf("competition_only")))
        val specific=RadarChannelMatcher.ChannelMatch(channel.copy(contentId="specific"),null,1,
            confidence=MatchConfidence.POSSIBLE,evidence=SportsMatchEvidence(SportsEvidenceSource.EVENT_NAME,MatchConfidence.POSSIBLE,60,listOf("participant_pair")))
        val result=RadarChannelMatcher.mergeMatches(listOf(rejected,competition,specific,specific))
        assertEquals(listOf("specific","channel"),result.map { it.channel.contentId })
    }
}
