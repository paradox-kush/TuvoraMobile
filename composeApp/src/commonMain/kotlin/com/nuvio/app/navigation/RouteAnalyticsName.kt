package com.nuvio.app.navigation

/**
 * The screen name a crash report should carry for [route].
 *
 * Replaces `route::class.simpleName`, which R8 rewrites in release builds. Measured in PostHog on
 * 2026-08-16: NuvioTV reported readable routes (`home`, `player`, `xtream_hub`, `detail`) while
 * mobile reported the two string literals it happened to have (`tabs_home`, `tabs_iptv`) and then
 * `p21`, `ov4`, `or4`, `lx4`, `kx4`, `px4`, `uq8`, `r96` for everything else — so every player and
 * detail crash was unattributable.
 *
 * Two deliberate properties:
 *  - **Exhaustive `when`, not a map or a default.** A new [AppRoute] fails to compile until it is
 *    named here. A test could only catch that after someone ran it; the compiler catches it while
 *    the route is being written.
 *  - **String literals.** Minification cannot touch them, and they hold still across releases, so
 *    a route's crash history stays queryable under one name.
 *
 * Names reuse NuvioTV's existing vocabulary (`detail`, `player`, `stream`) so one query can compare
 * the two apps. Arguments are never included — they are user data, and a breadcrumb is a screen
 * name. (`safeRouteName` on the Android side strips them again as defence in depth.)
 */
fun analyticsNameOf(route: AppRoute): String = when (route) {
    TabsRoute -> "tabs"
    is DetailRoute -> "detail"
    is PersonDetailRoute -> "person_detail"
    is EntityBrowseRoute -> "entity_browse"
    is DownloadShowRoute -> "download_show"
    is CollectionEditorRoute -> "collection_editor"
    is CollectionEditorPageRoute -> "collection_editor_page"
    is FolderDetailRoute -> "folder_detail"
    is StreamRoute -> "stream"
    is CatalogRoute -> "catalog"
    is PartyRoute -> "watch_party"
    is PlayerRoute -> "player"
    is LiveTvRoute -> "live_tv"
    // Settings leaves keep a shared prefix so one query covers the whole section.
    is SettingsPageRoute -> "settings_page"
    is HomescreenSettingsRoute -> "settings_homescreen"
    is MetaScreenSettingsRoute -> "settings_meta_screen"
    is ContinueWatchingSettingsRoute -> "settings_continue_watching"
    is DownloadsRoute -> "downloads"
    is AddonsSettingsRoute -> "settings_addons"
    is PluginsSettingsRoute -> "settings_plugins"
    is AccountSettingsRoute -> "settings_account"
    is SupportersContributorsSettingsRoute -> "settings_supporters"
    is LicensesAttributionsSettingsRoute -> "settings_licenses"
    is CollectionsRoute -> "collections"
}
