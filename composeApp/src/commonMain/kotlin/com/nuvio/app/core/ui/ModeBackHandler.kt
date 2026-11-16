package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

/**
 * Back for an in-screen MODE (the IPTV hub search), as each platform's user expects to leave it:
 * Android's system Back (the activity's back dispatcher), the iPhone's left-edge swipe (Compose
 * Multiplatform's back gesture on the navigation-event dispatcher). Kept apart from [PlatformBackHandler],
 * which stays a no-op on iOS so the many screen-level handlers do not start catching the swipe at once.
 */
@Composable
expect fun ModeBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
