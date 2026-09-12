package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B24 — the real v2 sync engine's behavior, exercised through a fake transport + in-memory state so
 * the whole reconcile/retry/restart flow is verified deterministically (no network, no barriers).
 */
class PlaylistV2SyncEngineTest {

    private fun acc(id: String, name: String = "P") =
        XtreamAccount(id = id, name = name, baseUrl = "http://$id", username = "u", password = "p")

    /** A scriptable server: a real revision-guarded full-replace with mutation-id dedup + delete-all. */
    private class FakeServer(var rows: List<XtreamAccount> = emptyList(), var revision: Long = 0, var generation: Long = 0) {
        var lastMutationId: String? = null
        var lastMutationHash: String? = null
        // test hooks
        var dropNextResponse = false          // simulate "commit succeeds, response lost"
        var failPull = false
        var failNextPush = 0                  // fail this many push attempts with a thrown error
        var deletedNoProfile = false          // profile was deleted and NOT recreated (backend rejects)

        fun pull(): PlaylistPullResponse {
            if (failPull) throw RuntimeException("network")
            return PlaylistPullResponse(revision, rows, generation)
        }

        /** A profile deletion: clears rows, bumps revision AND generation, marks it deleted (no profiles row). */
        fun delete() { rows = emptyList(); revision += 1; generation += 1; deletedNoProfile = true }

        fun push(expected: Long?, expectedGen: Long?, payload: List<XtreamAccount>, deleteAll: Boolean, mutationId: String): PlaylistPushResponse {
            if (failNextPush > 0) { failNextPush--; throw RuntimeException("network") }
            val hash = payload.joinToString(",") { it.id } + "|" + deleteAll
            if (mutationId == lastMutationId) {
                if (hash != lastMutationHash) return PlaylistPushResponse.Rejected("mutation id reused with different payload")
                return PlaylistPushResponse.Ok(revision, deduped = true)
            }
            // Order mirrors the backend RPC: dedup, then stale_generation, then profile_deleted.
            // stale_generation: the push is anchored to a superseded generation (the profile was deleted
            // after these ops were recorded) — reject so a reconcile-retry cannot populate a recreated profile.
            if (expectedGen != null && expectedGen < generation) return PlaylistPushResponse.Rejected("stale_generation")
            // profile_deleted: deleted and not recreated (no profiles row).
            if (deletedNoProfile) return PlaylistPushResponse.Rejected("profile_deleted")
            if (payload.isEmpty() && !deleteAll) return PlaylistPushResponse.Rejected("empty without delete_all")
            if (expected == null) {
                // initial creation only on a genuinely first write
                if (revision != 0L || rows.isNotEmpty()) return PlaylistPushResponse.Conflict(revision, rows)
            } else if (expected != revision) {
                return PlaylistPushResponse.Conflict(revision, rows)
            }
            // commit
            rows = payload
            revision += 1
            lastMutationId = mutationId
            lastMutationHash = hash
            deletedNoProfile = false  // a committed write means the profile is live again
            if (dropNextResponse) { dropNextResponse = false; throw RuntimeException("response lost after commit") }
            return PlaylistPushResponse.Ok(revision)
        }
    }

    /** Wires the engine to a FakeServer + in-memory local state; models a device. */
    private class Device(val server: FakeServer, var local: List<XtreamAccount>, var authoritative: Boolean = true) {
        val stateStore = HashMap<Int, String>()   // serialized PlaylistSyncState — survives "restart"
        var mutCounter = 0
        var activeProfile = 1

        /** Runs after the server commits but before the engine handles the response — models an edit
         *  (or a profile switch) landing WHILE the push is in flight. */
        var midPushHook: (() -> Unit)? = null
        val transport = object : PlaylistSyncTransport {
            override suspend fun pull(profileId: Int) = server.pull()
            override suspend fun push(profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean, mutationId: String, expectedGeneration: Long?): PlaylistPushResponse {
                val r = server.push(expectedRevision, expectedGeneration, accounts, deleteAll, mutationId)
                midPushHook?.invoke()
                return r
            }
        }

        fun engine() = PlaylistV2SyncEngine(
            transport = transport,
            loadState = { decodePlaylistSyncState(stateStore[it]) },
            saveState = { p, s -> stateStore[p] = encodePlaylistSyncState(s) },
            currentAccounts = { local },
            canPush = { authoritative },
            applyLocal = { _, accts -> local = accts; authoritative = true },
            stillActive = { it == activeProfile },
            newMutationId = { "mut-${++mutCounter}" },
        )

