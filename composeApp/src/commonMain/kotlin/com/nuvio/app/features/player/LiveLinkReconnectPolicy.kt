package com.nuvio.app.features.player

/**
 * Whether the ENGINE may re-open a stream URL by itself after a drop, and the options that say so.
 *
 * A Stalker `create_link` URL is single-use: the portal spends its token on the first open. Letting
 * FFmpeg (`stream-lavf-o reconnect=1`) or Media3 (OkHttp connection retries plus the default load
 * retries — six for progressive live) re-open it after a drop cannot succeed and only spends requests
 * and connection slots on the provider. For such a link the engine gives up on the first failure, and
 * the app-level recovery mints a fresh link through its own bounded path
 * ([PlayerCredentialRefreshPolicy], [com.nuvio.app.core.analytics.LivePlaybackRecoveryPolicy]). Every
 * other stream keeps the in-engine reconnect, which heals a transient drop without a rebuild.
 *
 * Pure: each platform feeds its engine from it (mpv `stream-lavf-o` set before every `loadfile`;
 * Media3 data source and load-error policy). NuvioTV scopes the same rule as `SESSION_ONLY`.
 */
object LiveLinkReconnectPolicy {

    enum class Mode {
        /** The engine re-opens the URL after a drop (reusable links: Xtream, M3U, add-ons, VOD). */
        ENGINE_RECONNECT,

        /** The URL is spent after its first open: no in-engine re-open; the app mints a fresh link. */
        SINGLE_USE,
    }

    /**
     * mpv `stream-lavf-o` for a reusable link. `reconnect_streamed=1` is what lets a non-seekable live
     * stream re-open at all; mpv ignores misspelled keys silently, so these are pinned by a test.
     */
    const val ENGINE_RECONNECT_LAVF_OPTIONS =
        "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5"

    /** mpv `stream-lavf-o` for a single-use link: every FFmpeg re-open switch off. */
    const val SINGLE_USE_LAVF_OPTIONS =
        "reconnect=0,reconnect_streamed=0,reconnect_on_network_error=0"

    /**
     * A live Stalker channel is single-use. A replay is not marked here (this overload keeps the walked
     * Xtream/M3U replay reusable); a Stalker archive replay is minted by `create_link` and flagged through
     * [modeFor] (singleUseLink = true).
     */
    fun modeFor(isStalkerSource: Boolean, isLive: Boolean, isCatchUp: Boolean = false): Mode =
        if (isStalkerSource && isLive && !isCatchUp) Mode.SINGLE_USE else Mode.ENGINE_RECONNECT

    fun modeFor(singleUseLink: Boolean): Mode =
        if (singleUseLink) Mode.SINGLE_USE else Mode.ENGINE_RECONNECT

    /** The `stream-lavf-o` value to set before the next `loadfile` (replaces the whole list). */
    fun mpvStreamLavfOptions(mode: Mode): String = when (mode) {
        Mode.ENGINE_RECONNECT -> ENGINE_RECONNECT_LAVF_OPTIONS
        Mode.SINGLE_USE -> SINGLE_USE_LAVF_OPTIONS
    }

    /**
     * May the surface answer a first-engine failure by opening the SAME url on the other engine (Exo -> libmpv)?
     * Never for a single-use link: the first open spent it, so the second engine would be handed a dead token
     * (and a second request on the provider's session cap). The app mints a fresh link instead.
     */
    fun allowsEngineFailover(mode: Mode): Boolean = mode == Mode.ENGINE_RECONNECT

    /**
     * May the link go through the on-disk media cache (a caching data source can re-request ranges of the URL)?
     * Never for a single-use link: it is read once, front to back, by the engine alone.
     */
    fun allowsMediaCache(mode: Mode): Boolean = mode == Mode.ENGINE_RECONNECT

    /** Media3 lane: OkHttp `retryOnConnectionFailure` and the load-error retries. */
    fun allowsTransportRetry(mode: Mode): Boolean = mode == Mode.ENGINE_RECONNECT
}
