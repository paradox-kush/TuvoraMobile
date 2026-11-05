package com.nuvio.app.features.livetv

/**
 * Where the docked guide reads a channel's PAST rows from.
 *
 * An M3U playlist's past is its own XMLTV guide on disk: every channel keeps at least
 * [com.nuvio.app.features.iptv.epg.XmltvIngestWindow.BACK_MS] of it, and a catch-up channel keeps its
 * whole archive. Without catch-up, the past still shows. It just can't be replayed: the cell has no
 * replay intent. Xtream panels read their per-channel table instead.
 */
internal object GuideHistorySource {
    enum class Source { STORED_XMLTV, CATCHUP_TABLE }

    fun forChannel(isM3u: Boolean, hasCatchUp: Boolean): Source =
        if (isM3u) Source.STORED_XMLTV else Source.CATCHUP_TABLE
}
