package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.epg.ChannelNameCleaner
import com.nuvio.app.features.epg.EpgIngestStaging
import com.nuvio.app.features.epg.GuideChannelMatcher
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One bounded disk spool per guide. Match the complete census once, then replay selected rows.
 * No extra network request and no programme list retained in memory. Caller must [dispose]. */
internal class XmltvGuideReplay(
    private val lineup: List<GuideChannelMatcher.LineupChannel>,
    private val picked: Set<String>,
    private val rules: ChannelNameCleaner.Rules,
    private val nowMs: Long,
    private val maxChars: Long = 256L * 1024 * 1024,
    /** M3U channels that replay: stream id -> `catchup-days`. Their guide keeps that many days of past programmes. */
    private val catchUpDaysBySid: Map<Int, Int> = emptyMap(),
    /** (stream id, guide id) pairs the viewer picked by hand: they keep their channel's archive depth too. */
    private val manualAssignments: List<Pair<Int, String>> = emptyList(),
) {
    private val staging = EpgIngestStaging()
    val guide = ArrayList<GuideChannelMatcher.GuideChannel>()
    private var stagedChars = 0L
    private var finished = false
    private val harvest = XmltvStreamingParser(
        keepChannelIds = emptySet(),
        onChannelNames = { id, names ->
            if (guide.size < 100_000) guide.add(GuideChannelMatcher.GuideChannel(id, names))
        },
        onProgramme = {},
    )

    fun feed(chunk: String) {
        check(!finished) { "XMLTV replay is already finished" }
        // A minified guide may arrive as one enormous line. Bound each staging record so the
        // Native reader never repeatedly copies a growing multi-megabyte line between reads.
        var offset = 0
        while (offset < chunk.length) {
            var end = minOf(offset + 16 * 1024, chunk.length)
            // Keep UTF-16 pairs together: each record is independently encoded as UTF-8 on disk.
            if (end < chunk.length && chunk[end - 1] in '\uD800'..'\uDBFF' && chunk[end] in '\uDC00'..'\uDFFF') end--
            val part = chunk.substring(offset, end)
            val encoded = Json.encodeToString(String.serializer(), part)
            stagedChars += encoded.length.toLong() + 1
            check(stagedChars <= maxChars) { "XMLTV replay size limit exceeded" }
            staging.append(encoded)
            harvest.feed(part)
            offset = end
        }
    }

    fun finish(onProgramme: (XmltvProgramme) -> Unit): GuideChannelMatcher.Result {
        check(!finished) { "XMLTV replay is already finished" }
        finished = true
        harvest.finish()
        val result = GuideChannelMatcher.match(lineup, guide, rules)
        val allow = result.assignments.mapTo(HashSet()) { it.guideId }
        guide.forEach { g -> normalizeChannelId(g.id).let { if (it in picked) allow.add(it) } }
        val backByGuide = XmltvIngestWindow.backMsByGuide(
            result.assignments.map { it.streamId to it.guideId } + manualAssignments, catchUpDaysBySid,
        )
        val replay = XmltvStreamingParser(keepChannelIds = allow, onProgramme = { p ->
            val back = if (backByGuide.isEmpty()) XmltvIngestWindow.BACK_MS else backByGuide[normalizeChannelId(p.channelId)] ?: XmltvIngestWindow.BACK_MS
            if (XmltvIngestWindow.keeps(p.startMs, p.endMs, nowMs, back)) onProgramme(p)
        })
        staging.openForRead()
        var replayedChars = 0L
        while (true) {
            val encoded = staging.nextLine() ?: break
            replayedChars += encoded.length.toLong() + 1
            val chunk = Json.decodeFromString(String.serializer(), encoded)
            replay.feed(chunk)
        }
        check(replayedChars == stagedChars) { "XMLTV replay was truncated" }
        replay.finish()
        return result
    }

    fun dispose() = staging.dispose()
}
