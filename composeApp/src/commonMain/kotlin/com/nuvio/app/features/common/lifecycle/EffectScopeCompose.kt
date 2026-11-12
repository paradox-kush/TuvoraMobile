package com.nuvio.app.features.common.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import co.touchlab.kermit.Logger

private val revertLog = Logger.withTag("EffectScope")

/**
 * Where a failed/late revert is reported when nothing is provided: the log. iOS / Apple TV never wrap
 * their UI in `installFeatures` (only Android's MainActivity and the desktop window do), so a no-op
 * default silently dropped every teardown failure there (B232). Logging is the floor; FeatureWiring
 * provides the same function explicitly. It must never throw — a failed revert is already an error.
 */
fun logRevertFailure(t: Throwable) {
    revertLog.w(t) { "revert failed during teardown" }
}

val LocalRevertFailureSink = staticCompositionLocalOf<(Throwable) -> Unit> { ::logRevertFailure }

/**
 * A composition-scoped [EffectScope] that disposes (LIFO) when [keys] change or the composable
 * leaves. Hangs off DisposableEffect — the same disposal boundary the live player already uses via
 * `key(url)` at LiveTvScreen. The failure sink comes from [LocalRevertFailureSink], so call sites
 * never construct one.
 */
@Composable
fun rememberEffectScope(vararg keys: Any?): EffectScope {
    val sink = LocalRevertFailureSink.current
    val scope = remember(*keys) { EffectScope(onRevertFailure = sink) }
    DisposableEffect(scope) { onDispose { scope.dispose() } }
    return scope
}
