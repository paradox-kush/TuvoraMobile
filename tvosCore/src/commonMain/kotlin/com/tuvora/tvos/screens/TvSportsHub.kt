package com.tuvora.tvos.screens

import com.nuvio.app.features.radar.MatchConfidence
import com.nuvio.app.features.radar.RadarCategory
import com.nuvio.app.features.radar.RadarChannelMatcher
import com.nuvio.app.features.radar.RadarFeaturedEvent
import com.nuvio.app.features.radar.RadarFixture
import com.nuvio.app.features.radar.RadarLeague
import com.nuvio.app.features.radar.RadarLiveScore
import com.nuvio.app.features.radar.RadarUiState

/** One rail of match cards: a followed team or league (NuvioTV SportsHubScreen.kt:319-363). */
data class TvSportsRow(
    val key: String,
    val title: String,
    val badge: String?,
    val fixtures: List<RadarFixture>,
    /** Set for a league rail: "See all" opens the league. */
    val league: RadarLeague?,
)

/** Everything the Sports hub draws, top to bottom. */
data class TvSportsHub(
    val featured: List<TvSportsFeatured>,
    val upcoming: List<RadarFixture>,
    /** Fixtures are still loading for a profile that follows something: show the skeleton row. */
    val upcomingLoading: Boolean,
    val teamRows: List<TvSportsRow>,
    val leagueRows: List<TvSportsRow>,
    val categories: List<TvSportsCategory>,
    /** No follows yet: the browse rail is titled "Follow your sports" and explains itself. */
    val followPrompt: Boolean,
)

data class TvSportsFeatured(val event: RadarFeaturedEvent, val matchCount: Int)

data class TvSportsCategory(val category: RadarCategory, val followedCount: Int) {
    /** CategoryTile subtitle: "3 followed" / "12 to track". */
    val subtitle: String get() = if (followedCount > 0) "$followedCount followed" else "${category.leagues.size} to track"
}

/** NuvioTV LeagueFixturesPage: everything in one league — live, upcoming, recent results. */
data class TvLeaguePage(
    val league: RadarLeague,
    val upcoming: List<RadarFixture>,
    val recent: List<RadarFixture>,
    /** The league's fixtures have arrived (followed or fetched on demand). */
    val loaded: Boolean,
    val followed: Boolean,
    /** "Soccer · 12 upcoming" / "Soccer · Loading…". */
    val subtitle: String,
    /** Loaded and nothing to show: "No scheduled matches right now." */
    val empty: Boolean,
)

/** One tier of channels in the match sheet; [label] is null when it is the only tier. */
data class TvMatchGroup(val label: String?, val matches: List<RadarChannelMatcher.ChannelMatch>)

/** The status pill at a match card's top-right (SportsHubScreen.kt:1150-1206). */
enum class TvMatchStatusTone { None, Live, Muted, Accent }

/** A kick-off's calendar day relative to today, in the viewer's time zone — localized by the UI. */
enum class TvMatchDay { Today, Tomorrow, Other }

/**
 * [text] is the pill's English copy for Live / Muted ("LIVE 67'", "POSTPONED", "FT"); an Accent
 * pill carries only [day] so the UI renders "TODAY" / "TOMORROW" in the viewer's language.
 */
data class TvMatchStatus(val tone: TvMatchStatusTone, val text: String, val day: TvMatchDay = TvMatchDay.Other)

/**
 * The Sports hub's composition, straight from NuvioTV's SportsHubScreen: which rails appear, in what
 * order, and what each card's status pill says. Pure (state + clock in, layout out), so it is tested
 * without the repository, the network or SwiftUI.
 */
object TvSportsHubPolicy {
    const val TEAM_ROW_CAP = 12
    const val LEAGUE_ROW_CAP = 12

