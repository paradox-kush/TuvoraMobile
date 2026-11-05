package com.nuvio.app

import androidx.compose.runtime.Composable
import com.nuvio.app.core.contracts.RecordingPlayback
import com.nuvio.app.core.contracts.RecordingsContentAccess
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.RecordingsRoute

@Composable
internal fun RecordingsDestination(
    route: RecordingsRoute,
    navController: NuvioNavigator,
    onPlay: (RecordingPlayback) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    RecordingsContentAccess.current()?.Render(onBack = onBack, onPlay = onPlay)
}
