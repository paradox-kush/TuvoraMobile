package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.content.IptvContentKind
import com.nuvio.app.features.iptv.content.IptvEpisodeRow
import com.nuvio.app.features.iptv.content.IptvSeriesRow
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.iptv.identity.M3uIdentity
import com.nuvio.app.features.iptv.identity.M3uSeriesGrouping

/** What one parsed M3U entry becomes in the content DB (pure — see [M3uIngestMapping]). */
internal sealed interface M3uIngestRow {
    val kind: IptvContentKind
    val categoryId: String

    data class Channel(val row: IptvStreamRow, override val categoryId: String) : M3uIngestRow {
        override val kind get() = IptvContentKind.LIVE
    }
    data class Movie(val row: IptvStreamRow, override val categoryId: String) : M3uIngestRow {
        override val kind get() = IptvContentKind.VOD
    }
    data class Episode(val series: IptvSeriesRow, val row: IptvEpisodeRow, override val categoryId: String) : M3uIngestRow {
        override val kind get() = IptvContentKind.SERIES
    }
}

/**
 * The pure decision "which row, under which id, does this M3U entry become" — every id a synced
 * content id is built from (channel / movie sid, series sid, episode id, category id) is decided here,
 * so it is unit-tested on both runners without a database. B64: ids hash the stream URL with the
 * playlist's login removed ([M3uIdentity.itemSid]) — a password change keeps every favourite /
 * Continue Watching / watched mark — and a `/movie/` row named "Show S01E02" is an episode of Show
 * ([M3uSeriesGrouping], D2). Twin: NuvioTV `core/iptv/M3uIngestMapping.kt` (same ids).
 */
internal object M3uIngestMapping {

    /**
     * [login] is the playlist's login ([M3uIdentity.loginOf] its URL); [episodeOrdinal] is the
     * fallback episode number for an episode whose name carries none.
     */
    fun map(entry: M3UParser.Entry, login: M3uIdentity.Login?, episodeOrdinal: Int): M3uIngestRow {
        val catId = M3UClient.categoryId(entry.group)
        return when (entry.kind) {
            M3UKind.LIVE -> M3uIngestRow.Channel(
                IptvStreamRow(
                    M3uIdentity.itemSid(entry.url, login), entry.name, entry.logo, entry.tvgId, catId, entry.url, entry.ext,
                    catchUp = entry.catchUp,
                ),
                catId,
            )
            M3UKind.MOVIE -> {
                // D2: "Show S01E02" shipped as a /movie/ row is an episode of Show (TV's promotion, shared).
                val promoted = M3uSeriesGrouping.promotion(entry.name, entry.tvgName, entry.group)
                if (promoted != null) {
                    episode(entry, promoted.seriesKey, promoted.season, promoted.episode, login, catId)
                } else {
                    M3uIngestRow.Movie(
                        IptvStreamRow(M3uIdentity.itemSid(entry.url, login), entry.name, entry.logo, null, catId, entry.url, entry.ext),
                        catId,
                    )
                }
            }
            M3UKind.SERIES -> episode(
                entry, entry.seriesKey ?: entry.name, entry.season ?: 1, entry.episode ?: (episodeOrdinal % 10_000), login, catId,
            )
        }
    }

    private fun episode(
        entry: M3UParser.Entry, key: String, season: Int, episode: Int, login: M3uIdentity.Login?, catId: String,
    ): M3uIngestRow.Episode {
        val seriesSid = M3uSeriesGrouping.seriesSid(key)
        return M3uIngestRow.Episode(
            series = IptvSeriesRow(seriesSid, M3UClient.seriesTitle(key), entry.logo, catId),
            row = IptvEpisodeRow(
                seriesSid = seriesSid,
                episodeId = M3uIdentity.episodeId(entry.url, login),
                name = entry.name,
                season = season,
                episode = episode,
                logo = entry.logo,
                url = entry.url,
                ext = entry.ext,
            ),
            categoryId = catId,
        )
    }
}
