package com.nuvio.app.core.contracts

/**
 * Whether a library / Continue Watching item is an EPISODIC show: something that releases episodes on dates, so an
 * alert or a Calendar bell can mean something for it. The one rule Notifications uses.
 *
 * "tv" is both a show alias (TMDB media type) and the type of a live IPTV channel (a favourite or a pin puts one in
 * the Library). The id tells them apart: a channel's id is an own-source id (`xtream:acc:live:42`, see
 * [OwnSourcePolicy.isOwnContentId]); a show's is a TMDB/Cinemeta-style id. Movies and unknown types are never shows.
 */
fun isEpisodicShowType(type: String?, contentId: String?): Boolean = when (type?.trim()?.lowercase()) {
    "series", "show", "tvshow", "anime" -> true
    "tv" -> !OwnSourcePolicy.isOwnContentId(contentId)
    else -> false
}
