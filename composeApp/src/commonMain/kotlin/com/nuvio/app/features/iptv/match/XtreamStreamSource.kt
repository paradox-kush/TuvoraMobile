package com.nuvio.app.features.iptv.match

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tmdb.TmdbService

/**
 * Turns a TMDB movie/episode into playable Xtream [StreamItem]s for one account —
 * the bridge that lets IPTV VOD show up next to addon/debrid streams on TMDB-driven
 * detail screens. Returns empty (never throws) when the account doesn't carry the title.
 */
internal object XtreamStreamSource {
    private val log = Logger.withTag("XtreamStreamSource")

    fun groupId(acc: XtreamAccount): String = "xtream-match:${acc.id}"

    suspend fun streamsFor(acc: XtreamAccount, type: String, videoId: String, season: Int?, episode: Int?): List<StreamItem> {
        val kind = when (TmdbService.normalizeMediaType(type)) {
            "movie" -> MatchKind.MOVIE
            "tv" -> MatchKind.SERIES
            else -> return emptyList()
        }
        val tmdbId = TmdbService.ensureTmdbId(videoId, type)?.toIntOrNull() ?: run {
            log.w { "skip $videoId: no TMDB id (missing API key or unknown id)" }
            return emptyList()
        }
        val titles = TmdbService.titleBundle(tmdbId, type) ?: run {
            log.w { "skip tmdb=$tmdbId: title bundle unavailable (API key/network)" }
            return emptyList()
        }
        val match = XtreamTmdbResolver.resolve(acc, kind, tmdbId, titles) ?: return emptyList()

        return when (kind) {
            MatchKind.MOVIE -> {
                // id-tagged catalogs often carry several editions (4K/HD/language) of the same
                // film — surface them all as separate streams
                val editions = XtreamMatchIndex.byTmdb(acc.id, kind, tmdbId).ifEmpty { listOf(match.item) }
                editions.map { item ->
                    StreamItem(
                        // the panel's own catalog name — carries the useful bits (4K/NF/language)
                        name = item.name,
                        title = null,
                        url = XtreamClient.movieStreamUrl(acc, item.sid, item.ext ?: "mp4"),
                        addonName = acc.name,
                        addonId = groupId(acc),
                    )
                }
            }
            MatchKind.SERIES -> {
                val s = season ?: return emptyList()
                val e = episode ?: return emptyList()
                val detail = XtreamClient.seriesInfo(acc, match.item.sid).getOrNull() ?: return emptyList()
                detail.episodes.filter { it.season == s && it.episodeNum == e }.map { ep ->
                    StreamItem(
                        name = "S${s}E${e} · ${ep.title}",
                        title = detail.name ?: match.item.name,
                        url = XtreamClient.episodeStreamUrl(acc, ep.episodeId, ep.containerExtension ?: "mp4"),
                        addonName = acc.name,
                        addonId = groupId(acc),
                    )
                }
            }
        }
    }
}
