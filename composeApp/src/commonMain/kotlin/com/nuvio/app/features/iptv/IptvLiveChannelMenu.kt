package com.nuvio.app.features.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.core.ui.LiveRecentActionTarget
import com.nuvio.app.core.ui.NuvioLiveChannelActionSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioToastPlacement
import com.nuvio.app.features.iptv.overlay.IptvChannelQuickActions
import com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy
import com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy.Action
import com.nuvio.app.features.iptv.overlay.IptvChannelQuickActionsPolicy.HideTarget
import com.nuvio.app.features.library.LibraryRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_channel_hidden_toast
import nuvio.composeapp.generated.resources.iptv_channel_hidden_toast_generic
import nuvio.composeapp.generated.resources.iptv_favourite_added_toast
import nuvio.composeapp.generated.resources.iptv_favourite_removed_toast
import nuvio.composeapp.generated.resources.iptv_toast_undo
import org.jetbrains.compose.resources.getString

/**
 * The long-press menu for a live channel, shared by the Live TV guide and the IPTV hub (UX36): one
 * gesture, one meaning. [hideTarget] null leaves Hide out (see [IptvChannelQuickActionsPolicy]).
 * A hide is confirmed with a bottom toast that names where hidden channels are managed and offers
 * Undo — the guide used to hide instantly and silently (UX73).
 *
 * Apple TV has its own SwiftUI context menu, so tvosCore excludes this file.
 */
@Composable
internal fun IptvLiveChannelMenu(
    channel: LiveRecentActionTarget,
    hideTarget: HideTarget?,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    /** F03: move a favourite (on a favourites row) or a pinned channel (in its group). */
    onMoveEarlier: (() -> Unit)? = null,
    onMoveLater: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val wasFavorite = LibraryRepository.isLocalSaved(channel.contentId, "tv")
    val actions = IptvChannelQuickActionsPolicy.menu(
        isFavorite = wasFavorite,
        hideTarget = hideTarget,
    )
    NuvioLiveChannelActionSheet(
        channel = channel,
        isFavorite = Action.REMOVE_FAVORITE in actions,
        // F03 (owner 2026-10-04): a long-press favourite toggle is confirmed with Undo, like a hide.
        onToggleFavorite = {
            val undo = LibraryRepository.captureLocalSavedUndo(channel.contentId, "tv")
            onToggleFavorite()
            scope.launch { confirmFavoriteToggled(channel.name, nowFavorite = !wasFavorite, undo = undo::undo) }
        },
        onMoveEarlier = onMoveEarlier,
        onMoveLater = onMoveLater,
        onHide = hideTarget?.takeIf { Action.HIDE in actions }?.let { target ->
            {
                IptvChannelQuickActions.hide(target)
                scope.launch { confirmHidden(target, channel.name) }
            }
        },
        onDismiss = onDismiss,
        // F14: assign this channel's guide by hand (TiviMate's "Assign EPG").
        onChooseGuide = if (com.nuvio.app.features.iptv.epg.GuideChannelPickerController.canOpen(channel.contentId)) {
            { com.nuvio.app.features.iptv.epg.GuideChannelPickerController.open(channel.contentId, channel.name) }
        } else {
            null
        },
    )
}

private suspend fun confirmHidden(target: HideTarget, channelName: String) {
    val playlistName = target.playlistId?.let { id ->
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == id }?.name
    }
    val message = if (playlistName.isNullOrBlank()) {
        getString(Res.string.iptv_channel_hidden_toast_generic, channelName)
    } else {
        getString(Res.string.iptv_channel_hidden_toast, channelName, playlistName)
    }
    NuvioToastController.show(
        message = message,
        durationMillis = UNDO_TOAST_MS,
        placement = NuvioToastPlacement.Bottom,
        actionLabel = getString(Res.string.iptv_toast_undo),
        onAction = { IptvChannelQuickActions.undoHide(target) },
    )
}

private suspend fun confirmFavoriteToggled(channelName: String, nowFavorite: Boolean, undo: () -> Unit) {
    NuvioToastController.show(
        message = getString(
            if (nowFavorite) Res.string.iptv_favourite_added_toast else Res.string.iptv_favourite_removed_toast,
            channelName,
        ),
        durationMillis = UNDO_TOAST_MS,
        placement = NuvioToastPlacement.Bottom,
        actionLabel = getString(Res.string.iptv_toast_undo),
        onAction = undo,
    )
}

private const val UNDO_TOAST_MS = 6_000L
