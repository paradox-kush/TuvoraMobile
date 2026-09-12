package com.nuvio.app.features.iptv

import android.content.Context
import android.content.SharedPreferences

internal actual object XtreamAccountStorage {
    private const val preferencesName = "nuvio_iptv"
    private const val accountsKey = "xtream_accounts"

    private var preferences: SharedPreferences? = null

    /** Called once at app startup (MainActivity), like AddonStorage.initialize. */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadAccountsJson(profileId: Int): String? =
        preferences?.getString("${accountsKey}_$profileId", null)

    actual fun saveAccountsJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("${accountsKey}_$profileId", json)?.apply()
    }

    actual fun loadRecentsJson(profileId: Int): String? =
        preferences?.getString("xtream_live_recents_$profileId", null)

    actual fun saveRecentsJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("xtream_live_recents_$profileId", json)?.apply()
    }

    actual fun loadRadarJson(profileId: Int): String? =
        preferences?.getString("radar_state_$profileId", null)

    actual fun saveRadarJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("radar_state_$profileId", json)?.apply()
    }

    actual fun loadRadarFixturesJson(profileId: Int): String? =
        preferences?.getString("radar_fixtures_$profileId", null)

    actual fun saveRadarFixturesJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("radar_fixtures_$profileId", json)?.apply()
    }

    actual fun loadRadarCatalogJson(profileId: Int): String? =
        preferences?.getString("radar_catalog_$profileId", null)
    actual fun saveRadarCatalogJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("radar_catalog_$profileId", json)?.apply()
    }

    actual fun loadRefreshStateJson(profileId: Int): String? =
        preferences?.getString("xtream_refresh_state_$profileId", null)

    actual fun saveRefreshStateJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("xtream_refresh_state_$profileId", json)?.apply()
    }

    actual fun loadHubSelectionJson(profileId: Int): String? =
        preferences?.getString("xtream_hub_selection_$profileId", null)

    actual fun saveHubSelectionJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("xtream_hub_selection_$profileId", json)?.apply()
    }

    actual fun loadPlaylistSyncStateJson(profileId: Int): String? =
        preferences?.getString("xtream_sync_state_$profileId", null)

    actual fun savePlaylistSyncStateJson(profileId: Int, json: String) {
        preferences?.edit()?.putString("xtream_sync_state_$profileId", json)?.apply()
    }
}
