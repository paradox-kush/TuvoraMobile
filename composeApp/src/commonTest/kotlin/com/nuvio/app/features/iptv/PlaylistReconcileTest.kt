package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B24 §4 — the pure reconcile + pull-classification core. Proves the conflict path preserves the
 * user's intent onto the authoritative server baseline (no wipe, no resurrection) and that remote
 * absence is only ever inferred from a SUCCESSFUL, genuinely-empty read.
 */
class PlaylistReconcileTest {

    private fun acc(id: String, name: String = "P") =
        XtreamAccount(id = id, name = name, baseUrl = "http://$id", username = "u", password = "p")

    @Test
    fun `reset then add-C onto a server holding A and B preserves A and B`() {
        val baseline = listOf(acc("A"), acc("B"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Add(acc("C"))))
        assertEquals(listOf("A", "B", "C"), result.accounts.map { it.id }, "add-C reconciles to A,B,C — not a wipe")
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `a delete is not resurrected by the baseline`() {
        val baseline = listOf(acc("A"), acc("B"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Delete("A")))
        assertEquals(listOf("B"), result.accounts.map { it.id }, "delete-A stays deleted after reconcile")
    }

    @Test
    fun `an edit is re-applied to the matching baseline row`() {
        val baseline = listOf(acc("A"), acc("B", name = "Old"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Update(acc("B", name = "New"))))
        assertEquals("New", result.accounts.first { it.id == "B" }.name, "the edit re-applies onto the server row")
        assertEquals(2, result.accounts.size)
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `an edit of a server-deleted row is dropped and surfaced not resurrected`() {
        val baseline = listOf(acc("A")) // B was deleted on another device
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Update(acc("B", name = "New"))))
        assertEquals(listOf("A"), result.accounts.map { it.id }, "the deleted row is not re-added")
        assertEquals(listOf("B"), result.droppedUpdateIds, "the dropped edit is surfaced to the user")
    }

    @Test
    fun `multiple ops replay in order`() {
        val baseline = listOf(acc("A"), acc("B"))
        val result = reconcilePendingOntoBaseline(
            baseline,
            listOf(PendingPlaylistOp.Add(acc("C")), PendingPlaylistOp.Delete("A"), PendingPlaylistOp.Update(acc("B", name = "B2"))),
        )
        assertEquals(listOf("B", "C"), result.accounts.map { it.id }.sorted(), "A removed, C added")
        assertEquals("B2", result.accounts.first { it.id == "B" }.name)
    }

    @Test
    fun `add of an existing id upserts rather than duplicating`() {
        val baseline = listOf(acc("A", name = "Old"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Add(acc("A", name = "New"))))
        assertEquals(1, result.accounts.size, "no duplicate id")
        assertEquals("New", result.accounts.single().name)
    }

    // --- pull classification: never infer absence from a failure -----------------------------------

    @Test
    fun `a failed pull is indeterminate regardless of the values`() {
        assertEquals(PlaylistPullOutcome.Indeterminate, classifyPlaylistPull(succeeded = false, accounts = emptyList(), revision = 0))
        assertEquals(PlaylistPullOutcome.Indeterminate, classifyPlaylistPull(succeeded = false, accounts = listOf(acc("A")), revision = 5))
        assertFalse(classifyPlaylistPull(false, emptyList(), 0).permitsFreshCreation(), "a timeout/permission error must never authorize a fresh create")
    }

    @Test
    fun `a successful empty read at revision zero is authoritatively absent`() {
        val outcome = classifyPlaylistPull(succeeded = true, accounts = emptyList(), revision = 0)
        assertEquals(PlaylistPullOutcome.AuthoritativeAbsent, outcome)
        assertTrue(outcome.permitsFreshCreation(), "only a proven-empty server permits a fresh create")
    }

    @Test
    fun `a successful read with rows is present and does not permit a blind create`() {
        val outcome = classifyPlaylistPull(succeeded = true, accounts = listOf(acc("A")), revision = 3)
        assertTrue(outcome is PlaylistPullOutcome.Present)
        assertEquals(3L, (outcome as PlaylistPullOutcome.Present).revision)
        assertFalse(outcome.permitsFreshCreation(), "a populated server must be reconciled onto, never blind-created over")
    }

    @Test
    fun `rows present at revision zero are still Present not Absent - legacy-seeded rows`() {
        // A legacy write can seed rows without advancing the revision; that is a populated server,
        // never an absence.
        val outcome = classifyPlaylistPull(succeeded = true, accounts = listOf(acc("A")), revision = 0)
        assertTrue(outcome is PlaylistPullOutcome.Present, "rows at rev 0 are Present, not Absent")
        assertFalse(outcome.permitsFreshCreation())
    }
}
