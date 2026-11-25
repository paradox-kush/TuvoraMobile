package com.nuvio.app.features.watchprogress

/**
 * Pure bookkeeping for "was this title played on THIS device": an insertion-ordered, capped set of content
 * ids (the Continue Watching dedupe key, i.e. parentMetaId). Never synced; see [ThisDevicePlayback].
 */
internal object ThisDevicePlaybackPolicy {
    const val MAX_IDS = 300
    private const val SEPARATOR = '\n'

    /** Moves [id] to the most-recent end; drops the oldest beyond [MAX_IDS]. Same list when nothing changes. */
    fun record(current: List<String>, id: String): List<String> {
        val clean = id.trim()
        if (clean.isEmpty() || SEPARATOR in clean) return current
        if (current.lastOrNull() == clean) return current
        val next = current.filterNot { it == clean } + clean
        return if (next.size > MAX_IDS) next.takeLast(MAX_IDS) else next
    }

    fun encode(ids: List<String>): String = ids.joinToString(SEPARATOR.toString())

    fun decode(payload: String?): List<String> =
        payload.orEmpty().split(SEPARATOR).map(String::trim).filter(String::isNotEmpty).takeLast(MAX_IDS)
}
