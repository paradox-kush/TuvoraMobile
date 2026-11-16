package com.tuvora.tvos.screens

import com.nuvio.app.features.profiles.PinVerifyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvProfileManagePolicyTest {
    private val p = TvProfileManagePolicy

    @Test
    fun `options follow NuvioTV - no delete for the primary and remove only when locked`() {
        assertEquals(listOf(TvProfileOption.Edit, TvProfileOption.SetPin), p.options(1, pinEnabled = false))
        assertEquals(
            listOf(TvProfileOption.Edit, TvProfileOption.ChangePin, TvProfileOption.RemovePin, TvProfileOption.Delete),
            p.options(3, pinEnabled = true),
        )
        assertEquals(TvPinMode.VerifyForDelete, p.pinModeFor(TvProfileOption.Delete, pinEnabled = true))
        assertNull(p.pinModeFor(TvProfileOption.Delete, pinEnabled = false))
        assertEquals(TvPinMode.VerifyForChange, p.pinModeFor(TvProfileOption.ChangePin, pinEnabled = true))
    }

    @Test
    fun `add card shows until the account is full`() {
        assertTrue(p.canAddProfile(5))
        assertFalse(p.canAddProfile(6))
    }

    @Test
    fun `empty account still offers the add card`() {
        assertTrue(p.canAddProfile(0))
    }

    @Test
    fun `names are capped and blank names cannot be saved`() {
        assertEquals(20, p.clampName("x".repeat(30)).length)
        assertFalse(p.canSave("  ", isSaving = false))
        assertFalse(p.canSave("Kid", isSaving = true))
        assertTrue(p.canSave("Kid", isSaving = false))
    }

    @Test
    fun `picking an avatar takes its colour and picking it again restores the fallback`() {
        assertEquals("a1" to "#FF0000", p.toggleAvatar(null, "a1", "#FF0000", "#1E88E5", "#1E88E5"))
        assertEquals(null to "#43A047", p.toggleAvatar("a1", "a1", "#FF0000", "#FF0000", "#43A047"))
        assertEquals("a2" to "#1E88E5", p.toggleAvatar("a1", "a2", null, "#1E88E5", "#43A047"))
    }

    @Test
    fun `set pin asks twice and a mismatch starts over with NuvioTV copy`() {
        val start = TvPinFlowState(TvPinMode.Set)
        val first = p.submit(start, "1234")
        assertEquals(TvPinActionKind.Advance, first.kind)
        assertTrue(first.next.confirming)
        assertEquals("Confirm your new PIN.", p.heading(first.next, "Kid"))
        val mismatch = p.submit(first.next, "9999")
        assertEquals(TvPinActionKind.Advance, mismatch.kind)
        assertFalse(mismatch.next.confirming)
        assertEquals("PINs did not match. Enter a new PIN again.", mismatch.next.localError)
        val match = p.submit(first.next, "1234")
        assertEquals(TvPinActionKind.SetPin, match.kind)
        assertEquals("1234", match.pin)
    }

    @Test
    fun `change pin verifies the current one then carries it into set`() {
        val verify = p.submit(TvPinFlowState(TvPinMode.VerifyForChange), "1111")
        assertEquals(TvPinActionKind.Verify, verify.kind)
        assertEquals(TvPinVerifiedKind.StartNewPin, p.verified(TvPinMode.VerifyForChange))
        val set = p.startNewPin("1111")
        val confirm = p.submit(p.submit(set, "2222").next, "2222")
        assertEquals(TvPinActionKind.SetPin, confirm.kind)
        assertEquals("1111", confirm.currentPin)
    }

    @Test
    fun `remove clears with the entered pin and delete verifies first`() {
        val remove = p.submit(TvPinFlowState(TvPinMode.VerifyForRemove), "4321")
        assertEquals(TvPinActionKind.ClearPin, remove.kind)
        assertEquals("4321", remove.currentPin)
        assertEquals(TvPinVerifiedKind.ConfirmDelete, p.verified(TvPinMode.VerifyForDelete))
        assertEquals(TvPinVerifiedKind.OpenProfile, p.verified(TvPinMode.Unlock))
    }

    @Test
    fun `save outcomes surface the server's reason`() {
        val state = TvPinFlowState(TvPinMode.Set, confirming = true, draft = "1234")
        assertEquals(TvPinSaveKind.Saved, p.afterSet(state, PinVerifyResult(unlocked = true)).kind)
        val needsCurrent = p.afterSet(state, PinVerifyResult(unlocked = false, currentPinRequired = true))
        assertEquals(TvPinSaveKind.CurrentPinRequired, needsCurrent.kind)
        assertEquals(TvPinMode.VerifyForChange, needsCurrent.next?.mode)
        val offline = p.afterSet(state, PinVerifyResult(unlocked = false, message = "Connect to the internet to set a PIN."))
        assertEquals("Connect to the internet to set a PIN.", offline.message)
        assertFalse(offline.next!!.confirming)
        assertEquals("Could not save PIN. Try again.", p.afterSet(state, null).message)
        assertEquals("Current PIN is incorrect.", p.afterClear(PinVerifyResult(unlocked = false)).message)
        assertEquals(TvPinSaveKind.Saved, p.afterClear(PinVerifyResult(unlocked = true)).kind)
    }

    @Test
    fun `headings name the profile`() {
        assertEquals("Enter your PIN to access Kid.", p.heading(TvPinFlowState(TvPinMode.Unlock), "Kid"))
        assertEquals("Enter current PIN to delete Kid.", p.heading(TvPinFlowState(TvPinMode.VerifyForDelete), "Kid"))
        assertFalse(p.showsForgotHint(TvPinMode.Set))
    }

    @Test
    fun `avatar categories put all and the pinned ones first`() {
        assertEquals(
            listOf("all", "anime", "gaming", "character", "Zoo"),
            p.avatarCategories(listOf("character", "gaming", " anime ", "Zoo", "", "character")),
        )
        assertTrue(p.inCategory("Anime ", "anime"))
        assertTrue(p.inCategory("character", "all"))
        assertFalse(p.inCategory("character", "anime"))
        assertEquals("TV", p.categoryLabel("tv"))
        assertEquals("Character", p.categoryLabel("character"))
    }
}
