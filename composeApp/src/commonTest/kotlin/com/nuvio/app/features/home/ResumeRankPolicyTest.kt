package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeRankPolicyTest {
    private val now = 100L * ResumeRankPolicy.DAY_MS
    private val hour = ResumeRankPolicy.HOUR_MS
    private val day = ResumeRankPolicy.DAY_MS

    private fun resume(key: String, agoMs: Long, fraction: Float, device: Boolean = false) = ResumeCandidate(
        key = key, kind = ResumeKind.Resume, lastEngagedAtMs = now - agoMs,
        progressFraction = fraction, playedOnThisDevice = device,
    )

    private fun nextUp(key: String, agoMs: Long, releasedAgoMs: Long? = null) = ResumeCandidate(
        key = key, kind = ResumeKind.NextUp, lastEngagedAtMs = now - agoMs,
        releasedAtMs = releasedAgoMs?.let { now - it },
    )

    private fun rank(vararg c: ResumeCandidate) = ResumeRankPolicy.rank(c.toList(), now)

    @Test
    fun `half watched film from yesterday evening outranks a next up engaged two hours ago`() {
        // Yesterday evening = 12h ago: +24h resume boost beats the +12h next-up boost over a 10h recency gap.
        // (At exactly 24h ago the SPEC table scores the next-up higher; see lane report.)
        assertEquals(
            listOf("film", "next"),
            rank(nextUp("next", 2 * hour), resume("film", 12 * hour, 0.5f)),
        )
    }

    @Test
    fun `two resumes in the same band order by recency`() {
        assertEquals(listOf("newer", "older"), rank(resume("older", 3 * day, 0.4f), resume("newer", day, 0.6f)))
    }

    @Test
    fun `a 96 percent item sinks below a 40 percent item of the same age`() {
        assertEquals(listOf("mid", "done"), rank(resume("done", day, 0.96f), resume("mid", day, 0.40f)))
    }

    @Test
    fun `a 2 percent item from five days ago sinks below a week old 50 percent item`() {
        // The SPEC example said three days; by its own table a 3-day barely-started item (-5d) still beats a
        // 7-day half-watched one (-6d), so the pinned case is five days (-7d vs -6d).
        assertEquals(
            listOf("half", "barely"),
            rank(resume("barely", 5 * day, 0.02f), resume("half", 7 * day, 0.5f)),
        )
    }

    @Test
    fun `a barely started item from the last day is not penalised`() {
        val c = resume("fresh", 2 * hour, 0.02f)
        assertEquals(c.lastEngagedAtMs, ResumeRankPolicy.score(c, now))
    }

    @Test
    fun `a new episode aired today for a show last watched 20 days ago outranks a 10 day old resume`() {
        val fresh = ResumeCandidate(
            key = "fresh", kind = ResumeKind.NewEpisode, lastEngagedAtMs = now - 20 * day,
            releasedAtMs = now - 2 * hour,
        )
        assertEquals(listOf("fresh", "resume"), rank(resume("resume", 10 * day, 0.5f), fresh))
    }

    @Test
    fun `a 31 day old item gets no boost`() {
        val old = resume("old", 31 * day, 0.5f, device = true)
        assertEquals(old.lastEngagedAtMs, ResumeRankPolicy.score(old, now))
        val oldNext = nextUp("oldNext", 31 * day)
        assertEquals(oldNext.lastEngagedAtMs, ResumeRankPolicy.score(oldNext, now))
        val boosted = resume("edge", 30 * day, 0.5f)
        assertEquals(boosted.lastEngagedAtMs + ResumeRankPolicy.RESUME_BOOST_MS, ResumeRankPolicy.score(boosted, now))
    }

    @Test
    fun `this device boost breaks a near tie and never overturns a 24 hour gap`() {
        assertEquals(
            listOf("device", "other"),
            rank(resume("other", 2 * day, 0.5f), resume("device", 2 * day + hour, 0.5f, device = true)),
            "1h older but played here wins",
        )
        assertEquals(
            listOf("other", "device"),
            rank(resume("other", 2 * day, 0.5f), resume("device", 3 * day, 0.5f, device = true)),
            "24h gap is not overturned by the 6h boost",
        )
    }

    @Test
    fun `unaired next ups go last with nearest date first and unknown dates after known`() {
        val unknown = ResumeCandidate("unknown", ResumeKind.NextUp, now - hour, isUnaired = true)
        val far = ResumeCandidate("far", ResumeKind.NextUp, now - hour, releasedAtMs = now + 10 * day)
        val near = ResumeCandidate("near", ResumeKind.NextUp, now - hour, releasedAtMs = now + 2 * day)
        assertEquals(
            listOf("resume", "next", "near", "far", "unknown"),
            ResumeRankPolicy.rank(
                listOf(unknown, far, near, nextUp("next", day), resume("resume", day, 0.5f)),
                now,
            ),
        )
    }

    @Test
    fun `same input twice gives the same order and equal scores order by key`() {
        val input = listOf(resume("b", day, 0.5f), resume("a", day, 0.5f), resume("c", day, 0.5f))
        val first = ResumeRankPolicy.rank(input, now)
        assertEquals(listOf("a", "b", "c"), first)
        assertEquals(first, ResumeRankPolicy.rank(input, now))
        assertEquals(first, ResumeRankPolicy.rank(input.reversed(), now))
    }

    @Test
    fun `empty in gives empty out`() {
        assertEquals(emptyList(), ResumeRankPolicy.rank(emptyList(), now))
    }
}
