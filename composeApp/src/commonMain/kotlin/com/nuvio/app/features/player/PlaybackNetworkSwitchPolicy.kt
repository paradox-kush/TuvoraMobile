package com.nuvio.app.features.player

/**
 * What playback does when the device's DEFAULT network changes mid-stream (Wi-Fi -> mobile data, and back).
 *
 * Neither OS moves an open socket. Android: once a new network is the default, only NEW connections use it
 * ("Read network state", developer.android.com); a connection on a Wi-Fi that is still associated but weak keeps
 * using it. Apple: a BSD socket keeps its source address after the path changes and the app has to reconnect
 * (TN3151; DTS forum 740420) — that is every libmpv/FFmpeg socket. And a weak-but-alive connection never fails
 * by itself: FFmpeg reconnects only on a read error or early EOF (`http.c`), its read-idle timeout resets on
 * every trickle of bytes, and OkHttp's readTimeout is per read too. Pooled keep-alive connections carry the old
 * network into the next segment request, and Media3 resets its bandwidth estimate on a network-type change and
 * rebuilds it from those same slow transfers. So playback glitched once at the switch and then stayed slow and
 * laggy for good: nothing ever moved the stream.
 *
 * On a real change the app moves its streams itself:
 *  - [Action.RELEASE_IDLE]: drop pooled idle connections, so the next request opens on the new network.
 *  - [Action.RECONNECT]: also re-open what is playing (Media3: cancel the in-flight read, which its load retry
 *    re-opens at the same byte; libmpv: reload at the playhead, or rejoin a live channel).
 *
 * A single-use link (Stalker `create_link`) is never re-opened here — a second open spends it
 * ([LiveLinkReconnectPolicy]); if its old path dies, the app-level recovery mints a fresh link. One reconnect per
 * [MIN_RECONNECT_INTERVAL_MS] at most, so a flapping network cannot loop reloads. Pure: the platforms feed it
 * through [PlaybackNetworkSwitchCoordinator]. NuvioTV mirrors it.
 */
object PlaybackNetworkSwitchPolicy {
    const val MIN_RECONNECT_INTERVAL_MS = 10_000L

    enum class Action { NONE, RELEASE_IDLE, RECONNECT }

    /**
     * [lastNetwork] is the last default network seen (null = none yet this listening period), [currentNetwork]
     * the new one (null = offline). Network ids are opaque: only equality matters.
     */
    fun decide(
        lastNetwork: String?,
        currentNetwork: String?,
        playbackActive: Boolean,
        anySingleUseLink: Boolean,
        lastReconnectAtMs: Long?,
        nowMs: Long,
    ): Action = when {
        // Offline: nothing to move to; the engine's own error/reconnect path handles the loss.
        currentNetwork == null -> Action.NONE
        // The first report after listening starts is the baseline, not a change.
        lastNetwork == null -> Action.NONE
        currentNetwork == lastNetwork -> Action.NONE
        !playbackActive || anySingleUseLink -> Action.RELEASE_IDLE
        lastReconnectAtMs != null && nowMs - lastReconnectAtMs < MIN_RECONNECT_INTERVAL_MS -> Action.RELEASE_IDLE
        else -> Action.RECONNECT
    }
}
