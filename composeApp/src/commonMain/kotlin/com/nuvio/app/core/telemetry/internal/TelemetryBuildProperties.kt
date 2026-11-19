package com.nuvio.app.core.telemetry.internal

/**
 * Which build and install channel an event came from, attached to every event as super properties so retention
 * can be split by store vs sideload. Names and values are shared with the TV and Desktop apps: do not rename.
 * (Neither name may contain a word the privacy scrubber redacts: url, uri, token, code, state, ...)
 */
internal object TelemetryBuildProperties {
    const val BUILD_FLAVOR = "build_flavor"
    const val INSTALL_SOURCE = "install_source"

    /** [installSource] is already a bucket (see [InstallSourcePolicy]) or null where the platform has none. */
    fun of(buildFlavor: String, installSource: String?): Map<String, Any> = buildMap {
        put(BUILD_FLAVOR, buildFlavor)
        if (installSource != null) put(INSTALL_SOURCE, installSource)
    }
}

/** Buckets an Android installer package name. The raw package name never leaves the device. */
internal object InstallSourcePolicy {
    const val PLAY = "play"
    const val AMAZON = "amazon"
    const val PACKAGE_INSTALLER = "package_installer"
    const val OTHER = "other"
    const val UNKNOWN = "unknown"

    fun bucket(installerPackage: String?): String {
        val pkg = installerPackage?.trim()
        if (pkg.isNullOrEmpty()) return UNKNOWN
        return when (pkg) {
            "com.android.vending" -> PLAY
            "com.amazon.venezia" -> AMAZON
            "com.google.android.packageinstaller", "com.android.packageinstaller" -> PACKAGE_INSTALLER
            else -> OTHER
        }
    }
}
