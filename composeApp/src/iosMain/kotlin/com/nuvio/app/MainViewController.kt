package com.nuvio.app

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.analytics.AnalyticsSink
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTierPolicy
import com.nuvio.app.features.common.lifecycle.FeatureRegistry
import com.nuvio.app.navigation.AppRoute
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIColor
import platform.UIKit.UIViewController

private val nuvioBackgroundColor = UIColor(red = 0.051, green = 0.051, blue = 0.051, alpha = 1.0)

@Suppress("unused")
fun MainViewController(): UIViewController = nuvioComposeViewController {
    App()
}

@Suppress("unused")
fun MainViewController(
    initialTabName: String,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
    onAppReady: (Boolean) -> Unit,
    onTabTitles: (String, String, String, String, String, String, String, String) -> Unit,
    nativeProfileSwitcherController: NativeProfileSwitcherController,
): UIViewController {
    val initialTab = AppScreenTab.fromName(initialTabName)
    return nuvioComposeViewController {
        App(
            initialTab = initialTab,
            useNativeNavigation = true,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = initialTab == AppScreenTab.Home,
            bypassAppGate = initialTab != AppScreenTab.Home,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = { tab -> onActivate(tab.name) },
            onAppReady = onAppReady,
            onTabTitles = onTabTitles,
            nativeProfileSwitcherController = nativeProfileSwitcherController,
        )
    }
}

@Suppress("unused")
fun ScreenViewController(
    route: AppRoute,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
): UIViewController = nuvioComposeViewController {
    App(
        initialRoute = route,
        useNativeNavigation = true,
        ownsAppRuntime = false,
        bypassAppGate = true,
        onNavigate = onNavigate,
        onGoBack = onGoBack,
        onReplace = onReplace,
        onActivate = { tab -> onActivate(tab.name) },
    )
}

private fun nuvioComposeViewController(
    content: @androidx.compose.runtime.Composable () -> Unit,
): UIViewController {
    ensureIosRuntimeBootstrapped()
    return ComposeUIViewController(
        configure = { onFocusBehavior = OnFocusBehavior.DoNothing },
        content = content,
    ).apply {
        view.backgroundColor = nuvioBackgroundColor
    }
}

/**
 * iOS process bootstrap — the Kotlin analog of NuvioApplication.onCreate. Swift's iOSApp.init
 * wires only analytics and the memory-pressure source, so registerFeatureContributions() was
 * never called on iOS and every feature port sat unregistered (IptvCatalog.current would throw).
 * Runs at the single ComposeUIViewController chokepoint, before any composition reads a port.
 */
private fun ensureIosRuntimeBootstrapped() {
    if (!FeatureRegistry.isInitialized) {
        installUnhandledExceptionReporter()
        registerFeatureContributions()
    }
    // Static half of the iOS memory probe (ProcessInfo.physicalMemory); the dynamic pressure
    // half is DispatchSource in iOSApp.swift feeding AppMemory.onPressure/onRelax.
    MemoryPortAccess.current().setBaseTier(
        MemoryTierPolicy.iosTier(NSProcessInfo.processInfo.physicalMemory.toLong()),
    )
}

/**
 * Route escaped Kotlin exceptions on iOS to PostHog instead of a silent abort(). Without this hook,
 * an unhandled Kotlin exception on the main thread goes straight to the Kotlin/Native terminate
 * handler → abort(), and never reaches PostHog (its Swift-level autocapture doesn't see K/N throws).
 *
 * The known-benign Compose-Multiplatform iOS overlay race (JetBrains/compose-multiplatform#4916 —
 * "ComposeScene is closed": a Popup/menu/sheet scene is disposed while a trailing setContent /
 * pointer event is still routed to it) is SWALLOWED — the scene is being torn down anyway, so
 * continuing is safe and avoids a crash the app can't otherwise prevent on CMP 1.11.1. Every other
 * exception is reported and then re-raised through the previous hook, preserving crash semantics.
 */
@OptIn(ExperimentalNativeApi::class)
private fun installUnhandledExceptionReporter() {
    var previous: ((Throwable) -> Unit)? = null
    previous = setUnhandledExceptionHook { throwable ->
        runCatching {
            AnalyticsSink.capture(
                "app_uncaught_exception_ios",
                mapOf(
                    "exception" to (throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable"),
                    "message" to (throwable.message ?: ""),
                    "stack" to throwable.stackTraceToString().take(4000),
                ),
            )
        }
        val benignSceneRace = throwable is IllegalStateException &&
            throwable.message?.contains("ComposeScene is closed") == true
        if (!benignSceneRace) {
            previous?.invoke(throwable)
        }
    }
}
