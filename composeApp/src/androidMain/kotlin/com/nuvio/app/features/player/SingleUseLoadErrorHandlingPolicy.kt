package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Media3's load-error policy for a single-use link ([LiveLinkReconnectPolicy.Mode.SINGLE_USE]).
 *
 * The default re-opens a failed load up to six times for progressive live
 * (`DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE`), at 0..5 s delays. A spent Stalker
 * `create_link` cannot answer any of them, so the first failure surfaces at once and the app-level
 * recovery mints a fresh link. NuvioTV's `SESSION_ONLY` uses the same `DefaultLoadErrorHandlingPolicy(0)`.
 */
@UnstableApi
internal object SingleUseLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
}