    fun build(state: RadarUiState, nowMs: Long): TvSportsHub {
        val featuredEvents = state.activeFeatured(nowMs)
        // Followed clubs feed the same Live & Upcoming row as followed leagues (SportsHubScreen.kt:143).
        val upcoming = (
            state.upcoming(state.followedLeagueIds + featuredEvents.map { it.leagueId }, nowMs) +
                state.upcomingForTeams(state.followedTeamIds, nowMs)
            )
            .distinctBy { fixtureKey(it) }
            .sortedBy { it.startEpochMs }
        val teamRows = state.followedTeams.mapNotNull { team ->
            val fixtures = state.upcomingForTeams(listOf(team.id), nowMs, cap = TEAM_ROW_CAP)
            if (fixtures.isEmpty()) null
            else TvSportsRow(key = "team-${team.id}", title = team.name, badge = team.badge, fixtures = fixtures, league = null)
        }
        val leagueRows = state.follows.mapNotNull { follow ->
            val league = state.leagueById(follow.leagueId) ?: return@mapNotNull null
            val fixtures = state.upcoming(listOf(league.id), nowMs, cap = LEAGUE_ROW_CAP)
            if (fixtures.isEmpty()) null
            else TvSportsRow(key = "league-${league.id}", title = league.name, badge = league.badge, fixtures = fixtures, league = league)
        }
        return TvSportsHub(
            featured = featuredEvents.map { TvSportsFeatured(it, state.upcoming(listOf(it.leagueId), nowMs, cap = 99).size) },
            upcoming = upcoming,
            upcomingLoading = upcoming.isEmpty() && state.loadingFixtures && (state.follows.isNotEmpty() || featuredEvents.isNotEmpty()),
            teamRows = teamRows,
            leagueRows = leagueRows,
            categories = state.catalog.categories.map { category ->
                TvSportsCategory(category, category.leagues.count { it.id in state.followedLeagueIds })
            },
            followPrompt = state.follows.isEmpty(),
        )
    }

    /**
     * The match sheet's three evidence tiers (SportsHubScreen.kt:750-770): the channel's own guide
     * names the match > a broadcaster listing or the channel name does > the channel only carries the
     * competition. Deduplicated by channel first; tiers are always labelled, including a lone weak tier.
     */
    fun groupMatches(matches: List<RadarChannelMatcher.ChannelMatch>, league: String?): List<TvMatchGroup> {
        val deduped = matches.distinctBy { it.channel.contentId }
        val showing = deduped.filter { it.confidence == MatchConfidence.CONFIRMED && it.via == RadarChannelMatcher.MatchVia.EPG }
        val broadcasting = deduped.filter { it.confidence == MatchConfidence.CONFIRMED && it.via != RadarChannelMatcher.MatchVia.EPG }
        val possible = deduped.filter { it.confidence == MatchConfidence.POSSIBLE }
        val carries = deduped.filter { it.confidence == MatchConfidence.LEAGUE }
        val carriesLabel = (league?.takeIf { it.isNotBlank() }?.let { "CARRIES $it" } ?: "CARRIES THIS COMPETITION").uppercase()
        return listOf(
            TvMatchGroup("SCHEDULED FOR THIS EVENT", showing),
            TvMatchGroup("LISTED BROADCASTER", broadcasting),
            TvMatchGroup("POSSIBLE EVENT FEEDS", possible),
            TvMatchGroup(carriesLabel, carries),
        ).filter { it.matches.isNotEmpty() }
    }

    const val LEAGUE_PAGE_UPCOMING_CAP = 40

    fun leaguePage(state: RadarUiState, league: RadarLeague, nowMs: Long): TvLeaguePage {
        val upcoming = state.upcoming(listOf(league.id), nowMs, cap = LEAGUE_PAGE_UPCOMING_CAP)
        val recent = state.recent(league.id, nowMs)
        val loaded = state.fixturesByLeague.containsKey(league.id)
        return TvLeaguePage(
            league = league,
            upcoming = upcoming,
            recent = recent,
            loaded = loaded,
            followed = league.id in state.followedLeagueIds,
            subtitle = listOfNotNull(league.sport?.takeIf { it.isNotBlank() }, if (loaded) "${upcoming.size} upcoming" else "Loading…").joinToString(" · "),
            empty = loaded && upcoming.isEmpty() && recent.isEmpty(),
        )
    }

