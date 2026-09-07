package com.nuvio.app.core.sync

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.isLocalOnly
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth

/**
 * Whether a call to Nuvio Sync can go out right now.
 *
 * Every `sync_*` RPC is `revoke all … from public, anon` + `grant execute … to authenticated`, so a
 * call made without a session doesn't fail as "not signed in" — PostgREST runs it as the `anon` role
 * and Postgres answers
 *
 *     permission denied for function sync_push_watch_progress   (SQLSTATE 42501)
 *
 * which is the exact trap [isLocalOnly] was written to document.
 */
internal object SyncSession {

    /**
     * True when an RPC issued right now will carry a real user's JWT.
     *
     * Two conditions, and both are load-bearing:
     *
     *  * [isLocalOnly] rules out "continue without an account". That is a LOCAL anonymous — a UUID
     *    in local storage that never had a GoTrue session at all.
     *  * A non-null access token rules out the case [AuthState] cannot see. supabase-kt reports a
     *    session only while `SessionStatus.Authenticated`; on `SessionStatus.RefreshFailure` it
     *    holds none, yet [AuthRepository] deliberately keeps publishing `Authenticated` so that a
     *    flaky network doesn't look like a sign-out. That is the right call for the UI and the
     *    wrong one for sync: during the gap postgrest falls back to the publishable key as bearer,
     *    so every RPC runs as `anon` and comes back 42501 — the whole pull+push cycle, retried,
     *    against a server that can never say yes.
     *
     * Nothing needs to re-arm sync when the session comes back: the periodic pull re-checks on each
     * tick, and foregrounding forces one.
     */
    fun canSync(): Boolean = hasAccount() && hasLiveAccessToken()

    /**
     * True when a real (non-local) account is signed in, regardless of whether its token is usable
     * this instant.
     *
     * This is the *lifecycle* question — should the periodic pull loop exist at all — and it must
     * NOT consider the access token. [AuthRepository] re-publishes an equal `Authenticated` value
     * across a refresh failure, so the StateFlow dedupes it and the Compose effects that own the
     * loop never re-run; a loop torn down for a momentarily-absent token would stay torn down.
     * Individual ticks ask [canSync] instead, so the loop keeps running and resumes talking to the
     * server the moment the session is back.
     */
    fun hasAccount(): Boolean = !AuthRepository.state.value.isLocalOnly

    /** True when a write can go out. Same question as [canSync], named for the push call sites. */
    fun canPush(): Boolean = canSync()

    /** Throws [SyncNotAuthenticatedException] unless [canPush]. */
    fun requirePushable() {
        if (!canPush()) throw SyncNotAuthenticatedException()
    }

    /**
     * Throws [SyncNotAuthenticatedException] unless [canSync] — the pull-side twin of
     * [requirePushable]. A pull fired without a live session runs as `anon` and comes back
     * `42501 permission denied for function sync_pull_…`; refusing it here keeps that noise off the
     * backend and, because [isSyncAuthRefusal] recognises the throw, abandons the rest of a cycle
     * whose session lapsed mid-flight instead of firing every remaining step as `anon`.
     */
    fun requirePullable() {
        if (!canSync()) throw SyncNotAuthenticatedException()
    }

    /**
     * Reading `client` builds one on demand from the selected backend, which can throw before a
     * backend has been resolved. No client means no session, which is the answer we want anyway.
     */
    private fun hasLiveAccessToken(): Boolean =
        runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }.getOrNull() != null
}

/**
 * True when this failure means "the server will refuse every other sync call too".
 *
 * A sync cycle is ~10 independent RPCs, each caught and reported on its own so one bad step can't
 * sink the rest. That is right for a step that failed on its own merits and wrong for an expired
 * session: there the first refusal already tells us the remaining nine will be refused identically,
 * and running them anyway is what turned one dead session into a burst of 42501s.
 *
 * Deliberately narrow. Only the two shapes that are certain to repeat qualify:
 *
 *  * [SyncNotAuthenticatedException] — this client refused the call before sending it.
 *  * `42501 / permission denied for function …` — PostgREST ran the call as `anon`.
 *
 * A 401 is NOT on the list: supabase-kt refreshes an expired access token underneath us, so a lone
 * 401 can genuinely resolve by the next step. `canSync()` is re-checked alongside this anyway, which
 * catches the session actually going away.
 */
internal fun Throwable.isSyncAuthRefusal(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SyncNotAuthenticatedException) return true
        val message = current.message?.lowercase()
        if (message != null &&
            ("42501" in message || "permission denied for function" in message)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * A sync call was attempted with no usable session, and was refused locally instead of being sent as
 * `anon` for the server to reject.
 *
 * Thrown rather than returned so callers keep treating it as "not pushed": every push site already
 * leaves the item in its dirty set when the push fails, which is exactly the behaviour wanted here
 * — the write stays queued and goes out on the next full sync after sign-in. Returning normally
 * would mark it pushed and lose it.
 *
 * Stays an [IllegalStateException] so existing `runCatching`/catch sites behave as before; call
 * sites that want to log it quietly can test for this type.
 */
internal class SyncNotAuthenticatedException :
    IllegalStateException("Not signed in — sync push skipped and left queued")
