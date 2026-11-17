package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.nuvio.app.core.analytics.LiveReconnectReason
import com.nuvio.app.core.analytics.LiveReconnectStatus
import com.nuvio.app.core.ui.NuvioTokens
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_live_reconnect_reason_connection_limit
import nuvio.composeapp.generated.resources.player_live_reconnect_reason_provider_firewall
import nuvio.composeapp.generated.resources.player_live_reconnect_reason_provider_server_error
import nuvio.composeapp.generated.resources.player_live_reconnect_reason_stream_not_found
import nuvio.composeapp.generated.resources.player_live_reconnect_reason_stream_stopped
import nuvio.composeapp.generated.resources.player_live_reconnect_reason_unreachable
import nuvio.composeapp.generated.resources.player_live_reconnecting_attempt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LiveReconnectReason.resource(): StringResource = when (this) {
    LiveReconnectReason.PROVIDER_FIREWALL -> Res.string.player_live_reconnect_reason_provider_firewall
    LiveReconnectReason.CONNECTION_LIMIT -> Res.string.player_live_reconnect_reason_connection_limit
    LiveReconnectReason.STREAM_NOT_FOUND -> Res.string.player_live_reconnect_reason_stream_not_found
    LiveReconnectReason.PROVIDER_SERVER_ERROR -> Res.string.player_live_reconnect_reason_provider_server_error
    LiveReconnectReason.UNREACHABLE -> Res.string.player_live_reconnect_reason_unreachable
    LiveReconnectReason.STREAM_STOPPED -> Res.string.player_live_reconnect_reason_stream_stopped
}

/**
 * "Reconnecting… (attempt N of M)" over the reason, shown while the live reconnect ladder runs. Pure display:
 * the state comes from the surface's [com.nuvio.app.core.analytics.LivePlaybackReconnector.status].
 */
@Composable
fun LiveReconnectStatusLine(status: LiveReconnectStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(NuvioTokens.Radius.md))
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.player_live_reconnecting_attempt, status.attempt, status.maxAttempts),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(status.reason.resource()),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}
