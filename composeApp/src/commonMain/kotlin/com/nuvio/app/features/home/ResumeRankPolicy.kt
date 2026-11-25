package com.nuvio.app.features.home

/** What a Continue Watching candidate is, as far as ranking cares. */
enum class ResumeKind {
    /** Started and not finished. */
    Resume,

    /** The next episode of a show whose previous episode was finished. */
    NextUp,

    /** A new episode (or season) release alert for a followed show. */
    NewEpisode,
}

/**
 * One row the Continue Watching list could show.
 *
 * [key] is the stable identity (the list's dedupe key). [isUnaired] is true when the item has not aired
 * yet; an unaired item with an unknown air date sets it with [releasedAtMs] = null. A [ResumeKind.NextUp]
 * whose [releasedAtMs] is in the future counts as unaired even when the flag is not set.
 */
data class ResumeCandidate(
    val key: String,
    val kind: ResumeKind,
    val lastEngagedAtMs: Long,
    val progressFraction: Float? = null,
    val releasedAtMs: Long? = null,
    val playedOnThisDevice: Boolean = false,
    val isUnaired: Boolean = false,
)

/**
 * Orders Continue Watching by "what would I pick up next" instead of pure recency
 * (SPEC streaming-retention 2.2). Pure: no clock, no I/O; the caller passes [nowMs].
 *
 * `effectiveTime = base + boost`, sorted descending, ties by key. Unaired items always come after every
 * aired item, nearest air date first, unknown dates last. Time of day is deliberately not a factor.
 */
object ResumeRankPolicy {
    const val HOUR_MS: Long = 60L * 60L * 1000L
    const val DAY_MS: Long = 24L * HOUR_MS

    const val RESUME_BOOST_MS: Long = 36L * HOUR_MS
    const val NEXT_UP_BOOST_MS: Long = 12L * HOUR_MS
    const val NEW_EPISODE_BOOST_MS: Long = 12L * HOUR_MS
    const val THIS_DEVICE_BOOST_MS: Long = 6L * HOUR_MS
    const val ALMOST_DONE_PENALTY_MS: Long = 24L * HOUR_MS
    const val BARELY_STARTED_PENALTY_MS: Long = 96L * HOUR_MS

    const val NEXT_UP_RECENT_WINDOW_MS: Long = 7L * DAY_MS
    const val NEW_EPISODE_WINDOW_MS: Long = 7L * DAY_MS
    const val NO_BOOST_AFTER_MS: Long = 30L * DAY_MS
    const val BARELY_STARTED_AGE_MS: Long = DAY_MS

    const val BARELY_STARTED_FRACTION: Float = 0.05f
    const val ALMOST_DONE_FRACTION: Float = 0.90f

    /** Keys in the order they should be shown. */
    fun rank(candidates: List<ResumeCandidate>, nowMs: Long): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val (unaired, aired) = candidates.partition { it.isUnairedAt(nowMs) }
        val airedOrder = aired
            .map { it to score(it, nowMs) }
            .sortedWith(
                compareByDescending<Pair<ResumeCandidate, Long>> { it.second }
                    .thenBy { it.first.key },
            )
            .map { it.first.key }
        val unairedOrder = unaired
            .sortedWith(
                compareBy<ResumeCandidate> { it.releasedAtMs == null }
                    .thenBy { it.releasedAtMs ?: 0L }
                    .thenBy { it.key },
            )
            .map { it.key }
        return airedOrder + unairedOrder
    }

    /** `base + boost` for an aired candidate (exposed so tests can pin the table). */
    fun score(candidate: ResumeCandidate, nowMs: Long): Long {
        val base = when (candidate.kind) {
            ResumeKind.NewEpisode -> maxOf(candidate.lastEngagedAtMs, candidate.releasedAtMs ?: Long.MIN_VALUE)
            else -> candidate.lastEngagedAtMs
        }
        val age = nowMs - candidate.lastEngagedAtMs
        val boostsAllowed = age <= NO_BOOST_AFTER_MS
        var adjustment = 0L

        if (boostsAllowed) {
            adjustment += when (candidate.kind) {
                ResumeKind.Resume -> resumeBoost(candidate.progressFraction)
                ResumeKind.NextUp ->
                    if (age <= NEXT_UP_RECENT_WINDOW_MS) NEXT_UP_BOOST_MS else 0L
                ResumeKind.NewEpisode -> {
                    val released = candidate.releasedAtMs
                    if (released != null && released <= nowMs && nowMs - released <= NEW_EPISODE_WINDOW_MS) {
                        NEW_EPISODE_BOOST_MS
                    } else {
                        0L
                    }
                }
            }
            if (candidate.playedOnThisDevice) adjustment += THIS_DEVICE_BOOST_MS
        }

        if (candidate.kind == ResumeKind.Resume) {
            val fraction = candidate.progressFraction
            if (fraction != null) {
                if (fraction > ALMOST_DONE_FRACTION) {
                    adjustment -= ALMOST_DONE_PENALTY_MS
                } else if (fraction < BARELY_STARTED_FRACTION && age > BARELY_STARTED_AGE_MS) {
                    adjustment -= BARELY_STARTED_PENALTY_MS
                }
            }
        }
        return base + adjustment
    }

    private fun resumeBoost(fraction: Float?): Long = when {
        fraction == null -> 0L
        fraction >= BARELY_STARTED_FRACTION && fraction <= ALMOST_DONE_FRACTION -> RESUME_BOOST_MS
        else -> 0L
    }

    private fun ResumeCandidate.isUnairedAt(nowMs: Long): Boolean =
        isUnaired || (kind == ResumeKind.NextUp && releasedAtMs?.let { it > nowMs } == true)
}
