package com.nuvio.app.features.iptv

/**
 * The catalog+playback surface the IPTV hub, search index and detail/stream short-circuits use,
 * abstracted over the two source types a playlist can have:
 *   - `xtream`  -> [XtreamClient] (talks player_api.php, builds stream URLs from creds)
 *   - `m3u_url` -> [M3UClient]    (streams+parses one M3U URL into a local content DB, then serves
 *                                  the SAME domain models from that DB; stream URL = the M3U line)
 *
 * Both emit the identical domain models ([XtreamChannel]/[XtreamMovie]/[XtreamSeriesItem]/…), so
 * everything downstream (registry ids, meta, streams, player) is source-agnostic. Route to the right
 * implementation with [forAccount].
 *
 * ponytail: this is a thin dispatch seam, not a provider framework. [XtreamClient] keeps all its
 * existing static methods too (Sports Centre / Radar call them directly), so adding the interface is
 * additive — nothing that already compiled changes shape.
 */
interface IptvClient {

    /** Confirms the source is reachable/usable before it's saved. */
    suspend fun verify(acc: XtreamAccount): Result<Unit>

    suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>>
    suspend fun liveChannels(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamChannel>>
    suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>>
    suspend fun vodMovies(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamMovie>>
    suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>>
    suspend fun series(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamSeriesItem>>

    /** Now/next EPG for a live channel. Empty for M3U in P2 (XMLTV lands in P2c). */
    suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int = 4): Result<List<XtreamProgram>>

    /** Rich VOD detail (plot/tmdb/container ext). M3U returns just what the playlist row carried. */
    suspend fun vodInfo(acc: XtreamAccount, vodId: Int): Result<XtreamVodDetail?>

    /** Series detail incl. episode list. M3U rebuilds it from the grouped episode rows. */
    suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail?>

    fun movieStreamUrl(acc: XtreamAccount, streamId: Int, ext: String = "mp4"): String
    fun liveStreamUrl(acc: XtreamAccount, streamId: Int): String
    fun episodeStreamUrl(acc: XtreamAccount, episodeId: String, ext: String = "mp4"): String

    companion object {
        /** Picks the client for a playlist by its [XtreamAccount.sourceType]. */
        fun forAccount(acc: XtreamAccount): IptvClient = when {
            acc.sourceType.isM3u() -> M3UClient
            acc.sourceType == SOURCE_TYPE_STALKER -> com.nuvio.app.features.iptv.stalker.StalkerClient
            else -> XtreamClient
        }
    }
}

const val SOURCE_TYPE_XTREAM = "xtream"
const val SOURCE_TYPE_M3U_URL = "m3u_url"
const val SOURCE_TYPE_STALKER = "stalker"

/** Either M3U source type (URL or local file) — both ride [M3UClient] + the parsed content DB. */
fun String.isM3u(): Boolean = this == SOURCE_TYPE_M3U_URL || this == SOURCE_TYPE_M3U_FILE
