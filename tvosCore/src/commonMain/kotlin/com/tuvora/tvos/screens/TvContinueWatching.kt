package com.tuvora.tvos.screens

import com.nuvio.app.features.home.ResumeCandidate
import com.nuvio.app.features.home.ResumeKind
import com.nuvio.app.features.home.ResumeRankPolicy

/** One Continue Watching card, as NuvioTV's ContinueWatchingSection draws it. */
data class TvCwItem(
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val episodeTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val artwork: String?,
    val logo: String?,
    val background: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdatedEpochMs: Long,
    val isNextUp: Boolean,
    /** The title's portrait poster, for NuvioTV's POSTER Continue Watching style. */
    val poster: String? = null,
    /** A Next Up episode's air date (epoch ms), when the add-on gave one; lets resume-first order unaired ones last. */
    val releasedAtMs: Long? = null,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Merges in-progress items and Next Up items into the Continue Watching row: newest first, Next Up
 * suppressed for any series already in progress, capped. Pure, so tested without repositories.
 */
object TvContinueWatching {
    /** Room for every Next Up lookup (TvContinueWatchingSources.NEXT_UP_LOOKUPS) plus recent in-progress titles. */
    const val MAX_ITEMS = 40

    /**
     * With [resumeFirst] ("Pick up where I left off", the phone's Order setting) the row is ordered by
     * [ResumeRankPolicy]; otherwise newest first exactly as before. [devicePlayedIds] are the parentMetaIds
     * played on this device; [nowMs] is the caller's clock (the policy itself has none).
     */
    fun merge(
        inProgress: List<TvCwItem>,
        nextUp: List<TvCwItem>,
        resumeFirst: Boolean = false,
        devicePlayedIds: Set<String> = emptySet(),
        nowMs: Long = 0L,
    ): List<TvCwItem> {
        val inProgressSeries = inProgress.map { it.parentMetaId }.toSet()
        val combined = (inProgress + nextUp.filter { it.parentMetaId !in inProgressSeries })
            .distinctBy { it.parentMetaId to it.videoId }
        if (!resumeFirst) return combined.sortedByDescending { it.lastUpdatedEpochMs }.take(MAX_ITEMS)

        val byKey = LinkedHashMap<String, TvCwItem>()
        val candidates = combined.map { item ->
            val key = "${item.parentMetaId}|${item.videoId}"
            byKey[key] = item
            ResumeCandidate(
                key = key,
                kind = if (item.isNextUp) ResumeKind.NextUp else ResumeKind.Resume,
                lastEngagedAtMs = item.lastUpdatedEpochMs,
                progressFraction = if (item.isNextUp || item.durationMs <= 0) null else item.progress,
                releasedAtMs = item.releasedAtMs,
                playedOnThisDevice = item.parentMetaId in devicePlayedIds,
            )
        }
        return ResumeRankPolicy.rank(candidates, nowMs).mapNotNull(byKey::get).take(MAX_ITEMS)
    }

    /** The top-right badge: "Next Up", or time left like NuvioTV ("8m left", "1h 49m left"). */
    fun badge(item: TvCwItem): String? {
        if (item.isNextUp) return "Next Up"
        val leftMin = minutesLeft(item) ?: return null
        val h = leftMin / 60
        val m = leftMin % 60
        return if (h > 0) "${h}h ${m}m left" else "${m}m left"
    }

    /**
     * Whole minutes still to watch, or null when there is nothing to show (Next Up, unknown
     * duration, finished). The screen formats it in the viewer's language; [badge] is the English form.
     */
    fun minutesLeft(item: TvCwItem): Int? {
        if (item.isNextUp || item.durationMs <= 0) return null
        val leftMin = ((item.durationMs - item.positionMs).coerceAtLeast(0) / 60_000).toInt()
        return leftMin.takeIf { it > 0 }
    }

    /** "S2 E1" for episodes, null for movies. */
    fun episodeLabel(item: TvCwItem): String? =
        if (item.seasonNumber != null && item.episodeNumber != null) "S${item.seasonNumber} E${item.episodeNumber}" else null
}
