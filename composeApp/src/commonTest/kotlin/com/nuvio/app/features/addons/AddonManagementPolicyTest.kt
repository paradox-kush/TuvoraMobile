package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonManagementPolicyTest {
    @Test
    fun secondary_profile_following_main_is_read_only() {
        assertEquals(true, AddonManagementPolicy.isReadOnly(profileIndex = 2, usesPrimaryAddons = true))
    }

    @Test
    fun secondary_profile_with_own_list_can_edit() {
        assertEquals(false, AddonManagementPolicy.isReadOnly(profileIndex = 2, usesPrimaryAddons = false))
    }

    @Test
    fun main_profile_can_always_edit() {
        assertEquals(false, AddonManagementPolicy.isReadOnly(profileIndex = 1, usesPrimaryAddons = true))
    }

    @Test
    fun no_active_profile_can_edit() {
        assertEquals(false, AddonManagementPolicy.isReadOnly(profileIndex = null, usesPrimaryAddons = false))
    }
}
