package com.nuvio.app.features.iptv

import platform.Foundation.NSUserDefaults

/**
 * One key that PlatformLocalAccountDataCleaner does not match (it removes fixed keys and anything ending in
 * `_<profileId>`): a guest who signs in is wiped, and the claim must survive that. Also serves tvOS.
 */
internal actual object PendingDeviceClaimStorage {
    private const val KEY = "device_setup_pending_claims"

    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY)

    actual fun save(json: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (json == null) defaults.removeObjectForKey(KEY) else defaults.setObject(json, forKey = KEY)
        defaults.synchronize()
    }
}
