package com.nuvio.app.core.contracts

/**
 * Whether a library / Continue Watching content type is an EPISODIC show: something that releases episodes on dates,
 * so an alert or a Calendar bell can mean anything for it. The one rule shared by Notifications and the Calendar.
 *
 * Deliberately NOT "tv": that is the type of a live IPTV channel (a favourite or a pin puts it in the Library), which
 * never has an episode date. Movies and any unknown type are not episodic either.
 */
fun isEpisodicShowType(type: String?): Boolean = when (type?.trim()?.lowercase()) {
    "series", "show", "tvshow", "anime" -> true
    else -> false
}
