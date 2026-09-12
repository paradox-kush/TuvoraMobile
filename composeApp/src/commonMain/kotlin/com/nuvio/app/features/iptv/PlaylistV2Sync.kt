package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * B24 — the real app-level v2 sync engine (optimistic-concurrency revision contract). This is the
 * shipping integration, not a test-only path: [XtreamAccountSyncService] drives it through a
 * [PlaylistSyncTransport] that calls the actual `sync_pull/push_iptv_playlists_v2` RPCs, and it is
 * activated only under a debug configuration until the backend is deployed (production stays v1).
 *
 * Guarantees implemented here (each has a unit test in PlaylistV2SyncEngineTest):
 *  - Pull the authoritative collection AND its revision together, then reconcile pending local ops
 *    onto it BY INTENT (reset → add C over server [A,B] = [A,B,C]) before pushing — never a blind
 *    overwrite, never a set-union that resurrects a deleted row.
 *  - Push with the expected revision, a stable mutation id, and explicit delete-all intent.
 *  - Retain the mutation id across retries AND restarts (persisted in the sync state); rotate it only
 *    after a commit — so a "commit succeeded, response lost" retry dedups server-side.
 *  - On conflict, re-reconcile the SAME pending ops onto the server's newer rows and re-push (bounded
 *    loop) — pending user intent is never silently discarded.
 *  - A failed/unavailable pull is Indeterminate: abort (no push, no wipe) — never inferred as empty.
 *  - A failed push NEVER falls back to the destructive v1 full-replace.
 *  - Acknowledge (clear) pending ops only after the server confirms the commit that carried them.
 */

/** A durable, per-profile pending op (collapsed to the latest intent per id), serialized to storage. */
@Serializable
internal data class PendingOpDto(
    val kind: String,                 // "upsert" | "delete"
    val id: String,
    val account: XtreamAccount? = null,
)

/** The persisted v2 sync state for one profile — survives the accounts-store corruption reset because
 *  it lives under its own storage key. */
@Serializable
internal data class PlaylistSyncState(
    val revision: Long = 0,
    val mutationId: String? = null,
    val pending: List<PendingOpDto> = emptyList(),
    /** True once the reconciled set is a deliberate empty (a user delete-all), so the push carries
     *  the explicit delete-all intent rather than being rejected as an accidental empty. */
    val deleteAllIntent: Boolean = false,
    /** The profile-lifetime generation this state (and its pending ops) is anchored to. Bumped by the
     *  backend on a profile deletion; when a pull reports a newer generation, the pending ops belong to
     *  a now-dead profile lifetime and are discarded rather than replayed onto the recreated profile. */
    val generation: Long = 0,
)

private fun PendingOpDto.toOp(): PendingPlaylistOp? = when (kind) {
    "add" -> account?.let { PendingPlaylistOp.Add(it) }       // a locally-created playlist: always (re)added
    "update" -> account?.let { PendingPlaylistOp.Update(it) } // an edit of an existing one: dropped if the server row is gone
    "delete" -> PendingPlaylistOp.Delete(id)
    else -> null
}

internal fun List<PendingOpDto>.toOps(): List<PendingPlaylistOp> = mapNotNull { it.toOp() }

/** The user ADDED a new playlist locally (reset → add-C). An add survives reconcile onto any baseline. */
internal fun List<PendingOpDto>.recordAdd(account: XtreamAccount): List<PendingOpDto> =
    filterNot { it.id == account.id } + PendingOpDto("add", account.id, account)

/** The user EDITED an existing playlist. Kept as an add if it was locally created this session (so it
 *  still survives), else an update (dropped on reconcile if the server row was deleted elsewhere). */
internal fun List<PendingOpDto>.recordUpdate(account: XtreamAccount): List<PendingOpDto> {
    val kind = if (firstOrNull { it.id == account.id }?.kind == "add") "add" else "update"
    return filterNot { it.id == account.id } + PendingOpDto(kind, account.id, account)
}

/** The user REMOVED a playlist. A row that was only ever added locally this session collapses to
 *  nothing (net no-op); otherwise a delete is recorded so the reconcile removes it from the server set. */
internal fun List<PendingOpDto>.recordDelete(id: String): List<PendingOpDto> {
    val wasLocalAdd = firstOrNull { it.id == id }?.kind == "add"
    val base = filterNot { it.id == id }
    return if (wasLocalAdd) base else base + PendingOpDto("delete", id)
}

