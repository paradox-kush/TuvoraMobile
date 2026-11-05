package com.nuvio.app.core.contracts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.LiveReplayLaunch
import kotlinx.coroutines.flow.Flow

/**
 * Firewall ports for the three fork tab/route screens that App.kt hosts (IPTV hub, Sports hub, the
 * docked Live TV screen). App.kt owns the navigation and supplies the callbacks; the fork provides
 * the screen. Slot-inversion (as HomeSportsSection): App.kt never names the fork composables.
 * No-op default (null section) renders nothing — correct when the feature is absent.
 */
interface IptvHubContent {
    @Composable
    fun Render(
        modifier: Modifier,
        onPosterClick: (MetaPreview) -> Unit,
        onPlayLiveChannel: (String) -> Unit,
        onFavoriteLiveChannel: (String) -> Unit,
        onAddProvider: () -> Unit,
        scrollToTopRequests: Flow<Unit>,
        /** The empty screen's "I have a setup code" (Step 2). */
        onEnterSetupCode: () -> Unit,
    )
}

interface SportsHubContent {
    @Composable
    fun Render(
        modifier: Modifier,
        onPlayChannel: (String) -> Unit,
        onAddPlaylist: () -> Unit,
        onOpenRecording: (String) -> Unit,
        onPlayReplay: (SportsReplay) -> Unit,
    )
}

interface LiveTvContent {
    @Composable
    fun Render(
        initialContentId: String,
        initialTitle: String,
        initialLogo: String?,
        initialReplay: LiveReplayLaunch?,
        onBack: () -> Unit,
        onFavoriteChannel: (String) -> Unit,
        /** The programme sheet's "View recordings" after something was scheduled. */
        onOpenRecordings: () -> Unit,
        modifier: Modifier,
    )
}

/** A finished recording handed to the player: a local file, played directly (no server in between). */
data class RecordingPlayback(
    val recordingId: String,
    val title: String,
    val subtitle: String?,
    val channelName: String,
    val fileUri: String,
)

/** The Recordings page. Null (no registration) means this build has no recordings and the entry points hide. */
interface RecordingsContent {
    @Composable
    fun Render(
        onBack: () -> Unit,
        onPlay: (RecordingPlayback) -> Unit,
    )
}

object IptvHubContentAccess {
    private var content: IptvHubContent? = null
    fun register(c: IptvHubContent) { content = c }
    fun current(): IptvHubContent? = content
    fun resetForTest() { content = null }
}

object SportsHubContentAccess {
    private var content: SportsHubContent? = null
    fun register(c: SportsHubContent) { content = c }
    fun current(): SportsHubContent? = content
    fun resetForTest() { content = null }
}

object LiveTvContentAccess {
    private var content: LiveTvContent? = null
    fun register(c: LiveTvContent) { content = c }
    fun current(): LiveTvContent? = content
    fun resetForTest() { content = null }
}

object RecordingsContentAccess {
    private var content: RecordingsContent? = null
    fun register(c: RecordingsContent) { content = c }
    fun current(): RecordingsContent? = content
    fun resetForTest() { content = null }
}
