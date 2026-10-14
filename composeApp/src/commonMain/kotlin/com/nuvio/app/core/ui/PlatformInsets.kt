package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal expect val nuvioPlatformExtraTopPadding: Dp
internal expect val nuvioPlatformExtraBottomPadding: Dp
internal expect val nuvioBottomNavigationExtraVerticalPadding: Dp
@Composable
internal expect fun nuvioBottomNavigationBarInsets(): WindowInsets

/** Physical display-safe top inset, excluding any enclosing native toolbar. */
@Composable
internal expect fun platformPhysicalTopInset(): Dp

internal val LocalNuvioBottomNavigationOverlayPadding = staticCompositionLocalOf { 0.dp }

/** Space below the status bar occupied by the native phone profile avatar. */
internal val LocalNuvioCornerProfileOverlayPadding = staticCompositionLocalOf { 0.dp }

/**
 * Height of a navigation bar drawn OVER the top of tab content (the tablet floating tab-bar pill),
 * status bar included; 0 when the tab bar is not at the top. Measured, so it changes once after
 * first layout — hence a dynamic local, not a static one.
 */
internal val LocalNuvioTopNavigationOverlayPadding = compositionLocalOf { 0.dp }

internal val LocalBottomInsetsConsumed = staticCompositionLocalOf { false }

/** CompositionLocal providing the shared [NuvioNavBarScrollState] so child screens can attach the nestedScrollConnection. */
val LocalNuvioNavBarScrollState = staticCompositionLocalOf<NuvioNavBarScrollState?> { null }

@Composable
internal fun nuvioSafeBottomPadding(extra: Dp = 0.dp): Dp {
	if (LocalBottomInsetsConsumed.current) return extra
	val navigationBarBottom = nuvioBottomNavigationBarInsets()
		.asPaddingValues()
		.calculateBottomPadding()
	return navigationBarBottom.coerceAtLeast(nuvioPlatformExtraBottomPadding) +
		LocalNuvioBottomNavigationOverlayPadding.current +
		extra
}
