package com.nuvio.app.features.addons

/**
 * Whether the active profile may edit its own add-on list. A secondary profile that follows the main
 * profile's add-ons (`usesPrimaryAddons`) reads the main profile's list and cannot change it; the screens
 * say so up front instead of offering an install form that can only fail.
 */
object AddonManagementPolicy {
    const val PRIMARY_PROFILE_INDEX = 1

    fun isReadOnly(profileIndex: Int?, usesPrimaryAddons: Boolean): Boolean =
        profileIndex != null && profileIndex != PRIMARY_PROFILE_INDEX && usesPrimaryAddons
}
