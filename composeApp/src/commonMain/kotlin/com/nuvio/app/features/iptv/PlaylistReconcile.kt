package com.nuvio.app.features.iptv

/**
 * B24 §4 — the pure reconcile core for the revision contract's conflict path. When a full-replace
 * push is rejected with the server's authoritative baseline, the client must NOT discard the local
 * edit and must NOT blindly re-push its own set (that is the corruption-reset → add-C wipe). Instead
 * it replays the pending mutation BY INTENT onto the server baseline:
 *
 *   reset → add C, server holds [A,B]  ⇒  Add(C) onto [A,B] = [A,B,C]   (A,B preserved)
 *   delete A,       server holds [A,B]  ⇒  Delete(A) onto [A,B] = [B]     (not a resurrection)
 *   edit B,         server holds [A,B]  ⇒  Update(B') onto [A,B] = [A,B'] (edit re-applied)
 *   edit B,         server deleted B    ⇒  Update(B') dropped, surfaced   (no zombie re-add)
 *
 * Intent, not a set-union: a set diff would resurrect a row the user deleted on this device the
 * moment another device still lists it. These are pure functions — the durable op log, the v2 push
 * loop and the baseline persistence are the I/O around them, tested separately.
 */
sealed interface PendingPlaylistOp {
    val id: String

    /** The user added a playlist locally (the reset → add-C case). Upserts onto the baseline. */
    data class Add(val account: XtreamAccount) : PendingPlaylistOp {
        override val id: String get() = account.id
    }

    /** The user edited an existing playlist. Re-applied to the matching baseline row; dropped (and
     *  surfaced) if the server no longer has that id. */
    data class Update(val account: XtreamAccount) : PendingPlaylistOp {
        override val id: String get() = account.id
    }

    /** The user removed a playlist. Removes the id from the baseline (never re-adds). */
    data class Delete(override val id: String) : PendingPlaylistOp
}

/** The reconciled set to re-push, plus any edits that could not apply because the server row is gone. */
data class PlaylistReconcileResult(
    val accounts: List<XtreamAccount>,
    val droppedUpdateIds: List<String>,
)

/**
 * Replay [ops] (in order) onto the authoritative server [baseline]. The result is what the client
 * re-pushes at the server's current revision. A [PendingPlaylistOp.Update] whose id is absent from
 * the baseline is dropped and reported in [PlaylistReconcileResult.droppedUpdateIds] — the client
 * surfaces it ("this playlist was removed on another device") rather than silently resurrecting it.
 */
fun reconcilePendingOntoBaseline(
    baseline: List<XtreamAccount>,
    ops: List<PendingPlaylistOp>,
): PlaylistReconcileResult {
    val byId = LinkedHashMap<String, XtreamAccount>()
    baseline.forEach { byId[it.id] = it }
    val dropped = mutableListOf<String>()

    ops.forEach { op ->
        when (op) {
            is PendingPlaylistOp.Add -> byId[op.account.id] = op.account // upsert: append or replace
            is PendingPlaylistOp.Update ->
                if (byId.containsKey(op.account.id)) byId[op.account.id] = op.account
                else dropped += op.account.id                            // server row gone — do not re-add
            is PendingPlaylistOp.Delete -> byId.remove(op.id)
        }
    }
    return PlaylistReconcileResult(byId.values.toList(), dropped)
}

/**
 * B24 §4 — the outcome of a v2 pull, kept explicit so the client never infers "the server has no
 * collection" from a failure. Treating a timeout / permission error / filtered-empty result as
 * absence is exactly what turns a transient error into a destructive fresh-create.
 */
sealed interface PlaylistPullOutcome {
    /** A SUCCESSFUL read: the server's rows and its current revision (revision may be 0 with rows if
     *  a legacy write seeded rows without a revision — still Present, never Absent). */
    data class Present(val accounts: List<XtreamAccount>, val revision: Long) : PlaylistPullOutcome

    /** A SUCCESSFUL read proving the collection does not exist yet: revision 0 AND no rows. Only this
     *  authorizes a fresh create (expected-revision = null). */
    data object AuthoritativeAbsent : PlaylistPullOutcome

    /** The pull did not authoritatively complete (network/timeout/permission/parse failure). The
     *  remote state is UNKNOWN — never treated as absent, never a basis for a full-replace. */
    data object Indeterminate : PlaylistPullOutcome
}

/**
 * Classify a v2 pull. [succeeded] must be true ONLY when the read authoritatively completed (the RPC
 * returned without error and was not silently filtered); a false [succeeded] is [Indeterminate]
 * regardless of the row/revision values, so a failed pull can never be mistaken for an empty server.
 */
fun classifyPlaylistPull(succeeded: Boolean, accounts: List<XtreamAccount>, revision: Long): PlaylistPullOutcome = when {
    !succeeded -> PlaylistPullOutcome.Indeterminate
    revision == 0L && accounts.isEmpty() -> PlaylistPullOutcome.AuthoritativeAbsent
    else -> PlaylistPullOutcome.Present(accounts, revision)
}

/**
 * Whether a fresh creation (push with expected-revision = null) is permitted. Only an authoritative
 * absence qualifies: an [PlaylistPullOutcome.Indeterminate] must reconcile-or-wait, never blind-create,
 * and a [PlaylistPullOutcome.Present] set must be reconciled onto (B24 §4).
 */
fun PlaylistPullOutcome.permitsFreshCreation(): Boolean = this is PlaylistPullOutcome.AuthoritativeAbsent
