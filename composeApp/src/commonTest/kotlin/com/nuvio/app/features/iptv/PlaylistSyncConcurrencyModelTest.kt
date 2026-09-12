package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B24 §5 — a controlled model of the sync server's full-replace semantics, proving what the prior
 * report got wrong: an atomic, idempotent delete-then-insert RPC does NOT preserve an intervening
 * update. Repeatability of an identical payload is not preservation. The revision-guarded model
 * shows the fix. These are pure state models for reasoning, not the live backend.
 */
class PlaylistSyncConcurrencyModelTest {

    /** Today's `sync_push_iptv_playlists`: an unconditional full-replace (delete-then-insert). */
    private class UnconditionalServer(var state: List<String> = emptyList()) {
        fun fullReplace(payload: List<String>) {
            state = payload
        }
    }

    /** Proposed: full-replace only if the caller's expected revision still matches; else conflict. */
    private class RevisionServer(var state: List<String> = emptyList(), var revision: Long = 1) {
        /** Returns the new revision on success, or null (conflict) if [expected] is stale. */
        fun fullReplace(expected: Long, payload: List<String>): Long? {
            if (expected != revision) return null
            state = payload
            revision += 1
            return revision
        }
    }

    @Test
    fun `the current unconditional RPC loses an intervening update on retry`() {
        val server = UnconditionalServer(state = listOf("A", "B"))

        // Phone commits its set; the success RESPONSE is lost, so the phone believes it must retry.
        server.fullReplace(listOf("A"))
        // TV, meanwhile, commits a newer set.
        server.fullReplace(listOf("A", "TV-new"))
        // Phone retries the SAME payload (idempotent) — and clobbers TV's newer state.
        server.fullReplace(listOf("A"))

        assertEquals(listOf("A"), server.state, "TV's intervening update was lost — idempotency did not preserve it")
    }

    @Test
    fun `an expected-revision guard rejects the stale retry and preserves the newer update`() {
        val server = RevisionServer(state = listOf("A", "B"), revision = 5)

        // Phone reads rev 5, commits A; server advances to 6. The success response is lost — the
        // phone still holds its stale expected revision 5.
        val phoneExpected = 5L
        val afterPhone = server.fullReplace(phoneExpected, listOf("A"))
        assertEquals(6L, afterPhone)

        // TV reads rev 6, commits its newer set; server advances to 7.
        val afterTv = server.fullReplace(6L, listOf("A", "TV-new"))
        assertEquals(7L, afterTv)

        // Phone RETRIES with its stale expected revision 5 → conflict, not applied.
        val retry = server.fullReplace(phoneExpected, listOf("A"))
        assertNull(retry, "the stale retry is rejected")
        assertEquals(listOf("A", "TV-new"), server.state, "TV's newer update is preserved")
        assertEquals(7L, server.revision, "no phantom revision from the rejected retry")
    }

    @Test
    fun `a committed write retried with the up-to-date revision is a safe idempotent no-op-ish reapply`() {
        val server = RevisionServer(state = listOf("A"), revision = 3)
        // A retry that carries the CURRENT revision (the client re-read after a conflict) reapplies
        // cleanly — this is the reconcile path, distinct from the blind stale retry above.
        val ok = server.fullReplace(3L, listOf("A", "C"))
        assertEquals(4L, ok)
        assertEquals(listOf("A", "C"), server.state)
        assertTrue(server.revision == 4L)
    }
}
