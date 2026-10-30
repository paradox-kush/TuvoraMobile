package com.nuvio.app.features.settings

// tvOS apps cannot switch to an alternate home-screen icon (setAlternateIconName is iOS-only).
// Named gap: the icon picker reports "no alternate icon" and every activation is declined.
internal actual object AppIconPlatform {
    actual val requiresCloseConfirmation: Boolean = false
    actual fun loadSelectedIconKey(): String? = null
    actual fun saveSelectedIconKey(key: String) {}
    actual fun currentIconName(): String? = null
    actual suspend fun activateIcon(name: String?): Boolean = false
}