/** The transport the engine drives — the real impl calls the v2 RPCs; tests supply a fake. */
internal interface PlaylistSyncTransport {
    /** A pull that authoritatively completed. Throws (or the caller catches) on network failure. */
    suspend fun pull(profileId: Int): PlaylistPullResponse

    suspend fun push(
        profileId: Int,
        expectedRevision: Long?,
        accounts: List<XtreamAccount>,
        deleteAll: Boolean,
        mutationId: String,
        expectedGeneration: Long?,
    ): PlaylistPushResponse
}

internal data class PlaylistPullResponse(val revision: Long, val accounts: List<XtreamAccount>, val generation: Long = 0)

internal sealed interface PlaylistPushResponse {
    data class Ok(val revision: Long, val deduped: Boolean = false) : PlaylistPushResponse
    data class Conflict(val currentRevision: Long, val currentRows: List<XtreamAccount>) : PlaylistPushResponse
    /** Rejected by the server for a reason that is NOT a revision conflict (e.g. empty-without-delete-all,
     *  mutation-id reuse) — surfaced, never retried blindly, never downgraded to v1. */
    data class Rejected(val reason: String) : PlaylistPushResponse
}

internal enum class PlaylistSyncOutcome {
    SYNCED,          // pushed (or nothing to push) and the baseline is current
    UP_TO_DATE,      // pulled; local already matched; nothing to push
    WITHHELD,        // not authoritative (Recovered/Corrupt/Absent with no pending intent) — server preserved
    PULL_FAILED,     // pull did not authoritatively complete — aborted, nothing wiped
    PUSH_FAILED,     // push failed after reconcile — pending ops retained for a later attempt
    CONFLICT_EXHAUSTED, // reconcile looped past the budget — pending ops retained
    REJECTED,        // server rejected the push (surfaced) — pending ops retained
}

/**
 * The pure engine. All I/O is injected, so the whole flow is unit-testable with a fake transport +
 * in-memory state, and the production wiring supplies the real RPC transport + durable storage.
 */
