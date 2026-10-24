package com.nuvio.app

import com.nuvio.app.features.auth.AccountSessionPrompts
import com.nuvio.app.features.profiles.ProfileSwitchController
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileEditScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSelectionScreen
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import com.nuvio.app.navigation.AppRoute

private enum class AppGateScreen {
    Loading,
    Auth,
    ProfileSelection,
    ProfileSwitching,
    ProfileEdit,
    Main,
}

@Composable
internal fun AppGate(
    initialTab: AppScreenTab,
    initialRoute: AppRoute,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    ownsAppRuntime: Boolean,
    bypassAppGate: Boolean,
    renderMainContent: Boolean,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)?,
    onGoBack: (() -> Unit)?,
    onReplace: ((AppRoute) -> Unit)?,
    onActivate: ((AppScreenTab) -> Unit)?,
    onAppReady: ((Boolean) -> Unit)?,
    onMainContentMountChanged: ((Boolean) -> Unit)?,
    onMainContentVisibleChanged: ((Boolean) -> Unit)?,
    onTabTitles: ((home: String, search: String, library: String, iptv: String, sports: String, profile: String, switchProfile: String, addProfile: String) -> Unit)?,
    nativeProfileSwitcherController: NativeProfileSwitcherController?,
    appGateController: AppGateController?,
) {
    if (bypassAppGate) {
        MainAppContent(
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            showLaunchOverlay = appGateController == null,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onTabTitles = onTabTitles,
            appGateController = appGateController,
            onRootContentReady = appGateController?.let { controller ->
                controller::reportMainContentReady
            },
            onSwitchProfile = appGateController?.let { controller ->
                controller::requestProfileSelection
            } ?: {},
        )
        return
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        AuthRepository.initialize()
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        NetworkStatusRepository.ensureStarted()
        MemberAccessRepository.ensureStarted()
        ProfileRepository.loadCachedProfiles()
        AvatarRepository.fetchAvatars()
    }

    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileAvatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val networkStatusUiState by remember {
        NetworkStatusRepository.uiState
    }.collectAsStateWithLifecycle()

    LaunchedEffect(
        profileState.activeProfile?.profileIndex,
        profileState.activeProfile?.name,
        profileState.activeProfile?.avatarColorHex,
        profileState.activeProfile?.avatarId,
        profileState.activeProfile?.avatarUrl,
        profileAvatars,
    ) {
        val activeProfile = profileState.activeProfile
        val avatarItem = activeProfile?.avatarId?.let { avatarId ->
            profileAvatars.find { it.id == avatarId }
        }
        NativeTabBridge.publishProfileTabIcon(
            name = activeProfile?.name,
            avatarColorHex = activeProfile?.avatarColorHex,
            avatarImageUrl = activeProfile?.let { profileAvatarImageUrl(it, avatarItem) },
            avatarBackgroundColorHex = avatarItem?.bgColor,
        )
    }

    var gateScreen by rememberSaveable { mutableStateOf(AppGateScreen.Loading.name) }
    var editingProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var autoSkipProfileSelection by rememberSaveable { mutableStateOf(false) }
    var pendingProfileSwitch by remember { mutableStateOf<PendingProfileSwitch?>(null) }

    // Settings "Sign In" button (signed-out users stay in the app via cached profiles, so nothing
    // else ever routes back to the auth screen).
    val signInRequests by AuthRepository.signInRequests.collectAsStateWithLifecycle()
    LaunchedEffect(signInRequests) {
        if (signInRequests > 0 && authState !is AuthState.Authenticated) {
            gateScreen = AppGateScreen.Auth.name
        }
    }
    var profileSelectionLoading by rememberSaveable { mutableStateOf(false) }
    var profileSelectionTransitionActive by rememberSaveable { mutableStateOf(false) }
    var skipProfileSelectionEnterAnimation by remember { mutableStateOf(false) }
    var mainContentStarted by rememberSaveable { mutableStateOf(false) }
    val externalMainContentReady = if (!renderMainContent && appGateController != null) {
        val ready by appGateController.mainContentReady.collectAsStateWithLifecycle()
        ready
    } else {
        false
    }

    fun reportAppReady(ready: Boolean) {
        appGateController?.reportAppReady(ready)
        onAppReady?.invoke(ready)
    }

    LaunchedEffect(gateScreen, onAppReady) {
        if (gateScreen != AppGateScreen.Main.name) {
            reportAppReady(false)
        }
    }

    LaunchedEffect(gateScreen, renderMainContent, onMainContentMountChanged) {
        if (renderMainContent) return@LaunchedEffect
        when (gateScreen) {
            AppGateScreen.Main.name -> {
                mainContentStarted = true
                onMainContentMountChanged?.invoke(true)
            }
            AppGateScreen.Loading.name,
            AppGateScreen.Auth.name,
            -> {
                mainContentStarted = false
                appGateController?.reportMainContentReady(false)
                onMainContentMountChanged?.invoke(false)
            }
            else -> onMainContentMountChanged?.invoke(mainContentStarted)
        }
    }

    LaunchedEffect(appGateController, renderMainContent) {
        if (renderMainContent) return@LaunchedEffect
        appGateController?.profileSelectionRequests?.collect {
            autoSkipProfileSelection = false
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            skipProfileSelectionEnterAnimation = true
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.requestedManageProfiles?.collect {
            appGateController.requestProfileSelection()
        }
    }

    LaunchedEffect(externalMainContentReady, renderMainContent) {
        if (!renderMainContent && externalMainContentReady) {
            profileSelectionLoading = false
        }
    }

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        onMainContentVisibleChanged,
    ) {
        if (!renderMainContent) {
            onMainContentVisibleChanged?.invoke(
                gateScreen == AppGateScreen.Main.name && externalMainContentReady,
            )
        }
    }

    fun rememberedStartupProfile(profiles: List<NuvioProfile>): NuvioProfile? {
        val currentProfileState = ProfileRepository.state.value
        if (
            !currentProfileState.rememberLastProfileEnabled ||
            !currentProfileState.hasEverSelectedProfile
        ) {
            return null
        }

        return profiles
            .find { it.profileIndex == ProfileRepository.activeProfileId }
            ?.takeUnless { it.pinEnabled }
    }

    // Fork: every "pick a profile, then show Main" goes through the single-flight
    // ProfileSwitchController — the switch runs OFF the main thread under a mutex, warms the
    // profile-bound repos, then pulls; Main is revealed only after that awaited sequence, which also
    // keeps AddonRepository.onProfileChanged from racing (and wiping) a fast pull.
    fun requestProfileSwitch(profile: NuvioProfile, sync: Boolean) {
        // Do not replace LaunchedEffect's request while its reset/warm pipeline owns the lock.
        if (pendingProfileSwitch != null || ProfileSwitchController.switchingTo.value != null) return
        if (!renderMainContent) {
            appGateController?.beginContentReload()
        }
        autoSkipProfileSelection = false
        pendingProfileSwitch = PendingProfileSwitch(profile, sync)
        gateScreen = AppGateScreen.ProfileSwitching.name
    }

    LaunchedEffect(nativeProfileSwitcherController, appGateController, renderMainContent) {
        if (renderMainContent || appGateController == null) return@LaunchedEffect
        nativeProfileSwitcherController?.selectedProfileIndices?.collect { profileIndex ->
            if (profileIndex == ProfileRepository.state.value.activeProfile?.profileIndex) return@collect
            val profile = ProfileRepository.state.value.profiles
                .firstOrNull { it.profileIndex == profileIndex }
                ?: return@collect
            requestProfileSwitch(profile, sync = true)
        }
    }

    LaunchedEffect(gateScreen, pendingProfileSwitch) {
        // A switch request cleared while still parked on ProfileSwitching falls back to the loader.
        if (gateScreen == AppGateScreen.ProfileSwitching.name && pendingProfileSwitch == null) {
            gateScreen = AppGateScreen.Loading.name
        }
    }

    LaunchedEffect(pendingProfileSwitch) {
        val request = pendingProfileSwitch ?: return@LaunchedEffect
        runCatching {
            check(ProfileSwitchController.switch(request.profile.profileIndex, request.syncOnEnter)) {
                "A profile switch is already running"
            }
        }.onSuccess {
            pendingProfileSwitch = null
            autoSkipProfileSelection = false
            gateScreen = AppGateScreen.Main.name
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            pendingProfileSwitch = null
            profileSelectionLoading = false
            profileSelectionTransitionActive = false
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    fun enterProfileGate(profiles: List<NuvioProfile>, syncOnEnter: Boolean) {
        profileSelectionLoading = false
        profileSelectionTransitionActive = false
        if (profiles.isEmpty()) {
            autoSkipProfileSelection = true
            gateScreen = AppGateScreen.ProfileSelection.name
            return
        }

        rememberedStartupProfile(profiles)?.let { profile ->
            requestProfileSwitch(profile, sync = syncOnEnter)
            return
        }

        autoSkipProfileSelection = true
        if (profiles.size == 1) {
            val onlyProfile = profiles.first()
            if (onlyProfile.pinEnabled) {
                gateScreen = AppGateScreen.ProfileSelection.name
                return
            }
            requestProfileSwitch(onlyProfile, sync = syncOnEnter)
        } else {
            gateScreen = AppGateScreen.ProfileSelection.name
        }
    }

    LaunchedEffect(authState, networkStatusUiState.condition, profileState.profiles) {
        if (gateScreen == AppGateScreen.ProfileSwitching.name) return@LaunchedEffect
        val cachedProfiles = profileState.profiles
        val hasCachedProfileAccess =
            cachedProfiles.isNotEmpty() &&
                authState !is AuthState.Authenticated
        val allowCachedProfileAccess =
            hasCachedProfileAccess &&
                (
                    networkStatusUiState.condition != NetworkCondition.Online ||
                        gateScreen != AppGateScreen.Auth.name
                )

        when (authState) {
            is AuthState.Loading -> {
                if (hasCachedProfileAccess) {
                    enterProfileGate(cachedProfiles, syncOnEnter = false)
                } else {
                    gateScreen = AppGateScreen.Loading.name
                }
            }
            is AuthState.Unauthenticated -> {
                if (allowCachedProfileAccess) {
                    enterProfileGate(cachedProfiles, syncOnEnter = false)
                } else {
                    ProfileRepository.clearInMemory()
                    profileSelectionLoading = false
                    profileSelectionTransitionActive = false
                    gateScreen = AppGateScreen.Auth.name
                }
            }
            is AuthState.Authenticated -> {
                val authenticatedState = authState as AuthState.Authenticated
                ProfileRepository.ensureLoaded(authenticatedState.userId)
                // A fresh anonymous ("Continue Without Account") session has no profile; seed one
                // default local profile BEFORE the profile gate so it auto-skips into the app
                // instead of an empty "Who's watching?" grid. No-op for a signed-in account.
                ProfileRepository.ensureDefaultLocalProfile()
                if (gateScreen == AppGateScreen.Loading.name || gateScreen == AppGateScreen.Auth.name) {
                    enterProfileGate(ProfileRepository.state.value.profiles, syncOnEnter = true)
                }
            }
        }
    }

    LaunchedEffect((authState as? AuthState.Authenticated)?.userId) {
        val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
        ProfileRepository.ensureLoaded(authenticatedState.userId)
        ProfileRepository.pullProfiles()
    }

    LaunchedEffect(
        gateScreen,
        autoSkipProfileSelection,
        profileState.profiles,
        profileState.hasEverSelectedProfile,
        profileState.rememberLastProfileEnabled,
        profileState.activeProfile?.profileIndex,
        profileState.activeProfile?.pinEnabled,
    ) {
        if (
            autoSkipProfileSelection &&
            gateScreen == AppGateScreen.ProfileSelection.name
        ) {
            rememberedStartupProfile(profileState.profiles)?.let { profile ->
                requestProfileSwitch(profile, sync = authState is AuthState.Authenticated)
                return@LaunchedEffect
            }

            if (profileState.profiles.size != 1) return@LaunchedEffect

            val onlyProfile = profileState.profiles.first()
            if (onlyProfile.pinEnabled) return@LaunchedEffect

            requestProfileSwitch(onlyProfile, sync = authState is AuthState.Authenticated)
        }
    }

    val profileOverlayVisible =
        gateScreen == AppGateScreen.ProfileSelection.name || profileSelectionLoading
    val profileOverlayState = remember {
        MutableTransitionState(profileOverlayVisible)
    }
    profileOverlayState.targetState = profileOverlayVisible
    val launchOverlayVisible =
        !renderMainContent &&
            gateScreen == AppGateScreen.Main.name &&
            !externalMainContentReady
    val launchOverlayState = remember {
        MutableTransitionState(launchOverlayVisible)
    }
    launchOverlayState.targetState = launchOverlayVisible

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        profileSelectionLoading,
        profileOverlayState.currentState,
        profileOverlayState.isIdle,
        launchOverlayState.currentState,
        launchOverlayState.isIdle,
        onAppReady,
    ) {
        if (renderMainContent) return@LaunchedEffect
        val overlaysHidden =
            profileOverlayState.isIdle &&
                !profileOverlayState.currentState &&
                launchOverlayState.isIdle &&
                !launchOverlayState.currentState
        reportAppReady(
            gateScreen == AppGateScreen.Main.name &&
                externalMainContentReady &&
                !profileSelectionLoading &&
                overlaysHidden,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = gateScreen,
            label = "app_gate",
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                    .togetherWith(fadeOut(tween(250)))
            },
        ) { currentGate ->
            when (currentGate) {
                AppGateScreen.Loading.name,
                AppGateScreen.ProfileSwitching.name,
                -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator()
                    }
                }
                AppGateScreen.Auth.name -> {
                    AuthScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background)
                            .padding(LocalVerticalBarPadding.current),
                    )
                }
                AppGateScreen.ProfileSelection.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.nuvio.colors.background),
                    )
                }
                AppGateScreen.ProfileEdit.name -> {
                    PlatformBackHandler(enabled = gateScreen == AppGateScreen.ProfileEdit.name) {
                        gateScreen = AppGateScreen.ProfileSelection.name
                    }
                    ProfileEditScreen(
                        profile = editingProfile,
                        onBack = { gateScreen = AppGateScreen.ProfileSelection.name },
                        onSaved = { gateScreen = AppGateScreen.ProfileSelection.name },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppGateScreen.Main.name -> {
                    if (renderMainContent) {
                        MainAppContent(
                            initialTab = initialTab,
                            initialRoute = initialRoute,
                            useNativeNavigation = useNativeNavigation,
                            useNativeTabBar = useNativeTabBar,
                            useTabletFloatingTabBar = useTabletFloatingTabBar,
                            ownsAppRuntime = ownsAppRuntime,
                            showLaunchOverlay = !profileSelectionLoading,
                            onNavigate = onNavigate,
                            onGoBack = onGoBack,
                            onReplace = onReplace,
                            onActivate = onActivate,
                            onTabTitles = onTabTitles,
                            appGateController = appGateController,
                            onRootContentReady = { ready ->
                                if (ready) {
                                    profileSelectionLoading = false
                                }
                                onAppReady?.invoke(
                                    ready && gateScreen == AppGateScreen.Main.name,
                                )
                            },
                            onSwitchProfile = {
                                autoSkipProfileSelection = false
                                profileSelectionLoading = false
                                profileSelectionTransitionActive = false
                                skipProfileSelectionEnterAnimation = false
                                gateScreen = AppGateScreen.ProfileSelection.name
                            },
                        )
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = launchOverlayState,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppLaunchOverlay(
                profile = profileState.activeProfile ?: profileState.profiles.firstOrNull(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = profileOverlayState,
            enter = if (skipProfileSelectionEnterAnimation) {
                androidx.compose.animation.EnterTransition.None
            } else {
                fadeIn(tween(400))
            },
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(NuvioTokens.Z.dialog),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val onBack: (() -> Unit)? = if (!autoSkipProfileSelection) {
                    {
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.Main.name
                    }
                } else {
                    null
                }
                PlatformBackHandler(
                    enabled = gateScreen == AppGateScreen.ProfileSelection.name && !profileSelectionLoading,
                ) {
                    onBack?.invoke()
                }
                ProfileSelectionScreen(
                    onProfileSelected = { profile ->
                        if (
                            !profileSelectionLoading &&
                            (autoSkipProfileSelection || profile.profileIndex != ProfileRepository.state.value.activeProfile?.profileIndex)
                        ) {
                            profileSelectionLoading = true
                            profileSelectionTransitionActive = true
                            skipProfileSelectionEnterAnimation = false
                            requestProfileSwitch(
                                profile = profile,
                                sync = authState is AuthState.Authenticated,
                            )
                            if (!renderMainContent) {
                                onActivate?.invoke(AppScreenTab.Home)
                            }
                        }
                    },
                    onEditProfile = { profile ->
                        editingProfile = profile
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    onAddProfile = {
                        editingProfile = null
                        skipProfileSelectionEnterAnimation = false
                        gateScreen = AppGateScreen.ProfileEdit.name
                    },
                    interactionEnabled = !profileSelectionLoading,
                    onBack = onBack,
                    activeProfileIndex = if (autoSkipProfileSelection) null else profileState.activeProfile?.profileIndex,
                    contentVisible = !profileSelectionTransitionActive,
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = profileSelectionTransitionActive,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppLoadingContent(modifier = Modifier.fillMaxSize())
                }
            }
        }

        // D1: "signed out, data kept — sign in again" and "different account over kept data" prompts.
        if (ownsAppRuntime) AccountSessionPrompts()
    }
}

private data class PendingProfileSwitch(
    val profile: NuvioProfile,
    val syncOnEnter: Boolean,
)
