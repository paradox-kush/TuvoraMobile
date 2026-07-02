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
                // catalogs carry several editions (4K/HD/language) of the same film —
                // surface them all: by shared tmdb id where the panel provides ids, else
                // by shared normalized name (year-guarded; the verified match stays first)
                val editions = XtreamMatchIndex.byTmdb(acc.id, kind, tmdbId)
                    .ifEmpty { sameNameEditions(acc.id, kind, match.item, titles.year) }
                editions.map { item ->
                    // items synthesized from a synced mapping have no container ext — look it
                    // up so the stream URL is right on panels that don't use mp4
                    val ext = item.ext ?: XtreamClient.vodInfo(acc, item.sid).getOrNull()?.containerExtension ?: "mp4"
                    StreamItem(
                        // the panel's own catalog name — carries the useful bits (4K/NF/language)
                        name = item.name,
                        title = null,
                        url = XtreamClient.movieStreamUrl(acc, item.sid, ext),
                        addonName = acc.name,
                        addonId = groupId(acc),
                    )
                }
            }
            MatchKind.SERIES -> {
                val s = season ?: return emptyList()
                val e = episode ?: return emptyList()
                val editions = XtreamMatchIndex.byTmdb(acc.id, kind, tmdbId)
                    .ifEmpty { sameNameEditions(acc.id, kind, match.item, titles.year) }
                    .take(MAX_SERIES_EDITIONS) // one get_series_info per edition — bound it
                editions.flatMap { ed ->
                    val detail = XtreamClient.seriesInfo(acc, ed.sid).getOrNull() ?: return@flatMap emptyList<StreamItem>()
                    detail.episodes.filter { it.season == s && it.episodeNum == e }.map { ep ->
                        StreamItem(
                            name = "S${s}E${e} · ${ep.title}",
                            // the edition's catalog name so language variants are tellable apart
                            title = ed.name,
                            url = XtreamClient.episodeStreamUrl(acc, ep.episodeId, ep.containerExtension ?: "mp4"),
                            addonName = acc.name,
                            addonId = groupId(acc),
                        )
                    }
                }
            }
        }
    }

    /**
     * Editions of the same title on panels that ship no tmdb ids: items sharing the matched
     * item's normalized name key, year-compatible with the target. The verified match leads.
     */
    private suspend fun sameNameEditions(provider: String, kind: MatchKind, matched: IndexedItem, targetYear: Int?): List<IndexedItem> {
        val key = TitleNormalizer.normKey(matched.name)
        if (key.isEmpty()) return listOf(matched)
        val siblings = XtreamMatchIndex.probe(provider, kind, key).filter {
            it.year == null || targetYear == null || (if (it.year > targetYear) it.year - targetYear else targetYear - it.year) <= 1
        }
        return (listOf(matched) + siblings).distinctBy { it.sid }
    }

    private const val MAX_SERIES_EDITIONS = 5
}
