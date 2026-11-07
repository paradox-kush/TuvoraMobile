package com.nuvio.app.features.sportsdetail.internal

import platform.Foundation.NSUserDefaults

/** iPhone/iPad, and Apple TV through :tvosCore. */
internal actual object SportsDetailStorage {
    private const val PREFIX = "tuvora_sportsdetail."

    actual fun loadString(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(PREFIX + key)

    actual fun saveString(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = PREFIX + key)
    }
}
