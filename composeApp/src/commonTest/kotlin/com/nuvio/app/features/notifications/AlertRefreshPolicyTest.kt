package com.nuvio.app.features.notifications

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertRefreshPolicyTest {
    private val hour = 60L * 60 * 1000
    private val t0 = 1_000_000_000L
    private val ab = setOf("series:a", "series:b")

    private fun decide(
        tracked: Set<String> = ab,
        fetched: Set<String> = ab,
        lastAt: Long? = t0,
        now: Long = t0 + 60_000L,
        reason: AlertRefreshReason = AlertRefreshReason.ProgressTick,
    ) = AlertRefreshPolicy.decide(tracked, fetched, lastAt, now, reason)

    // ---- the policy ----

    @Test
    fun `a progress tick with an unchanged set makes no request`() {
        assertEquals(AlertRefreshDecision.ReplanFromCache, decide(reason = AlertRefreshReason.ProgressTick))
    }

    @Test
    fun `a progress tick every minute for a long session never asks for anything`() {
        for (minute in 1..300) {
            val now = t0 + minute * 60_000L
            assertEquals(
                AlertRefreshDecision.ReplanFromCache,
                decide(now = now, reason = AlertRefreshReason.ProgressTick),
                "minute $minute",
            )
        }
    }

    @Test
    fun `one show switched on fetches only that show`() {
        assertEquals(
            AlertRefreshDecision.FetchOnly(setOf("series:c")),
            decide(tracked = ab + "series:c", reason = AlertRefreshReason.ShowsChanged),
        )
    }

    @Test
    fun `a newly added show is fetched even on a progress tick`() {
        assertEquals(
            AlertRefreshDecision.FetchOnly(setOf("series:c")),
            decide(tracked = ab + "series:c", reason = AlertRefreshReason.ProgressTick),
        )
    }

    @Test
    fun `six hours and a minute later everything is fetched again`() {
        val now = t0 + 6 * hour + 60_000L
        assertEquals(AlertRefreshDecision.FetchAll, decide(now = now, reason = AlertRefreshReason.AppOpened))
        assertEquals(AlertRefreshDecision.FetchAll, decide(now = now, reason = AlertRefreshReason.SettingsChange))
    }

    @Test
    fun `just under six hours still plans from the cache`() {
        val now = t0 + 6 * hour - 1
        assertEquals(AlertRefreshDecision.ReplanFromCache, decide(now = now, reason = AlertRefreshReason.AppOpened))
    }

    @Test
    fun `a progress tick never triggers the six hour refetch`() {
        val now = t0 + 7 * hour
        assertEquals(AlertRefreshDecision.ReplanFromCache, decide(now = now, reason = AlertRefreshReason.ProgressTick))
    }

    @Test
    fun `a settings change such as quiet hours makes no request`() {
        assertEquals(AlertRefreshDecision.ReplanFromCache, decide(reason = AlertRefreshReason.SettingsChange))
    }

    @Test
    fun `an app open within six hours plans from the cache`() {
        assertEquals(
            AlertRefreshDecision.ReplanFromCache,
            decide(now = t0 + 5 * hour, reason = AlertRefreshReason.AppOpened),
        )
    }

    @Test
    fun `nothing fetched yet fetches everything`() {
        assertEquals(
            AlertRefreshDecision.FetchAll,
            decide(fetched = emptySet(), lastAt = null, reason = AlertRefreshReason.AppOpened),
        )
    }

    @Test
    fun `no show with alerts on needs nothing`() {
        assertEquals(
            AlertRefreshDecision.ReplanFromCache,
            decide(tracked = emptySet(), fetched = emptySet(), lastAt = null),
        )
    }

    @Test
    fun `a show removed from the set needs no request`() {
        assertEquals(AlertRefreshDecision.ReplanFromCache, decide(tracked = setOf("series:a")))
    }

    // ---- the candidate cache, with a counting fetch port ----

    private fun show(id: String) = TrackedFollowedShow(contentId = id, contentType = "series", followedOnIsoDate = "2026-11-01")

    private fun candidateFor(id: String, episode: String = "$id-e1") = AlertCandidate(
        showKey = "series:$id",
        showTitle = id.uppercase(),
        episodeKey = episode,
        releaseDate = LocalDate(2026, 11, 25),
    )

    private class CountingFetch(var failing: Set<String> = emptySet(), var episodes: Map<String, String> = emptyMap()) {
        val requested = ArrayList<String>()
        val port: suspend (TrackedFollowedShow) -> List<AlertCandidate>? = { show ->
            requested += show.contentId
            if (show.contentId in failing) {
                null
            } else {
                listOf(
                    AlertCandidate(
                        showKey = "series:${show.contentId}",
                        showTitle = show.contentId.uppercase(),
                        episodeKey = episodes[show.contentId] ?: "${show.contentId}-e1",
                        releaseDate = LocalDate(2026, 11, 25),
                    ),
                )
            }
        }
    }

    @Test
    fun `a progress emission with an unchanged tracked set triggers zero metadata fetches`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        val shows = listOf(show("a"), show("b"), show("c"))

        val first = cache.resolve(shows, t0, AlertRefreshReason.AppOpened)
        assertEquals(3, first.fetches, "the first plan asks about every show once")
        assertEquals(3, fetch.requested.size)

        // An hour of playback: the player saves progress every minute and the repository replans on each save.
        for (minute in 1..60) {
            val tick = cache.resolve(shows, t0 + minute * 60_000L, AlertRefreshReason.ProgressTick)
            assertEquals(0, tick.fetches, "tick $minute")
            assertEquals(3, tick.candidates.size, "tick $minute still plans from the cache")
        }
        assertEquals(3, fetch.requested.size, "sixty progress ticks made no request")
    }

    @Test
    fun `switching one show on fetches only that show`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        cache.resolve(listOf(show("a"), show("b")), t0, AlertRefreshReason.AppOpened)
        fetch.requested.clear()

        val resolved = cache.resolve(listOf(show("a"), show("b"), show("c")), t0 + 1_000L, AlertRefreshReason.ShowsChanged)

        assertEquals(listOf("c"), fetch.requested)
        assertEquals(1, resolved.fetches)
        assertEquals(3, resolved.candidates.size)
    }

    @Test
    fun `a settings change replans from the cache without a request`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        val shows = listOf(show("a"), show("b"))
        cache.resolve(shows, t0, AlertRefreshReason.AppOpened)
        fetch.requested.clear()

        val resolved = cache.resolve(shows, t0 + 2 * hour, AlertRefreshReason.SettingsChange)

        assertTrue(fetch.requested.isEmpty(), "quiet hours changed: no request")
        assertEquals(2, resolved.candidates.size)
    }

    @Test
    fun `six hours later an app open fetches every show again`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        val shows = listOf(show("a"), show("b"))
        cache.resolve(shows, t0, AlertRefreshReason.AppOpened)
        fetch.requested.clear()

        cache.resolve(shows, t0 + 6 * hour + 60_000L, AlertRefreshReason.AppOpened)

        assertEquals(setOf("a", "b"), fetch.requested.toSet())
    }

    @Test
    fun `a failed fetch keeps the previous candidates and is not read as no episodes`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        val shows = listOf(show("a"), show("b"))
        cache.resolve(shows, t0, AlertRefreshReason.AppOpened)

        fetch.failing = setOf("a")
        val refreshed = cache.resolve(shows, t0 + 7 * hour, AlertRefreshReason.AppOpened)

        assertEquals(2, refreshed.candidates.size, "show a keeps its earlier episode")
        assertTrue(refreshed.candidates.any { it.showKey == "series:a" })
    }

    @Test
    fun `a show whose first fetch failed is not retried on every progress tick`() = runTest {
        val fetch = CountingFetch(failing = setOf("a"))
        val cache = AlertCandidateCache(fetch.port)
        val shows = listOf(show("a"), show("b"))
        cache.resolve(shows, t0, AlertRefreshReason.AppOpened)
        fetch.requested.clear()

        repeat(30) { cache.resolve(shows, t0 + (it + 1) * 60_000L, AlertRefreshReason.ProgressTick) }

        assertTrue(fetch.requested.isEmpty(), "offline: no retry storm")
    }

    @Test
    fun `a show that lost its alerts stops contributing candidates`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        cache.resolve(listOf(show("a"), show("b")), t0, AlertRefreshReason.AppOpened)

        val after = cache.resolve(listOf(show("a")), t0 + 60_000L, AlertRefreshReason.ShowsChanged)

        assertEquals(listOf("series:a"), after.candidates.map { it.showKey })
    }

    @Test
    fun `clearing the cache for a profile switch fetches everything again`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        val shows = listOf(show("a"))
        cache.resolve(shows, t0, AlertRefreshReason.AppOpened)
        cache.clear()
        fetch.requested.clear()

        cache.resolve(shows, t0 + 60_000L, AlertRefreshReason.ProgressTick)

        assertEquals(listOf("a"), fetch.requested)
    }

    @Test
    fun `wouldFetch agrees with resolve`() = runTest {
        val fetch = CountingFetch()
        val cache = AlertCandidateCache(fetch.port)
        val shows = listOf(show("a"))
        assertTrue(cache.wouldFetch(shows, t0, AlertRefreshReason.AppOpened))
        cache.resolve(shows, t0, AlertRefreshReason.AppOpened)
        assertEquals(false, cache.wouldFetch(shows, t0 + 60_000L, AlertRefreshReason.ProgressTick))
    }
}
