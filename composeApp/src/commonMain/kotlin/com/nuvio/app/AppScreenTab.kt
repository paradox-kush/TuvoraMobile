package com.nuvio.app

import com.nuvio.app.core.ui.NativeNavigationTab

enum class AppScreenTab {
    Home,
    Search,
    Library,
    Iptv,
    Sports,
    Settings,
    ;

    companion object {
        fun fromName(name: String): AppScreenTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Home
    }
}

// null for tabs the iOS liquid-glass native tab bar doesn't carry (IPTV isn't in the native
// bar yet — needs Swift work; on Android the Compose nav bar renders it).
internal fun AppScreenTab.toNativeNavigationTab(): NativeNavigationTab? = when (this) {
    AppScreenTab.Home -> NativeNavigationTab.Home
    AppScreenTab.Search -> NativeNavigationTab.Search
    AppScreenTab.Library -> NativeNavigationTab.Library
    AppScreenTab.Settings -> NativeNavigationTab.Settings
    AppScreenTab.Iptv -> NativeNavigationTab.Iptv
    AppScreenTab.Sports -> NativeNavigationTab.Sports
}

internal fun NativeNavigationTab.toAppScreenTab(): AppScreenTab = when (this) {
    NativeNavigationTab.Home -> AppScreenTab.Home
    NativeNavigationTab.Search -> AppScreenTab.Search
    NativeNavigationTab.Library -> AppScreenTab.Library
    NativeNavigationTab.Iptv -> AppScreenTab.Iptv
    NativeNavigationTab.Sports -> AppScreenTab.Sports
    NativeNavigationTab.Settings -> AppScreenTab.Settings
}
