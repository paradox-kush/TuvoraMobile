package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.GuideDataRefreshPolicy

/**
 * What the Apple TV guide re-asks when new guide data lands while it is open.
 *
 * The race (reproduced on Apple TV 2026-10-08): the guide opened while a playlist's XMLTV ingest was
 * still downloading. Every row answered empty and [TvGuideEpg] stamped each (row, window) as asked
 * once, so the guide kept saying "No information" with the programmes already on disk, until the
 * user left and re-entered. [XtreamHubRepository.guideDataGeneration] bumps when data lands; this
 * turns that bump into the stamps to forget and the rows to ask again.
 *
 * It reuses the shared [GuideDataRefreshPolicy] (the phone's docked guide runs the same decision),
 * adapted to this guide's stamp format ([TvGuideEpgPrefetch.key]). Only rows that answered empty are
 * re-asked, plus the focused channel, so new data costs a handful of store reads.
 */
object TvGuideDataRefresh {

    /** A guide skips the generation it opened with; only a later bump means new data. */
    fun changed(seenGeneration: Long, generation: Long): Boolean =
        GuideDataRefreshPolicy.changedSince(seenGeneration, generation)

    /** [dropKeys] leave the once-only set; [rowIds] are asked again (focused first), the rest untouched. */
    data class Plan(val dropKeys: Set<String>, val rowIds: List<String>)

    /**
     * @param askedKeys the once-only `"<contentId>@<windowStartMs>"` stamps made so far
     * @param answeredKeys the stamps whose answer had at least one programme
     * @param shownRowIds the rows of the last settled request
     * @param focusedId the focused channel (always re-asked: a manual guide pick changes its data)
     */
    fun plan(
        askedKeys: Set<String>,
        answeredKeys: Set<String>,
        shownRowIds: List<String>,
        focusedId: String?,
        windowStartMs: Long,
    ): Plan {
        if (shownRowIds.isEmpty()) return Plan(emptySet(), emptyList())
        val plan = GuideDataRefreshPolicy.plan(
            requestedStamps = askedKeys,
            hasProgrammes = { TvGuideEpgPrefetch.key(it, windowStartMs) in answeredKeys },
            visible = shownRowIds,
            current = focusedId?.takeIf { it in shownRowIds } ?: shownRowIds.first(),
            anchorMs = windowStartMs,
        )
        return Plan(plan.dropStamps, plan.reask)
    }
}
