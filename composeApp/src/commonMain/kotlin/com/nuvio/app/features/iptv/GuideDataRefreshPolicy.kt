package com.nuvio.app.features.iptv

/**
 * What an open guide surface re-asks for when new guide data lands (an XMLTV ingest, a mirror sync
 * or a manual guide pick — see [XtreamHubRepository.onGuideDataChanged]).
 *
 * The race this exists for (reproduced 2026-10-08 on Android and iOS): a guide opened while a
 * playlist's XMLTV ingest was still downloading. Every row answered empty and was stamped
 * once-per-window, so the guide kept showing "No EPG" with the programmes already on disk — until
 * the user left and re-entered. Tiles had the same once-only shape ("No information").
 *
 * Only rows that answered empty are re-asked (plus the focused channel, whose data a manual pick
 * changes), so new guide data costs a handful of store reads, never a re-fetch of a full screen.
 */
internal object GuideDataRefreshPolicy {

    /** A surface skips the generation it was composed with; only a later bump means new data. */
    fun changedSince(seenGeneration: Long, generation: Long): Boolean = generation != seenGeneration

    data class Plan(val dropStamps: Set<String>, val reask: List<String>)

    /**
     * Docked guide: which once-only `"<contentId>@<anchorMs>"` window stamps to drop, and which rows
     * to ask again, focused channel first. [visible] is the last settled window of rows.
     */
    fun plan(
        requestedStamps: Set<String>,
        hasProgrammes: (String) -> Boolean,
        visible: List<String>,
        current: String,
        anchorMs: Long,
    ): Plan {
        val reask = buildList {
            add(current)
            visible.filterTo(this) { it != current && !hasProgrammes(it) }
        }
        val dropStamps = reask.map { "$it@$anchorMs" }.filterTo(mutableSetOf()) { it in requestedStamps }
        return Plan(dropStamps, reask)
    }

    /**
     * Key for a tile's EPG request: constant once the tile shows a programme (no re-ask), the guide
     * data generation while it shows none (re-ask when new data lands).
     */
    fun tileHealKey(hasProgramme: Boolean, generation: Long): Long = if (hasProgramme) -1L else generation
}
