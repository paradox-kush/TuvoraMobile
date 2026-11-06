package com.nuvio.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Crash reports must name the screen in a form a person can read.
 *
 * `App.kt` used `currentRoute::class.simpleName`, which R8 rewrites in release builds. Measured in
 * PostHog on 2026-08-16: TV reported `home` / `player` / `xtream_hub` / `detail`, while mobile
 * reported `tabs_home` and `tabs_iptv` (string literals, so they survived) and then `p21`, `ov4`,
 * `or4`, `lx4`, `kx4`, `px4`, `uq8`, `r96`… for everything else. Every player and detail crash — the
 * ones that matter — landed in an unattributable bucket. The Downloads autofill crash was only
 * identifiable because its stack happened to name the file.
 *
 * Names are string literals here, so minification cannot touch them, and they deliberately reuse
 * NuvioTV's vocabulary so a single query can compare the two apps.
 */
class RouteAnalyticsNameTest {

    @Test
    fun `player and detail routes are readable`() {
        assertEquals("player", analyticsNameOf(PlayerRoute(launchId = 1L)))
        assertEquals("detail", analyticsNameOf(DetailRoute(type = "movie", id = "tt1")))
        assertEquals("live_tv", analyticsNameOf(LiveTvRoute(launchId = 1L)))
        assertEquals("stream", analyticsNameOf(StreamRoute(launchId = 1L)))
        assertEquals("catalog", analyticsNameOf(CatalogRoute(launchId = 1L)))
        assertEquals("recordings", analyticsNameOf(RecordingsRoute()))
    }

    /** Matches NuvioTV's existing `detail` / `player` / `stream` vocabulary. */
    @Test
    fun `names share NuvioTV's vocabulary`() {
        assertTrue(analyticsNameOf(PlayerRoute(1L)) == "player", "TV reports 'player'")
        assertTrue(analyticsNameOf(DetailRoute("movie", "tt1")) == "detail", "TV reports 'detail'")
    }

    /**
     * Route arguments are user data — a person's name, a collection id, a stream launch id. The
     * breadcrumb is a SCREEN name and must never carry them, which is also why `safeRouteName`
     * exists on the Android side.
     */
    @Test
    fun `names never carry route arguments`() {
        val personal = analyticsNameOf(
            PersonDetailRoute(personId = 42, personName = "Jane Doe", personPhoto = "https://x/y.jpg")
        )
        assertEquals("person_detail", personal)
        assertTrue("Jane" !in personal && "42" !in personal, "no arguments in '$personal'")

        val folder = analyticsNameOf(
            FolderDetailRoute(collectionId = "secret-collection", folderId = "secret-folder")
        )
        assertTrue("secret" !in folder, "no ids in '$folder'")
    }

    /** Every route must produce a non-blank, lowercase, argument-free token. */
    @Test
    fun `every name is a stable lowercase token`() {
        val everyRoute: List<AppRoute> = listOf(
            TabsRoute,
            DetailRoute("movie", "tt1"),
            PersonDetailRoute(1, "n"),
            EntityBrowseRoute("k", 1, "n"),
            SettingsPageRoute("page", "t"),
            HomescreenSettingsRoute(),
            MetaScreenSettingsRoute(),
            ContinueWatchingSettingsRoute(),
            DownloadsRoute(),
            RecordingsRoute(),
            DownloadShowRoute("s", "t"),
            AddonsSettingsRoute(),
            PluginsSettingsRoute(),
            AccountSettingsRoute(),
            SupportersContributorsSettingsRoute(),
            LicensesAttributionsSettingsRoute(),
            CollectionsRoute(),
            CollectionEditorRoute(),
            CollectionEditorPageRoute(null, "page", "t"),
            FolderDetailRoute("c", "f"),
            StreamRoute(1L),
            CatalogRoute(1L),
            PlayerRoute(1L),
            LiveTvRoute(1L),
        )
        for (route in everyRoute) {
            val name = analyticsNameOf(route)
            assertTrue(name.isNotBlank(), "blank name for $route")
            assertEquals(name.lowercase(), name, "'$name' must be lowercase")
            assertTrue(
                name.all { it in 'a'..'z' || it == '_' },
                "'$name' must be letters and underscores only",
            )
        }
    }

    /** Distinct screens must not collapse into one bucket — that is the bug we are fixing. */
    @Test
    fun `distinct screens get distinct names`() {
        val names = listOf(
            analyticsNameOf(PlayerRoute(1L)),
            analyticsNameOf(LiveTvRoute(1L)),
            analyticsNameOf(DetailRoute("movie", "tt1")),
            analyticsNameOf(StreamRoute(1L)),
            analyticsNameOf(CatalogRoute(1L)),
            analyticsNameOf(DownloadShowRoute("s", "t")),
        )
        assertEquals(names.size, names.toSet().size, "collided: $names")
    }
}