    fun fixtureKey(fixture: RadarFixture): String = fixture.id ?: "${fixture.leagueId}/${fixture.event}/${fixture.ts}"

    /**
     * Live (with the feed's minute, "LIVE 67'") > postponed > full time > Today/Tomorrow > nothing.
     * [day] is the kick-off's calendar day ([matchDay]); language-neutral, so no English label is
     * ever compared (the old version matched RadarTime's "Today"/"Tomorrow" strings).
     */
    fun status(fixture: RadarFixture, live: Boolean, liveScore: RadarLiveScore?, day: TvMatchDay): TvMatchStatus = when {
        live -> TvMatchStatus(TvMatchStatusTone.Live, liveText(liveScore?.progress))
        fixture.postponed == "yes" -> TvMatchStatus(TvMatchStatusTone.Muted, "POSTPONED")
        fixture.scoreLabel != null -> TvMatchStatus(TvMatchStatusTone.Muted, "FT")
        day != TvMatchDay.Other -> TvMatchStatus(TvMatchStatusTone.Accent, "", day)
        else -> TvMatchStatus(TvMatchStatusTone.None, "")
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whole calendar days from now's day to the kick-off's day, with the zone's UTC offset applied. */
    fun dayOffset(startMs: Long, nowMs: Long, utcOffsetMs: Long): Long =
        (startMs + utcOffsetMs).floorDiv(DAY_MS) - (nowMs + utcOffsetMs).floorDiv(DAY_MS)

    fun matchDay(startMs: Long?, nowMs: Long, utcOffsetMs: Long): TvMatchDay = when (startMs?.let { dayOffset(it, nowMs, utcOffsetMs) }) {
        0L -> TvMatchDay.Today
        1L -> TvMatchDay.Tomorrow
        else -> TvMatchDay.Other
    }

    fun liveText(progress: String?): String = if (progress.isNullOrBlank()) "LIVE" else "LIVE ${progress.trim()}"

    /** The score to show for one side: the livescore feed's, else the fixture's own result. */
    fun score(feed: String?, fixture: String?): String? = (feed ?: fixture)?.takeIf { it.isNotBlank() }

    /** True when both scores are numbers and this side is behind — the trailing score dims. */
    fun scoreTrails(own: String?, other: String?): Boolean {
        val a = own?.trim()?.toIntOrNull() ?: return false
        val b = other?.trim()?.toIntOrNull() ?: return false
        return a < b
    }

    /** Crest fallback: initials of the first two words, else the first two letters. */
    fun teamMonogram(name: String): String {
        val words = name.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.isNotEmpty() -> words[0].take(2).uppercase()
            else -> "?"
        }
    }
}

/**
 * The Sports screen's refresh cadence while it is visible (egress rule, CLAUDE.md): a cheap live tick
 * every 2 minutes ([com.nuvio.app.features.radar.RadarRepository.refreshLiveFixtures], which asks
 * RadarLiveRefreshPolicy for the live subset and makes no call on an idle slate), and the full
 * followed-set refresh only every 15 minutes, to discover newly scheduled fixtures.
 */
object TvSportsRefreshCadence {
    const val LIVE_INTERVAL_MS: Long = 2 * 60 * 1000L
    const val FULL_INTERVAL_MS: Long = 15 * 60 * 1000L

    data class Tick(val full: Boolean, val sinceFullMs: Long)

    /** Called after each [LIVE_INTERVAL_MS] wait with the time since the last full refresh. */
    fun next(sinceFullMs: Long): Tick {
        val elapsed = sinceFullMs + LIVE_INTERVAL_MS
        return if (elapsed >= FULL_INTERVAL_MS) Tick(full = true, sinceFullMs = 0) else Tick(full = false, sinceFullMs = elapsed)
    }
}
