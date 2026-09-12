package com.nuvio.app.features.iptv

/**
 * Local, profile-scoped persistence of the Xtream accounts list as a JSON string.
 * Mirrors features/addons AddonStorage (SharedPreferences on Android, NSUserDefaults on iOS).
 *
 * ponytail: local only; Supabase cloud sync (like addons have) is the upgrade path.
 */
internal expect object XtreamAccountStorage {
    fun loadAccountsJson(profileId: Int): String?
    fun saveAccountsJson(profileId: Int, json: String)
    /** Recently-watched live channels (JSON), profile-scoped — for the Live TV Continue-Watching row. */
    fun loadRecentsJson(profileId: Int): String?
    fun saveRecentsJson(profileId: Int, json: String)
    /** Sports Centre follows+prefs (JSON RadarLocalState), profile-scoped — rides this prefs bag to avoid new init plumbing. */
    fun loadRadarJson(profileId: Int): String?
    fun saveRadarJson(profileId: Int, json: String)
    /** Last-fetched fixtures (JSON RadarFixturesResponse) so the Sports tab renders offline. */
    fun loadRadarFixturesJson(profileId: Int): String?
    fun saveRadarFixturesJson(profileId: Int, json: String)
    /** Last good published league catalog (JSON RadarCachedCatalog) — cache, not user data. */
    fun loadRadarCatalogJson(profileId: Int): String?
    fun saveRadarCatalogJson(profileId: Int, json: String)
    /** Per-playlist last auto-refresh timestamps (JSON map id->epochMs), profile-scoped — P3 auto-refresh. */
    fun loadRefreshStateJson(profileId: Int): String?
    fun saveRefreshStateJson(profileId: Int, json: String)
    /** Hub's last provider + section tab (JSON XtreamHubSelection), profile-scoped — Fix 1 sticky
     *  selection. Device-local UI state: deliberately NOT part of the account sync payload. */
    fun loadHubSelectionJson(profileId: Int): String?
    fun saveHubSelectionJson(profileId: Int, json: String)
    /** B24 v2 sync state (JSON PlaylistSyncState: revision + mutationId + pending ops), profile-scoped.
     *  A SEPARATE key from the accounts blob so it survives an accounts-store corruption reset — the
     *  pending "add C" and the mutation id outlive a reset of the accounts store. */
    fun loadPlaylistSyncStateJson(profileId: Int): String?
    fun savePlaylistSyncStateJson(profileId: Int, json: String)
}
