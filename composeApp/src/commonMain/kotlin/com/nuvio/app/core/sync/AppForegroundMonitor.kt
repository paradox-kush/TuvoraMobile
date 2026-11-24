package com.nuvio.app.core.sync

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

internal enum class AppVisibility {
    Foreground,
    Background,
}

internal expect object AppForegroundMonitor {
    fun events(): Flow<AppVisibility>
}

/**
 * Only a return-to-foreground may drive a foreground refresh/pull.
 *
 * The monitor's contract grew a `Background` case in the `Flow<Unit>` -> `Flow<AppVisibility>`
 * refactor (a merge-resolution carry-over), but both `events()` consumers still collected every
 * emission. That silently degraded cross-device sync on 1.5.7 (all platforms; worst on iOS, where
 * the monitor emits `Background` on every resign/suspend): a pull kicked off as the app backgrounds
 * fails (network torn / token flap) and arms `SyncManager`'s per-profile retry-backoff, which then
 * suppresses the NEXT, legitimate foreground pull — `requestForegroundPull(force = true)` bypasses
 * the recency throttle but NOT `isInRetryBackoff`. Filtering to `Foreground` restores the
 * pre-refactor foreground-only semantics.
 */
internal fun shouldTriggerForegroundWork(visibility: AppVisibility): Boolean =
    visibility == AppVisibility.Foreground

/** Foreground-only view of [AppForegroundMonitor.events], per [shouldTriggerForegroundWork]. */
internal fun Flow<AppVisibility>.foregroundEvents(): Flow<AppVisibility> =
    filter { shouldTriggerForegroundWork(it) }

/**
 * The visibility stream for a platform that offers "is active now?" plus became-active / will-resign notifications
 * (iOS, tvOS). The observers are registered BEFORE the current state is read: read first and an activation that
 * lands in between (app launch: the state reads Inactive, then didBecomeActive posts) is lost, and the stream sits on
 * Background until the next background/foreground round trip. That silently kept sports alerts (and every other
 * foreground-gated consumer) off for a whole launch on iPhone (2026-11-24 central pass).
 *
 * [observe] registers the two callbacks and returns their unregister action.
 */
internal fun appVisibilityEvents(
    isActive: () -> Boolean,
    observe: (onActive: () -> Unit, onResign: () -> Unit) -> (() -> Unit),
): Flow<AppVisibility> = callbackFlow {
    val unregister = observe(
        { trySend(AppVisibility.Foreground) },
        { trySend(AppVisibility.Background) },
    )
    trySend(if (isActive()) AppVisibility.Foreground else AppVisibility.Background)
    awaitClose { unregister() }
}.distinctUntilChanged()
