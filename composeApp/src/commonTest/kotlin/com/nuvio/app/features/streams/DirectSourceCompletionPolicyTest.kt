package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.*

class DirectSourceCompletionPolicyTest {
    private val stream = StreamItem(name="1080p", url="stalker-deferred:test", addonId="xtream", addonName="IPTV")
    private val loaded = StreamsUiState(requestToken="request", groups=listOf(AddonStreamGroup("IPTV", "xtream", listOf(stream), false)), isAnyLoading=false)
    @Test fun aResolvedDirectSourceLaunchesWithoutAddonDiscoveryFilters() {
        val settings = PlayerSettingsUiState(streamAutoPlayMode=StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlaySource=StreamAutoPlaySource.INSTALLED_ADDONS_ONLY, streamAutoPlaySelectedAddons=setOf("other"))
        val result = DirectSourceCompletionPolicy.complete(loaded, settings, false)
        assertEquals(stream, result.autoPlayStream)
        assertEquals(listOf(stream), result.autoPlayCandidates)
        assertTrue(result.autoPlayDecided)
        assertTrue(result.showDirectAutoPlayOverlay)
        assertEquals("request", result.requestToken)
    }
    @Test fun manualSelectionShowsTheDirectSourceWithoutTheLoadingCover() {
        val settings = PlayerSettingsUiState(streamAutoPlayMode=StreamAutoPlayMode.FIRST_STREAM)
        val result = DirectSourceCompletionPolicy.complete(loaded, settings, true)
        assertNull(result.autoPlayStream)
        assertTrue(result.autoPlayDecided)
        assertFalse(result.shouldShowAutoPlayLoading("request", settings, true))
        assertEquals(loaded.groups, result.groups)
    }
    @Test fun aFailedResolutionSettlesAndRevealsTheError() {
        val settings = PlayerSettingsUiState(streamAutoPlayMode=StreamAutoPlayMode.FIRST_STREAM)
        val result = DirectSourceCompletionPolicy.complete(StreamsUiState(requestToken="request", emptyStateReason=StreamsEmptyStateReason.NoStreamsFound), settings, false)
        assertTrue(result.autoPlayDecided)
        assertFalse(result.shouldShowAutoPlayLoading("request", settings, false))
        assertEquals(StreamsEmptyStateReason.NoStreamsFound, result.emptyStateReason)
    }
    @Test fun regexThatMatchesNoDirectSourceShowsManualChoices() {
        val settings = PlayerSettingsUiState(streamAutoPlayMode=StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex="2160p")
        val result = DirectSourceCompletionPolicy.complete(loaded, settings, false)
        assertNull(result.autoPlayStream)
        assertFalse(result.showDirectAutoPlayOverlay)
        assertEquals(loaded.groups, result.groups)
    }
}
