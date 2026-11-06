package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals

class GuideHistorySourceTest {
    // iPhone pass 11-05: a channel without catch-up showed "No EPG" when the guide travelled back, though its
    // XMLTV rows (6 h back) were on disk.
    @Test fun m3uChannelWithoutCatchUpStillShowsItsPast() {
        assertEquals(GuideHistorySource.Source.STORED_XMLTV, GuideHistorySource.forChannel(isM3u = true, hasCatchUp = false))
    }
    @Test fun m3uCatchUpChannelReadsItsStoredGuide() {
        assertEquals(GuideHistorySource.Source.STORED_XMLTV, GuideHistorySource.forChannel(isM3u = true, hasCatchUp = true))
    }
    @Test fun xtreamReadsItsPerChannelTable() {
        assertEquals(GuideHistorySource.Source.CATCHUP_TABLE, GuideHistorySource.forChannel(isM3u = false, hasCatchUp = false))
    }
}
