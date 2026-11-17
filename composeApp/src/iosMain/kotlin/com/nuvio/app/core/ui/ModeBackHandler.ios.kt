package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * F6: the iPhone's left-edge swipe-back. ComposeUIViewController feeds that gesture into the
 * navigation-event dispatcher (its back gesture is on by default); a handler only arms it while
 * [enabled], so the swipe stays inert when no mode is open.
 */
@Composable
actual fun ModeBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}
