package com.nuvio.app.features.iptv

import android.content.Context
import android.content.SharedPreferences

/**
 * Own preferences file. It must NOT be listed in PlatformLocalAccountDataCleaner: a guest who signs in is
 * wiped, and the claim is what carries their redeemed playlists into the account.
 */
internal actual object PendingDeviceClaimStorage {
    private const val PREFERENCES_NAME = "tuvora_device_setup_claims"
    private const val KEY = "pending_claims"

    private var preferences: SharedPreferences? = null

    /** Called once at app startup (AndroidFeatureWiring), like XtreamAccountStorage.initialize. */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString(KEY, null)

    actual fun save(json: String?) {
        val edit = preferences?.edit() ?: error("pending claim storage is not initialised")
        if (json == null) edit.remove(KEY) else edit.putString(KEY, json)
        // commit, not apply: the process may be killed right after the redeem and this is the only record.
        check(edit.commit()) { "pending claim storage write failed" }
    }
}
