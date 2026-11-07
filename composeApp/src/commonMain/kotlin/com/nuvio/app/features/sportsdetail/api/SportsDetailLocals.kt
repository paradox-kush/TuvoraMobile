package com.nuvio.app.features.sportsdetail.api

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's one game-detail service. FeatureWiring provides it (with the persistent, settings-backed
 * [MappingStorage]) so the screen reads it from the composition. Null where the feature is absent.
 */
val LocalSportsDetail = staticCompositionLocalOf<SportsDetailService?> { null }

/** The Watch block's backend (matcher + player state); [NoGameWatch] where IPTV is absent. */
val LocalGameWatch = staticCompositionLocalOf<GameWatchPort> { NoGameWatch }
