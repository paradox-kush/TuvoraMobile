package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.TabsRoute

fun disposeRoute(route: AppRoute) {
    disposeRouteResources(route)
}

@OptIn(ExperimentalCoilApi::class)
@Composable
@Preview
fun App(
    initialTab: AppScreenTab = AppScreenTab.Home,
    initialRoute: AppRoute = TabsRoute,
    useNativeNavigation: Boolean = false,
    useNativeTabBar: Boolean = false,
    useTabletFloatingTabBar: Boolean = false,
    ownsAppRuntime: Boolean = true,
    bypassAppGate: Boolean = false,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)? = null,
    onGoBack: (() -> Unit)? = null,
    onReplace: ((AppRoute) -> Unit)? = null,
    onActivate: ((AppScreenTab) -> Unit)? = null,
    onAppReady: ((Boolean) -> Unit)? = null,
    onTabTitles: ((home: String, search: String, library: String, iptv: String, sports: String, profile: String, switchProfile: String, addProfile: String) -> Unit)? = null,
    nativeProfileSwitcherController: NativeProfileSwitcherController? = null,
    appGateController: AppGateController? = null,
) {
    AppEnvironment {
        AppGate(
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            bypassAppGate = bypassAppGate,
            renderMainContent = true,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onAppReady = onAppReady,
            onMainContentMountChanged = null,
            onMainContentVisibleChanged = null,
            onTabTitles = onTabTitles,
            nativeProfileSwitcherController = nativeProfileSwitcherController,
            appGateController = appGateController,
        )
    }
}

@Composable
internal fun AppEnvironment(content: @Composable () -> Unit) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components {
                add(SvgDecoder.Factory())
                add(
                    coil3.network.ktor3.KtorNetworkFetcherFactory(
                        cacheStrategy = { coil3.network.cachecontrol.CacheControlCacheStrategy() },
                    ),
                )
            }
            // TEMPORARY field diagnosis (HubTrace): is a poster request even ISSUED, does it hit
            // the memory/disk cache, how long does the network leg take, and does it fail? No-op
            // unless HubTrace.enabled (debug builds only).
            .eventListener(object : coil3.EventListener() {
                private val startedAt = mutableMapOf<String, Long>()
                private fun key(request: coil3.request.ImageRequest) = request.data.toString().takeLast(60)
                override fun onStart(request: coil3.request.ImageRequest) {
                    if (!com.nuvio.app.core.diag.HubTrace.enabled) return
                    startedAt[key(request)] = com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs()
                    com.nuvio.app.core.diag.HubTrace.log("image", "start") { key(request) }
                }
                override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) {
                    if (!com.nuvio.app.core.diag.HubTrace.enabled) return
                    val k = key(request)
                    val t0 = startedAt.remove(k)
                    com.nuvio.app.core.diag.HubTrace.log("image", "success") {
                        "src=${result.dataSource} took=${t0?.let { com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() - it }}ms $k"
                    }
                }
                override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) {
                    if (!com.nuvio.app.core.diag.HubTrace.enabled) return
                    val k = key(request)
                    val t0 = startedAt.remove(k)
                    com.nuvio.app.core.diag.HubTrace.log("image", "ERROR") {
                        "after=${t0?.let { com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() - it }}ms err=${result.throwable::class.simpleName}: ${result.throwable.message?.take(120)} $k"
                    }
                }
                override fun onCancel(request: coil3.request.ImageRequest) {
                    if (!com.nuvio.app.core.diag.HubTrace.enabled) return
                    com.nuvio.app.core.diag.HubTrace.log("image", "cancel") { key(request) }
                }
            })
            .configurePlatformImageLoader(context)
            .build()
    }
    val selectedTheme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsStateWithLifecycle()
    val amoledEnabled by remember {
        ThemeSettingsRepository.amoledEnabled
    }.collectAsStateWithLifecycle()

    NuvioTheme(appTheme = selectedTheme, amoled = amoledEnabled) {
        content()
    }
}
