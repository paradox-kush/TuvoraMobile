package com.nuvio.app.features.calendar.internal

import platform.Foundation.NSUserDefaults

/** iPhone/iPad, and Apple TV through :tvosCore (where the tiered defaults keep it in the cache tier). */
internal actual object CalendarStorage {
    private const val PREFIX = "tuvora_calendar."

    actual fun loadString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(PREFIX + key)

    actual fun saveString(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = PREFIX + key)
    }
}
