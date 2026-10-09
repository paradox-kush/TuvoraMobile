package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState

/** Settles a completed native-source load; discovery filters for add-ons never hide a chosen provider. */
internal object DirectSourceCompletionPolicy {
    fun complete(state: StreamsUiState, settings: PlayerSettingsUiState, manualSelection: Boolean,
                 preferredBingeGroup: String? = null, debridEnabled: Boolean = true,
                 activeResolverProviderId: String? = null): StreamsUiState {
        val enabled = !manualSelection && (StreamAutoPlayPolicy.isEffectivelyEnabled(settings) || preferredBingeGroup != null)
        val evaluation = if (enabled) StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = state.groups.flatMap { it.streams }, mode = settings.streamAutoPlayMode,
            regexPattern = settings.streamAutoPlayRegex, source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(), selectedAddons = emptySet(), selectedPlugins = emptySet(),
            preferredBingeGroup = preferredBingeGroup, preferBingeGroupInSelection = preferredBingeGroup != null,
            bingeGroupOnly = settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL,
            debridEnabled = debridEnabled, activeResolverProviderId = activeResolverProviderId,
        ) else null
        return state.copy(isAnyLoading = false, autoPlayDecided = true,
            autoPlayStream = evaluation?.stream, autoPlayCandidates = evaluation?.readyStreams.orEmpty(),
            isDirectAutoPlayFlow = evaluation?.stream != null, showDirectAutoPlayOverlay = evaluation?.stream != null)
    }
}