        fun recordAdd(a: XtreamAccount) = mutate { it.recordAdd(a) }.also { local = local.filterNot { x -> x.id == a.id } + a }
        fun recordUpdate(a: XtreamAccount) = mutate { it.recordUpdate(a) }.also { local = local.filterNot { x -> x.id == a.id } + a }
        fun recordDelete(id: String) = mutate { it.recordDelete(id) }.also { local = local.filterNot { x -> x.id == id } }
        private fun mutate(f: (List<PendingOpDto>) -> List<PendingOpDto>) {
            val s = decodePlaylistSyncState(stateStore[1])
            stateStore[1] = encodePlaylistSyncState(s.copy(pending = f(s.pending)))
        }
    }

    @Test
    fun `reset then add-C over server A and B reconciles to A B C through the live engine path`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A"), acc("B")), revision = 4)
        // local storage "reset": the accounts store is empty/absent, but the user added C (pending op).
        val dev = Device(server, local = emptyList(), authoritative = false)
        dev.recordAdd(acc("C"))

        val outcome = dev.engine().sync(1)

        assertEquals(PlaylistSyncOutcome.SYNCED, outcome)
        assertEquals(listOf("A", "B", "C"), server.rows.map { it.id }.sorted(), "server holds A,B,C — no wipe")
        assertEquals(listOf("A", "B", "C"), dev.local.map { it.id }.sorted(), "local reflects the reconciled set")
        assertEquals(5L, server.revision)
        assertTrue(decodePlaylistSyncState(dev.stateStore[1]).pending.isEmpty(), "pending cleared after commit")
    }

    @Test
    fun `a failed pull aborts and wipes nothing`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A"), acc("B")), revision = 2)
        server.failPull = true
        val dev = Device(server, local = emptyList(), authoritative = false)
        dev.recordAdd(acc("C"))

        val outcome = dev.engine().sync(1)

        assertEquals(PlaylistSyncOutcome.PULL_FAILED, outcome)
        assertEquals(listOf("A", "B"), server.rows.map { it.id }, "server untouched on a failed pull")
        assertEquals(2L, server.revision)
    }

    @Test
    fun `commit succeeds but response lost - the retry dedups to the same revision`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A")), revision = 1)
        val dev = Device(server, local = listOf(acc("A")))
        dev.recordAdd(acc("B")) // add B
        server.dropNextResponse = true

        // first sync: the push commits (rev 2) but the response is "lost" (thrown) -> PUSH_FAILED
        val first = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.PUSH_FAILED, first)
        assertEquals(2L, server.revision, "the commit actually happened")
        val persisted = decodePlaylistSyncState(dev.stateStore[1])
        assertTrue(persisted.mutationId != null, "mutation id retained for the retry")
        assertTrue(persisted.pending.isNotEmpty(), "pending retained")

        // retry (same persisted mutation id) -> server dedups, returns the committed revision, ok
        val second = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.SYNCED, second)
        assertEquals(2L, server.revision, "no phantom revision from the retry")
        assertEquals(listOf("A", "B"), server.rows.map { it.id }.sorted())
    }

    @Test
    fun `mutation id survives a restart before retry`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A")), revision = 1)
        val dev = Device(server, local = listOf(acc("A")))
        dev.recordAdd(acc("B"))
        server.dropNextResponse = true
        dev.engine().sync(1) // commits rev2, response lost
        val midId = decodePlaylistSyncState(dev.stateStore[1]).mutationId

        // "restart": rebuild the engine from the SAME persisted stateStore (a new Device sharing state)
        val dev2 = Device(server, local = dev.local, authoritative = dev.authoritative)
        dev2.stateStore.putAll(dev.stateStore)
        val afterRestart = decodePlaylistSyncState(dev2.stateStore[1]).mutationId
        assertEquals(midId, afterRestart, "mutation id persisted across restart")

        val outcome = dev2.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.SYNCED, outcome)
        assertEquals(2L, server.revision, "restarted retry still dedups — no double apply")
    }

    @Test
    fun `two clients on the same revision - the stale one reconciles instead of clobbering`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A")), revision = 1)
        val dev = Device(server, local = listOf(acc("A")))
        dev.recordAdd(acc("C")) // this device wants to add C at rev1
        // meanwhile another client commits B at rev1 -> rev2
        server.push(1L, 0L, listOf(acc("A"), acc("B")), false, "other-client")
        assertEquals(2L, server.revision)

        val outcome = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.SYNCED, outcome)
        assertEquals(listOf("A", "B", "C"), server.rows.map { it.id }.sorted(), "B preserved, C added — no lost update")
    }

    @Test
    fun `a delete-all pushes explicit delete-all intent`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A")), revision = 3)
        val dev = Device(server, local = listOf(acc("A")))
        dev.recordDelete("A")

        val outcome = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.SYNCED, outcome)
        assertTrue(server.rows.isEmpty(), "server cleared via explicit delete-all")
        assertEquals(4L, server.revision)
    }

    @Test
    fun `an edit of a server-deleted row is dropped - others preserved`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A")), revision = 5) // B was deleted elsewhere
        val dev = Device(server, local = listOf(acc("A"), acc("B")))
        dev.recordUpdate(acc("B", name = "Edited")) // edit B, which no longer exists on server

        val outcome = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.SYNCED, outcome)
        assertEquals(listOf("A"), server.rows.map { it.id }, "the edit of a gone row is not resurrected")
    }

    @Test
    fun `nothing pending and local equals server is up-to-date with no push`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A"), acc("B")), revision = 7)
        val dev = Device(server, local = listOf(acc("A"), acc("B")))
        val revBefore = server.revision
        val outcome = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, outcome)
        assertEquals(revBefore, server.revision, "no push when already in sync")
    }

    // --- Storage-failure boundaries (B24 §2): missing/lost sync-state must NOT wipe the server. ----

    @Test
    fun `whole-store loss - empty local and no sync-state adopts the server and never wipes`() = runBlocking {
        // Both the accounts store AND the sync-state are gone (single-file loss on mobile). Local is
        // empty + non-authoritative; there is no pending intent. The server holds a real set.
        val server = FakeServer(rows = listOf(acc("A"), acc("B")), revision = 8)
        val dev = Device(server, local = emptyList(), authoritative = false)
        // stateStore is empty -> decodePlaylistSyncState returns the default (rev 0, no pending, no id)
        val outcome = dev.engine().sync(1)
        // Non-destructive adopt: the server is authoritative, so the (healed) pull leaves us in sync.
        assertTrue(outcome == PlaylistSyncOutcome.UP_TO_DATE || outcome == PlaylistSyncOutcome.WITHHELD, "adopt, not push; got $outcome")
        assertEquals(listOf("A", "B"), server.rows.map { it.id }.sorted(), "the server set is preserved (no wipe)")
        assertEquals(8L, server.revision, "no push happened")
        assertEquals(listOf("A", "B"), dev.local.map { it.id }.sorted(), "local adopts the authoritative server set")
    }

    @Test
    fun `lost sync-state with a stale authoritative local set adopts the server and does not push local over it`() = runBlocking {
        // The accounts store survived with a stale [X] (looks authoritative), but the sync-state (and
        // thus any pending intent) was lost. The server has moved on to [A,B]. Missing pending must
        // NOT let the stale local push over the server.
        val server = FakeServer(rows = listOf(acc("A"), acc("B")), revision = 9)
        val dev = Device(server, local = listOf(acc("X")), authoritative = true)
        val outcome = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, outcome)
        assertEquals(listOf("A", "B"), server.rows.map { it.id }.sorted(), "server not clobbered by the stale local set")
        assertEquals(9L, server.revision, "no push from a lost-pending state")
        assertEquals(listOf("A", "B"), dev.local.map { it.id }.sorted(), "local adopts the server; the un-tracked X is dropped (lost, not recoverable)")
    }

    @Test
    fun `a missing server collection with an authoritative local set migrates up - initial create`() = runBlocking {
        // The ONLY case a local set may be pushed with no pending log: the server is genuinely empty
        // (rev 0). Missing sync-state does not block a legitimate first-ever upload.
        val server = FakeServer(rows = emptyList(), revision = 0)
        val dev = Device(server, local = listOf(acc("A"), acc("B")), authoritative = true)
        val outcome = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.SYNCED, outcome)
        assertEquals(listOf("A", "B"), server.rows.map { it.id }.sorted(), "first-ever local set uploaded")
        assertEquals(1L, server.revision)
    }

    @Test
    fun `an edit made during an in-flight push is preserved - not acknowledged with A`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A")), revision = 1)
        val dev = Device(server, local = listOf(acc("A")))
        dev.recordAdd(acc("B")) // op A: add B
        // While B's push is in flight (committed, response not yet handled), the user adds C.
        dev.midPushHook = { dev.recordAdd(acc("C")) }

        val outcome = dev.engine().sync(1)

        assertEquals(PlaylistSyncOutcome.SYNCED, outcome)
        assertEquals(listOf("A", "B"), server.rows.map { it.id }.sorted(), "the request carried only A,B")
        val pendingAfter = decodePlaylistSyncState(dev.stateStore[1]).pending
        assertEquals(listOf("C"), pendingAfter.map { it.id }, "the mid-flight edit C stays pending, not acked with B")

        // A second sync flushes C -> server ends with A,B,C.
        dev.midPushHook = null
        assertEquals(PlaylistSyncOutcome.SYNCED, dev.engine().sync(1))
        assertEquals(listOf("A", "B", "C"), server.rows.map { it.id }.sorted(), "C syncs on the next pass")
        assertTrue(decodePlaylistSyncState(dev.stateStore[1]).pending.isEmpty())
    }

    @Test
    fun `a profile switch during a pending push does not redirect or clear another profile`() = runBlocking {
        // profile 1 has a pending add; the active profile flips to 2 mid-push. The engine targets the
        // captured profile 1 and must not touch profile 2's state.
        val server = FakeServer(rows = listOf(acc("A")), revision = 1)
        val dev = Device(server, local = listOf(acc("A")))
        dev.recordAdd(acc("B"))
        dev.stateStore[2] = encodePlaylistSyncState(PlaylistSyncState(revision = 99, pending = listOf(PendingOpDto("add", "Z", acc("Z")))))
        dev.midPushHook = { dev.activeProfile = 2 } // switch away mid-push

        dev.engine().sync(1)

        // profile 2's state is untouched by profile 1's sync.
        val p2 = decodePlaylistSyncState(dev.stateStore[2])
        assertEquals(99L, p2.revision, "profile 2 revision untouched")
        assertEquals(listOf("Z"), p2.pending.map { it.id }, "profile 2 pending untouched")
    }

    @Test
    fun `malformed sync-state decodes to the safe default`() {
        assertEquals(PlaylistSyncState(), decodePlaylistSyncState("not json at all"))
        assertEquals(PlaylistSyncState(), decodePlaylistSyncState("{ truncated"))
        assertEquals(PlaylistSyncState(), decodePlaylistSyncState(null))
    }

    @Test
    fun `a deleted profile's pending op is discarded and cannot populate a recreated profile`() = runBlocking {
        // Client A recorded a durable pending add C on a live profile (anchored to generation 0), then
        // the profile was DELETED elsewhere (server empty, revision+generation bumped). A's recovery must
        // NOT replay C onto a profile recreated at the reused id — that is the silent-population defect
        // B24 §1 requires us to prevent. On pull, A sees the newer generation, DISCARDS the now-obsolete
        // pending, and pushes nothing. (This replaces the earlier "retain and replay once recreated"
        // behavior, which was itself the defect: a dead profile's op reappearing on the new profile.)
        val server = FakeServer(rows = listOf(acc("A")), revision = 1, generation = 0)
        val dev = Device(server, local = listOf(acc("A")))
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, dev.engine().sync(1))  // anchor state to generation 0
        dev.recordAdd(acc("C"))
        assertEquals(listOf("C"), decodePlaylistSyncState(dev.stateStore[1]).pending.map { it.id }, "pending C recorded")

        server.delete()  // profile deleted (and not yet recreated): revision+generation bumped

        // A's recovery: pull reports generation 1 > anchored 0 -> discard pending C; nothing pushed.
        val outcome1 = dev.engine().sync(1)
        assertTrue(outcome1 == PlaylistSyncOutcome.UP_TO_DATE || outcome1 == PlaylistSyncOutcome.WITHHELD,
            "after discarding obsolete pending there is nothing to push")
        assertTrue(server.rows.isEmpty(), "server was NOT resurrected")
        val st1 = decodePlaylistSyncState(dev.stateStore[1])
        assertTrue(st1.pending.isEmpty(), "obsolete pending add C was discarded")
        assertEquals(1L, st1.generation, "client adopted the new generation")

        // Restart A (state survives) and retry: still no resurrection.
        val outcome2 = dev.engine().sync(1)
        assertTrue(outcome2 == PlaylistSyncOutcome.UP_TO_DATE || outcome2 == PlaylistSyncOutcome.WITHHELD,
            "restart re-sync stays clean")
        assertTrue(server.rows.isEmpty(), "server stays empty across the retry")

        // The profile is legitimately RECREATED at the reused id. A genuinely NEW local op (anchored to
        // the current generation) must still sync normally.
        server.deletedNoProfile = false
        dev.recordAdd(acc("D"))
        val outcome3 = dev.engine().sync(1)
        assertEquals(PlaylistSyncOutcome.SYNCED, outcome3, "a legitimate new op on the recreated profile commits")
        assertEquals(listOf("D"), server.rows.map { it.id }, "recreated profile holds only its own new op, not the dead C")
    }

    @Test
    fun `a stale-generation push is rejected by the server contract`() = runBlocking {
        // Defense-in-depth: even a client that did NOT discard (older build) cannot populate the profile,
        // because a push anchored to a superseded generation is rejected by the server contract.
        val server = FakeServer(rows = emptyList(), revision = 2, generation = 1)
        val resp = server.push(expected = 2, expectedGen = 0, payload = listOf(acc("C")), deleteAll = false, mutationId = "m1")
        assertEquals(PlaylistPushResponse.Rejected("stale_generation"), resp)
        assertTrue(server.rows.isEmpty(), "a stale-generation push does not populate the server")
    }
}