internal class PlaylistV2SyncEngine(
    private val transport: PlaylistSyncTransport,
    private val loadState: (Int) -> PlaylistSyncState,
    private val saveState: (Int, PlaylistSyncState) -> Unit,
    /** The current authoritative local accounts (what the user sees). */
    private val currentAccounts: () -> List<XtreamAccount>,
    /** Whether the loaded local state may drive a full set (Valid, incl. a genuine delete-all). */
    private val canPush: () -> Boolean,
    /** Apply a reconciled/pulled set as the new local state WITHOUT echoing a push back. */
    private val applyLocal: (profileId: Int, accounts: List<XtreamAccount>) -> Unit,
    /** Still targeting this profile? (a switch mid-flight must not redirect the payload). */
    private val stillActive: (Int) -> Boolean,
    private val newMutationId: () -> String,
    private val maxConflictRetries: Int = 5,
) {
    /**
     * One full sync for [profileId]: pull authoritative rows+revision, reconcile pending intent onto
     * them, and push under the revision contract. Network calls are made OUTSIDE any storage lock
     * (this function holds none; the caller serializes per profile).
     */
    suspend fun sync(profileId: Int): PlaylistSyncOutcome {
        // 1. Pull authoritatively. A failure is Indeterminate — abort, wipe nothing.
        val pull = runCatching { transport.pull(profileId) }.getOrNull() ?: return PlaylistSyncOutcome.PULL_FAILED
        if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED

        var state = loadState(profileId)
        // Generation reset (B24 profile-recreation safety): if the server reports a newer generation
        // than the one our pending ops are anchored to, the profile was deleted (and possibly recreated
        // at the reused id) after those ops were recorded. They belong to a dead profile lifetime, so we
        // DISCARD them — replaying them would silently populate the recreated profile. We then adopt the
        // new generation so any genuinely new local edit is anchored correctly. Persisted immediately so
        // the discard survives a crash before the push.
        if (pull.generation > state.generation && state.pending.isNotEmpty()) {
            state = state.copy(pending = emptyList(), deleteAllIntent = false)
        }
        if (state.generation != pull.generation) {
            state = state.copy(generation = pull.generation)
            saveState(profileId, state)
        }
        val recorded = state.pending.toOps()
        // The exact pending entries this sync will push. On commit we remove ONLY these, so an edit
        // recorded DURING the push (a newer pending entry) is preserved, never acknowledged with the
        // request that did not carry it (B24 §3).
        val ackedPending: List<PendingOpDto> = state.pending

        // 2. Decide the ops to replay and the starting expected revision.
        val pending: List<PendingPlaylistOp>
        var expected: Long?
        when {
            recorded.isNotEmpty() -> {
                pending = recorded
                expected = pull.revision
            }
            sameSet(currentAccounts(), pull.accounts) -> {
                // In sync — adopt the server revision, clear any stale mutation id.
                saveState(profileId, state.copy(revision = pull.revision, mutationId = null))
                return PlaylistSyncOutcome.UP_TO_DATE
            }
            pull.accounts.isEmpty() && pull.revision == 0L && canPush() && currentAccounts().isNotEmpty() -> {
                // Migration / first-ever v2 write: the server has no collection and the local set is a
                // genuine authored set. Push it up as an initial creation (expected = null).
                pending = currentAccounts().map { PendingPlaylistOp.Add(it) }
                expected = null
            }
            else -> {
                // No recorded intent and a divergence we can't attribute to the user (e.g. a damaged
                // local store, or another device's change): the server is authoritative — adopt it,
                // never push local over it.
                applyLocal(profileId, pull.accounts)
                saveState(profileId, state.copy(revision = pull.revision, pending = emptyList(), mutationId = null, deleteAllIntent = false))
                return if (canPush()) PlaylistSyncOutcome.UP_TO_DATE else PlaylistSyncOutcome.WITHHELD
            }
        }

        // 3. Reconcile pending intent onto the authoritative server rows and push. Keep the SAME
        //    mutation id across the whole attempt (and across restarts, since it is persisted).
        val mutationId = state.mutationId ?: newMutationId()
        var baseRows = pull.accounts
        // Persist the mutation id + baseline BEFORE the network write, but re-read first so a pending
        // entry recorded since [loadState] is not clobbered by this write.
        state = loadState(profileId).copy(mutationId = mutationId, revision = pull.revision)
        saveState(profileId, state)

        var retries = 0
        while (true) {
            if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED
            val reconciled = reconcilePendingOntoBaseline(baseRows, pending)
            applyLocal(profileId, reconciled.accounts)
            val deleteAll = reconciled.accounts.isEmpty()
            val resp = runCatching {
                // Anchor the write to the generation we observed on pull; the server rejects it as
                // stale_generation if the profile was deleted since, so a reconcile-retry cannot
                // resurrect a deleted-then-recreated profile even if this client did not discard.
                transport.push(profileId, expected, reconciled.accounts, deleteAll, mutationId, pull.generation)
            }.getOrElse { return PlaylistSyncOutcome.PUSH_FAILED } // keep pending + mutationId; retry later

            when (resp) {
                is PlaylistPushResponse.Ok -> {
                    // Acknowledge only now — the server confirmed the commit. Re-read the CURRENT log
                    // and remove ONLY the entries this request carried; a newer edit recorded mid-push
                    // stays pending for the next sync (B24 §3 — never ack an op not in the request).
                    val fresh = loadState(profileId)
                    saveState(profileId, fresh.copy(
                        revision = resp.revision,
                        pending = fresh.pending.filterNot { it in ackedPending },
                        mutationId = null,
                        deleteAllIntent = false,
                    ))
                    return PlaylistSyncOutcome.SYNCED
                }
                is PlaylistPushResponse.Conflict -> {
                    if (retries++ >= maxConflictRetries) {
                        // Preserve pending ops for a later attempt; do NOT discard user intent.
                        saveState(profileId, state.copy(revision = resp.currentRevision))
                        return PlaylistSyncOutcome.CONFLICT_EXHAUSTED
                    }
                    baseRows = resp.currentRows
                    expected = resp.currentRevision
                    state = state.copy(revision = expected)
                    saveState(profileId, state)
                    // loop: re-reconcile the same pending ops onto the newer server rows
                }
                is PlaylistPushResponse.Rejected -> {
                    // Surface; retain pending. Never fall back to v1.
                    return PlaylistSyncOutcome.REJECTED
                }
            }
        }
    }
}

/** Order-independent identity comparison (the payload's order is positional but not identity). */
private fun sameSet(a: List<XtreamAccount>, b: List<XtreamAccount>): Boolean =
    a.size == b.size && a.map { it.id }.toSet() == b.map { it.id }.toSet()

internal val playlistSyncStateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodePlaylistSyncState(raw: String?): PlaylistSyncState =
    raw?.let { runCatching { playlistSyncStateJson.decodeFromString(PlaylistSyncState.serializer(), it) }.getOrNull() }
        ?: PlaylistSyncState()

internal fun encodePlaylistSyncState(state: PlaylistSyncState): String =
    playlistSyncStateJson.encodeToString(PlaylistSyncState.serializer(), state)
