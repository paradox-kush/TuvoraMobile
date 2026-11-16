package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilePickerAddTilePolicyTest {
    @Test
    fun `empty picker offers the add tile outside manage mode`() {
        assertTrue(ProfilePickerAddTilePolicy.showAddTile(profileCount = 0, isEditMode = false))
    }

    @Test
    fun `empty picker offers the add tile in manage mode`() {
        assertTrue(ProfilePickerAddTilePolicy.showAddTile(profileCount = 0, isEditMode = true))
    }

    @Test
    fun `populated picker hides the add tile until manage mode`() {
        assertFalse(ProfilePickerAddTilePolicy.showAddTile(profileCount = 2, isEditMode = false))
        assertTrue(ProfilePickerAddTilePolicy.showAddTile(profileCount = 2, isEditMode = true))
    }

    @Test
    fun `full picker never offers the add tile`() {
        assertFalse(ProfilePickerAddTilePolicy.showAddTile(profileCount = MAX_PROFILES, isEditMode = true))
    }
}
