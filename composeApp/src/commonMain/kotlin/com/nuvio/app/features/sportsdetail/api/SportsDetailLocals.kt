package com.nuvio.app.features.sportsdetail.api

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The service and Watch port the platform registered at process start (FeatureWiring.registerFeatureContributions).
 * iOS never wraps its UI in `installFeatures` (only Android's MainActivity does), so screens must still find them
 * with nothing provided — a null default sent every iPhone game straight back to the old match sheet.
 */
object SportsDetailAccess {
    private var service: SportsDetailService? = null
    private var watch: GameWatchPort? = null

    fun register(service: SportsDetailService, watch: GameWatchPort) {
        this.service = service
        this.watch = watch
    }

    fun service(): SportsDetailService? = service
    fun watch(): GameWatchPort = watch ?: NoGameWatch
    fun resetForTest() { service = null; watch = null }
}

/** The game-detail service; defaults to the registered one, null where the feature is absent. */
val LocalSportsDetail = staticCompositionLocalOf<SportsDetailService?> { SportsDetailAccess.service() }

/** The Watch block's backend (matcher + player state); [NoGameWatch] where IPTV is absent. */
val LocalGameWatch = staticCompositionLocalOf<GameWatchPort> { SportsDetailAccess.watch() }
