// Ported from the Arc fork's CalendarRemindersTest and CalendarHomeTest (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarRemindersTest {
    private val now = at("2026-10-05T23:00:00Z")

    private fun airedAgo(agoMs: Long, episode: Int = 1) = CalendarEntry(season = 1, episode = episode, airInstantMs = now - agoMs)

    private val noneWatched: (CalendarTitleRecord, CalendarEntry) -> Boolean = { _, _ -> false }

    private fun due(
        titles: List<CalendarTitleRecord>,
        bells: Set<String> = titles.map { it.key }.toSet(),
        seen: Set<String> = emptySet(),
        watched: (CalendarTitleRecord, CalendarEntry) -> Boolean = noneWatched,
        atMs: Long = now,
    ) = dueReminders(titles, bells, seen, watched, atMs)

    @Test fun anEpisodeIsDueOneHourAfterItAirs() {
        val title = showRecord("series:imdb:tt1", airedAgo(59 * MINUTE, 1), airedAgo(60 * MINUTE, 2))
        assertEquals(listOf(2), due(listOf(title)).items.map { it.entry.episode })
    }

    @Test fun aPlainDateIsDueAtOneInTheMorningUtcTheNextDay() {
        val title = showRecord(
            "series:imdb:tt1",
            CalendarEntry(season = 1, episode = 1, airDate = "2026-10-05", source = CalendarSource.TMDB),
            source = CalendarSource.TMDB,
        )
        assertEquals(at("2026-10-06T01:00:00Z"), calendarReminderDueAtMs(title.entries.single()))
        assertTrue(due(listOf(title)).items.isEmpty())
        assertEquals(1, due(listOf(title), atMs = at("2026-10-06T01:00:00Z")).count)
    }

    @Test fun onlyTheLastFourteenDays() {
        val title = showRecord(
            "series:imdb:tt1",
            airedAgo(14 * DAY + 2 * HOUR, 1),
            airedAgo(14 * DAY, 2),
            airedAgo(3 * DAY, 3),
        )
        assertEquals(listOf(3, 2), due(listOf(title)).items.map { it.entry.episode })
    }

    @Test fun onlyTitlesWithTheBellOnAlsoByAnotherId() {
        val belled = showRecord("series:imdb:tt1", airedAgo(2 * HOUR)).copy(ids = CalendarIds(imdb = "tt1", tmdb = 10))
        val quiet = showRecord("series:imdb:tt2", airedAgo(2 * HOUR))
        val result = due(listOf(belled, quiet), bells = setOf("series:tmdb:10"))
        assertEquals(listOf("series:imdb:tt1"), result.items.map { it.title.key })
    }

    @Test fun watchedAndSeenEpisodesAreLeftOut() {
        val title = showRecord("series:imdb:tt1", airedAgo(2 * HOUR, 1), airedAgo(3 * HOUR, 2), airedAgo(4 * HOUR, 3))
        val result = due(
            listOf(title),
            seen = setOf("series:imdb:tt1|S1E2"),
            watched = { _, entry -> entry.episode == 3 },
        )
        assertEquals(listOf("series:imdb:tt1|S1E1"), result.items.map { it.episodeKey })
    }

    @Test fun newestFirstWithACountForAndNMore() {
        val titles = (1..5).map { showRecord("series:imdb:tt$it", airedAgo((it + 1) * HOUR)) }
        val result = due(titles)
        assertEquals((1..5).map { "series:imdb:tt$it" }, result.items.map { it.title.key })
        assertEquals(5, result.count)
        assertEquals(2, result.moreThan(3))
        assertEquals(0, result.moreThan(9))
        val card = calendarReminderCard(result)!!
        assertEquals(3, card.items.size)
        assertEquals(2, card.more)
        assertEquals(5, card.episodeKeys.size)
        assertNull(calendarReminderCard(CalendarDueReminders()))
    }

    @Test fun cinemaDatesGiveNoReminderButDigitalReleasesDo() {
        val film = CalendarTitleRecord(
            key = "movie:imdb:tt9", type = CalendarTitleType.MOVIE, ids = CalendarIds(imdb = "tt9"),
            sources = mapOf(
                CalendarSource.TMDB to listOf(
                    CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-01", source = CalendarSource.TMDB),
                    CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-02", source = CalendarSource.TMDB),
                ),
            ),
        )
        assertEquals(listOf(CalendarEntryKind.MOVIE_DIGITAL), due(listOf(film)).items.map { it.entry.kind })
    }

    @Test fun everythingDueSinceTheLastCheckIsOneBatchAndIsAnnouncedOnce() {
        val batcher = CalendarReminderBatcher()
        val first = showRecord("series:imdb:tt1", airedAgo(2 * HOUR))
        val second = showRecord("series:imdb:tt2", airedAgo(3 * HOUR))
        assertEquals(2, batcher.nextBatch(due(listOf(first, second))).size)
        assertTrue(batcher.nextBatch(due(listOf(first, second))).isEmpty())

        val later = now + 5 * HOUR
        val third = showRecord("series:imdb:tt3", CalendarEntry(season = 1, episode = 1, airInstantMs = now + 2 * HOUR))
        val batch = batcher.nextBatch(due(listOf(first, second, third), atMs = later))
        assertEquals(listOf("series:imdb:tt3"), batch.map { it.title.key })
    }

    @Test fun seenRemindersAreForgottenAfterThirtyDays() {
        val seen = mapOf("old" to now - 31 * DAY, "recent" to now - 29 * DAY)
        assertEquals(setOf("recent", "new"), seen.withSeen(listOf("new"), now).keys)
        assertEquals(setOf("recent"), seen.prunedSeen(now).keys)
    }

    @Test fun theReminderCheckWaitsForTheNextDueReleaseWithinAMinuteAndSixHours() {
        val soon = showRecord("series:imdb:tt1", CalendarEntry(season = 1, episode = 2, airInstantMs = now + 10 * MINUTE))
        val next = calendarNextReminderAtMs(listOf(soon), setOf(soon.key), emptySet(), now)
        assertEquals(now + 70 * MINUTE, next)
        assertEquals(70 * MINUTE, calendarReminderCheckDelayMs(next, now))
        assertEquals(MINUTE, calendarReminderCheckDelayMs(now + 1_000L, now))
        assertEquals(6 * HOUR, calendarReminderCheckDelayMs(now + 3 * DAY, now))
        assertEquals(6 * HOUR, calendarReminderCheckDelayMs(null, now))
        assertNull(calendarNextReminderAtMs(listOf(soon), emptySet(), emptySet(), now))
    }

    @Test fun theWatchedIndexMatchesByAnyIdOfTheTitle() {
        val index = CalendarWatchedIndex(CalendarWatchedIndex.episodeKey("tmdb:5", 1, 2) + CalendarWatchedIndex.movieKey("tt9"))
        val show = CalendarTitleRecord("series:imdb:tt5", CalendarTitleType.SERIES, CalendarIds(imdb = "tt5", tmdb = 5))
        assertTrue(index.isWatched(show, CalendarEntry(season = 1, episode = 2)))
        assertTrue(!index.isWatched(show, CalendarEntry(season = 1, episode = 3)))
        val film = CalendarTitleRecord("movie:imdb:tt9", CalendarTitleType.MOVIE, CalendarIds(imdb = "tt9"))
        assertTrue(index.isWatched(film, CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL)))
    }
}
