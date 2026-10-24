package com.tuvora.tvos.screens

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.isLocalOnly
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.sources.DeviceSourceDescriptor
import com.nuvio.app.core.sources.DeviceSources
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.streams.StreamsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

/** One process owner for source policy, cache invalidation and foreground refresh; no screen polls. */
object TvDeviceSources {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(TvDeviceSourceState())
    val state: StateFlow<TvDeviceSourceState> = mutable.asStateFlow()
    private var started = false

    internal fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(AuthRepository.state, ProfileRepository.state, com.tuvora.tvos.app.TvAppLifecycle.screen) { auth, profiles, screen ->
                if (auth == AuthState.Loading || screen != com.tuvora.tvos.app.TvGateScreen.Main) null
                else Triple(auth.userId, auth.isLocalOnly, profiles.activeProfile?.profileIndex ?: 1)
            }.distinctUntilChanged().collectLatest { key ->
                if (key == null) { mutable.value = TvDeviceSourceState(); return@collectLatest }
                val (_, localOnly, profile) = key
                val engine = DeviceSources.engine()
                DeviceSources.activate()
                coroutineScope {
                    launch {
                        var previousEpoch: Long? = null
                        engine.state.collect { view ->
                            if (previousEpoch != view.availabilityEpoch) {
                                previousEpoch = view.availabilityEpoch
                                MetaDetailsRepository.clear()
                                SearchRepository.clear()
                                StreamsRepository.clear()
                                HomeRepository.clear()
                                AddonRepository.onDeviceSourcesChanged()
                                ServerRepository.onDeviceSourcesChanged()
                                IptvCatalogAccess.catalog.devicePreferencesChanged()
                            }
                            mutable.value = TvDeviceSourcePresentation.state(view, profile, localOnly)
                        }
                    }
                    launch {
                        combine(engine.state.map { it.mode }.distinctUntilChanged(), AddonRepository.uiState, ServerRepository.uiState, IptvCatalogAccess.catalog.devicePreferenceSources) { mode, addons, servers, iptv ->
                            mode to Triple(addons.addons, servers.connections, iptv)
                        }.collect { (mode, repositories) ->
                            val (addons, servers, iptv) = repositories
                            if (mode == "local") {
                                val old = engine.state.value.disk.sources.values.flatten()
                                fun source(kind: String, ref: String, name: String, enabled: Boolean) = DeviceSourceDescriptor(
                                    old.firstOrNull { it.kind == kind && it.sourceRef == ref }?.sourceId ?: Uuid.random().toString(),
                                    kind, ref, name, enabled, profile)
                                engine.localSources(profile, addons.map { source("addon", it.manifestUrl, it.displayTitle, it.enabled) } +
                                    servers.map { source("media_server", "${it.providerId}|${it.remoteServerId}|${it.remoteUserId}", it.name, it.enabled) } +
                                    iptv.map { source("iptv", it.sourceRef, it.name, it.accountEnabled) })
                            }
                        }
                    }
                    launch {
                        AppForegroundMonitor.events().collectLatest { visibility ->
                            if (visibility == AppVisibility.Foreground) engine.refresh(profile)
                        }
                    }
                }
            }
        }
    }

    suspend fun refresh(): String? = action { DeviceSources.refresh() }

    suspend fun setEnabled(sourceId: String, enabled: Boolean): String? = action {
        val engine = DeviceSources.engine()
        val profile = ProfileRepository.activeProfileId
        engine.setChoice(profile, sourceId, enabled)
        engine.refresh(profile, refreshSources = false, forceDelta = false)
    }

    suspend fun resolve(sourceId: String, applyMine: Boolean): String? = action {
        val engine = DeviceSources.engine()
        val profile = ProfileRepository.activeProfileId
        engine.resolve(profile, sourceId, applyMine)
        if (applyMine) engine.refresh(profile, refreshSources = false, forceDelta = false)
    }

    suspend fun select(label: String, setupId: String?, copy: Boolean): String? = action {
        val engine = DeviceSources.engine()
        val existing = setupId?.let { id -> engine.state.value.setups.firstOrNull { it.id == id } ?: error("Setup no longer available") }
        val profile = ProfileRepository.activeProfileId
        if (existing == null && engine.state.value.disk.binding?.registration != null) engine.refresh(profile)
        else engine.select(label, existing, copy, profile)
        if (engine.state.value.ready) SyncManager.pullAllForProfile(profile)
    }

    suspend fun startLocal(): String? = action { DeviceSources.engine().startLocal() }

    private suspend fun action(block: suspend () -> Unit): String? = try {
        block(); null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        "Could not save your choice. Please retry."
    }
}
