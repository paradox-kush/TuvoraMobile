package com.nuvio.app.features.radar

import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamSearchIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * "Which of MY channels is showing this match?" — the Sports Centre matcher.
 *
 * The CORE is source-agnostic: it scores [CandidateChannel]s (plain data) against a fixture,
 * with an optional per-candidate EPG lookup. Today the single assembly function pulls
 * candidates from Xtream playlists; when the playlist-manager feature lands M3U/Stalker,
 * extend [assembleCandidates] to add their channels (content DB) and route [epgFor] through
 * `epg_programmes` first — the core never changes. (See radar-feature-requirements.md §5.)
 *
 * Layered because real-panel EPG is sparse-to-empty: name matches alone must produce results.
 */
object RadarChannelMatcher {

    data class CandidateChannel(
        val playlistId: String,
        val playlistName: String,
        /** Plays through the app's existing live route (registry-registered). */
        val contentId: String,
        val name: String,
        val logo: String?,
        /** Source-specific EPG handle; for Xtream it's the stream id. */
        val streamId: Int,
    )

    data class ChannelMatch(
        val channel: CandidateChannel,
        /** The EPG programme that matched, when the panel has EPG for this channel. */
        val programme: XtreamProgram?,
        val score: Int,
    )

    private const val NAME_POOL_CAP = 200
    private const val EPG_PROBE_CAP = 40
    private const val EPG_CONCURRENCY = 8
    private const val RESULT_CAP = 10

    // Channel-name markers of generic sports channels — weak candidates that the EPG stage
    // can confirm even when no league keyword appears in the channel name.
    private val GENERIC_SPORT_MARKERS = listOf(
        "sport", "espn", "bein", "dazn", "eurosport", "supersport", "fox sports",
        "sky sports", "tnt sports", "arena", "match!", "setanta", "premier sports",
    )

    private val STOP_TOKENS = setOf("fc", "cf", "sc", "afc", "rc", "cd", "ac", "de", "the", "club", "los", "las")

    /**
     * Match a fixture against every enabled playlist's channels. [onPartial] fires once with
     * the quick name-based matches so the sheet can render before EPG probes finish.
     */
    suspend fun match(
        fixture: RadarFixture,
        league: RadarLeague?,
        onPartial: (List<ChannelMatch>) -> Unit = {},
    ): List<ChannelMatch> {
        val keywords = buildList {
            league?.keywords?.forEach { add(normalize(it)) }
            fixture.league?.let { add(normalize(it)) }
        }.filter { it.isNotBlank() }.distinct()
        val homeTokens = teamTokens(fixture.home)
        val awayTokens = teamTokens(fixture.away)
        val eventTokens = if (homeTokens.isEmpty() && awayTokens.isEmpty()) teamTokens(fixture.event) else emptyList()

        val candidates = assembleCandidates()

        // Stage 1: name scores over the full pool (cheap, in-memory).
        val named = candidates.mapNotNull { c ->
            val score = nameScore(normalize(c.name), keywords, homeTokens, awayTokens, eventTokens)
            if (score > 0) ChannelMatch(c, programme = null, score = score) else null
        }.sortedByDescending { it.score }.take(NAME_POOL_CAP)

        onPartial(named.take(RESULT_CAP))

        // Stage 2: EPG probes for the strongest name candidates.
        val start = fixture.startEpochMs
        val probed = if (start == null) named else coroutineScope {
            val semaphore = Semaphore(EPG_CONCURRENCY)
            named.take(EPG_PROBE_CAP).map { m ->
                async {
                    semaphore.withPermit {
                        val programmes = epgFor(m.channel)
                        val hit = bestProgramme(programmes, start, fixture.sport, keywords, homeTokens, awayTokens, eventTokens)
                        if (hit != null) m.copy(programme = hit.first, score = m.score / 10 + hit.second) else m
                    }
                }
            }.awaitAll() + named.drop(EPG_PROBE_CAP)
        }

        return probed.sortedByDescending { it.score }.take(RESULT_CAP)
    }

    // --- source assembly (the ONLY source-specific part) ----------------------

    private suspend fun assembleCandidates(): List<CandidateChannel> {
        XtreamRepository.ensureLoaded()
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled }
        return accounts.flatMap { account ->
            XtreamSearchIndex.liveChannelsFor(account).map { ch ->
                CandidateChannel(
                    playlistId = account.id,
                    playlistName = account.name,
                    contentId = XtreamItemRegistry.liveId(account.id, ch.streamId),
                    name = ch.name,
                    logo = ch.logo,
                    streamId = ch.streamId,
                )
            }
        }
    }

    private suspend fun epgFor(channel: CandidateChannel): List<XtreamProgram> {
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == channel.playlistId }
            ?: return emptyList()
        return XtreamClient.shortEpg(account, channel.streamId, limit = 8).getOrDefault(emptyList())
    }

    /** Registers the match's channel so the play route can resolve its stream URL. */
    fun ensurePlayable(match: ChannelMatch) {
        if (XtreamItemRegistry.get(match.channel.contentId) != null) return
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == match.channel.playlistId } ?: return
        XtreamItemRegistry.register(
            com.nuvio.app.features.iptv.XtreamResolvedItem(
                contentId = match.channel.contentId,
                accountId = account.id,
                kind = com.nuvio.app.features.iptv.XtreamKind.LIVE,
                name = match.channel.name,
                streamUrl = XtreamClient.liveStreamUrl(account, match.channel.streamId),
                logo = match.channel.logo,
                streamType = "live",
            )
        )
    }

    // --- scoring (pure) --------------------------------------------------------

    private fun nameScore(
        name: String,
        keywords: List<String>,
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
    ): Int {
        if (name.isBlank()) return 0
        val homeHit = homeTokens.any { name.contains(it) }
        val awayHit = awayTokens.any { name.contains(it) }
        val keywordHit = keywords.any { name.contains(it) }
        val eventHit = eventTokens.count { name.contains(it) } >= 2
        val genericHit = GENERIC_SPORT_MARKERS.any { name.contains(it) }
        return when {
            homeHit && awayHit -> 50
            keywordHit -> 25
            eventHit -> 20
            homeHit || awayHit -> 12
            genericHit -> 8
            else -> 0
        }
    }

    private fun bestProgramme(
        programmes: List<XtreamProgram>,
        startMs: Long,
        sport: String?,
        keywords: List<String>,
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
    ): Pair<XtreamProgram, Int>? {
        val windowStart = startMs - 45 * 60 * 1000L
        val windowEnd = startMs + 4 * 60 * 60 * 1000L
        return programmes
            .filter { it.endMs > windowStart && it.startMs < windowEnd }
            .mapNotNull { p ->
                val text = normalize("${p.title} ${p.description}")
                if (text.isBlank()) return@mapNotNull null
                val home = homeTokens.any { text.contains(it) }
                val away = awayTokens.any { text.contains(it) }
                val keyword = keywords.any { text.contains(it) }
                val event = eventTokens.count { text.contains(it) } >= 2
                val score = when {
                    home && away -> 100
                    event -> 90
                    (home || away) && keyword -> 70
                    keyword -> 35
                    home || away -> 25
                    else -> 0
                }
                if (score > 0) p to score else null
            }
            .maxByOrNull { it.second }
    }

    private fun normalize(s: String?): String =
        (s ?: "").lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(" ").filter { it.isNotBlank() }.joinToString(" ")

    private fun teamTokens(team: String?): List<String> =
        normalize(team).split(" ").filter { it.length > 2 && it !in STOP_TOKENS }
}
