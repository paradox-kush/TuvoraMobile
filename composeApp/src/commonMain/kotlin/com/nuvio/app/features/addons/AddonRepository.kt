package com.nuvio.app.features.addons

import com.nuvio.app.core.build.AppFeaturePolicy
import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.SyncSession
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var currentProfileId: Int = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()

    // UX71: the AddonSyncMerge baseline of the current profile, and whether a push/retry is on its way.
    private val lastSyncedUrls = MutableStateFlow<List<String>?>(null)
    private val syncInFlight = MutableStateFlow(false)
    private var retryJob: Job? = null

    /** Drives the Add-ons screen's "Not synced yet" marker (see [AddonSyncStatusPolicy]). */
    internal val syncStatus: StateFlow<AddonSyncStatus> = combine(
        uiState,
        lastSyncedUrls,
        syncInFlight,
    ) { state, lastSynced, inFlight ->
        AddonSyncStatusPolicy.status(
            local = dedupeManifestUrls(state.addons.map(ManagedAddon::manifestUrl)),
            lastSynced = lastSynced,
            hasAccount = SyncSession.hasAccount(),
            followsPrimaryProfile = isUsingPrimaryAddonsFromSecondaryProfile(),
            syncInFlight = inFlight,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AddonSyncStatus.NotApplicable)

    /**
     * The "Retry" behind "Not synced yet": runs the same three-way pull+merge as a sync cycle, which pushes the
     * device's unsynced edits up without overwriting what other devices added meanwhile.
     */
    internal fun retrySync() {
        if (retryJob?.isActive == true) return
        if (!SyncSession.canSync()) {
            log.d { "retrySync() — skipped, no usable session" }
            return
        }
        retryJob = scope.launch {
            syncInFlight.value = true
            try {
                pullFromServer(ProfileRepository.activeProfileId)
            } finally {
                syncInFlight.value = pushJobsByProfile.values.any { it.isActive }
            }
        }
    }

    private fun recordSynced(profileId: Int, urls: List<String>) {
        AddonStorage.saveSyncedAddonUrls(profileId, urls)
        if (profileId == currentProfileId) lastSyncedUrls.value = dedupeManifestUrls(urls)
    }

    private fun reloadLastSynced() {
        lastSyncedUrls.value = AddonStorage.loadSyncedAddonUrls(currentProfileId)?.let(::dedupeManifestUrls)
    }

    fun onDeviceSourcesChanged() {
        activeRefreshJobs.values.toList().forEach { it.cancel() }
        activeRefreshJobs.clear()
        _uiState.update { it.copy(availabilityRevision = it.availabilityRevision + 1, addons = it.addons.map { a -> a.copy(isRefreshing = false) }) }
        _uiState.value.addons.filter { it.availableOnDevice && it.manifest == null && !it.isRefreshing }.forEach { refreshAddon(it.manifestUrl) }
    }

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (initialized) return
        initialized = true
        currentProfileId = effectiveProfileId
        reloadLastSynced()
        log.d { "initialize() — loading local addons for profile $currentProfileId" }

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
        val enabledByUrl = loadLocalEnabledStates()
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        if (storedUrls.isEmpty()) return

        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(
            addons = storedUrls.map { manifestUrl ->
                existingByUrl[manifestUrl].toPendingAddon(
                    manifestUrl = manifestUrl,
                    enabled = enabledByUrl[manifestUrl],
                )
            },
        )

        storedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(manifestUrl)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        cancelActiveRefreshes()
        currentProfileId = effectiveProfileId
        initialized = false
        _uiState.value = AddonsUiState()
        reloadLastSynced()
    }

    fun clearLocalState() {
        cancelActiveRefreshes()
        pushJobsByProfile.values.forEach(Job::cancel)
        pushJobsByProfile.clear()
        currentProfileId = 1
        initialized = false
        _uiState.value = AddonsUiState()
        lastSyncedUrls.value = null
        syncInFlight.value = false
    }

    suspend fun pullFromServer(profileId: Int) {
        currentProfileId = resolveEffectiveProfileId(profileId)
        val pullProfileId = currentProfileId
        reloadLastSynced()
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized" }
        var mergePushNeeded = false
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", pullProfileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()

            val rowsByUrl = linkedMapOf<String, AddonRow>()
            rows.forEach { row ->
                val manifestUrl = ensureManifestSuffix(row.url)
                if (!rowsByUrl.containsKey(manifestUrl)) {
                    rowsByUrl[manifestUrl] = row.copy(url = manifestUrl)
                }
            }

            val remoteUrls = rowsByUrl.keys.toList()
            log.i { "pullFromServer() — server returned ${rows.size} addons" }
            remoteUrls.forEachIndexed { i, u -> log.d { "  server[$i]: $u" } }

            // Read the device's list from storage, not _uiState: a pull can land before
            // initialize() has loaded the profile, and an empty in-memory list would read as
            // "the user removed everything". Every edit persist()s, so storage is current.
            val merge = if (isUsingPrimaryAddonsFromSecondaryProfile()) {
                // This profile can't edit the primary's addons, so it has nothing of its own to merge.
                AddonSyncMerge.Outcome(urls = remoteUrls, pushNeeded = false)
            } else {
                AddonSyncMerge.merge(
                    local = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(pullProfileId)),
                    remote = remoteUrls,
                    lastSynced = AddonStorage.loadSyncedAddonUrls(pullProfileId)?.let(::dedupeManifestUrls),
                )
            }
            val urls = merge.urls
            if (merge.pushNeeded) {
                log.i { "pullFromServer() — local addon edits never reached the server; pushing the merged list (${urls.size} addons)" }
            }

            val localEnabledByUrl = loadLocalEnabledStates()
            val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
            _uiState.value = AddonsUiState(
                addons = urls.map { url ->
                    val row = rowsByUrl[url]
                    existingByUrl[url].toPendingAddon(
                        manifestUrl = url,
                        userSetName = row?.name?.takeIf { it.isNotBlank() },
                        enabled = row?.enabled ?: localEnabledByUrl[url],
                    )
                },
            )
            persist()
            urls.forEach { url ->
                val existing = existingByUrl[url]
                val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == url }
                if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                    refreshAddon(url)
                }
            }
            initialized = true
            if (merge.pushNeeded) {
                mergePushNeeded = true
            } else {
                recordSynced(pullProfileId, urls)
            }
            log.i { "pullFromServer() — applied ${urls.size} addons to state" }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "pullFromServer() — FAILED" }
        }
        if (mergePushNeeded) {
            // Awaited, not debounced: the sync pipeline runs the next steps against this list.
            // A failure leaves the last-synced list untouched, so the next pull merges again.
            runCatching { sendToServer(pullProfileId, pushItemsFor(_uiState.value.addons)) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    log.e(e) { "pullFromServer() — merge push FAILED; will retry on the next pull" }
                }
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default + com.nuvio.app.core.sources.AddonInstallationRequest(manifestUrl)) {
                val payload = fetchAddonResponseText(manifestUrl)
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                ).let { AddonSourcePolicy.manifestForBuild(it, AppFeaturePolicy.addonStreamSourcesEnabled) }
            }
        } catch (error: Throwable) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        persist()
        pushToServer()
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — $manifestUrl" }
        var changed = false
        _uiState.update { current ->
            val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
            changed = updatedAddons.size != current.addons.size
            if (changed) current.copy(addons = updatedAddons) else current
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var changed = false
        _uiState.update { current ->
            val addons = current.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                return@update current
            }

            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            reordered.add(toIndex, movingAddon)
            changed = true
            current.copy(addons = reordered)
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var shouldRefresh = false
        var changed = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        if (!changed) return
        persist()
        pushToServer()
        if (shouldRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    /** Fetches again only the enabled add-ons whose manifest failed; a no-op (no request) when none did. */
    fun retryFailedManifests() {
        AddonLoadRetryPolicy.manifestsToRetry(_uiState.value.addons).forEach { manifestUrl ->
            refreshAddon(manifestUrl = manifestUrl, forceRefresh = true)
        }
    }

    fun refreshAll() {
        _uiState.value.addons.filter { it.availableOnDevice }.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                forceRefresh = true,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        if (!com.nuvio.app.core.sources.DeviceSources.allowsAddon(manifestUrl)) return
        val existingJob = activeRefreshJobs[manifestUrl]
        if (existingJob?.isActive == true) return

        markRefreshing(manifestUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                suspend fun fetchManifest(force: Boolean) = runCatching {
                    val payload = fetchAddonResponseText(
                        url = manifestUrl,
                        forceRefresh = force,
                    )
                    AddonManifestParser.parse(
                        manifestUrl = manifestUrl,
                        payload = payload,
                    ).let { AddonSourcePolicy.manifestForBuild(it, AppFeaturePolicy.addonStreamSourcesEnabled) }
                }
                // A failed manifest used to stay failed until the app restarted (Home showed only
                // Continue Watching). Retry on a short bounded ladder while still "refreshing".
                var result = fetchManifest(forceRefresh)
                var retries = 0
                while (result.isFailure) {
                    val wait = AddonLoadRetryPolicy.delayBeforeRetry(retries++) ?: break
                    delay(wait)
                    result = fetchManifest(force = true)
                }

                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != manifestUrl) {
                                addon
                            } else {
                                result.fold(
                                    onSuccess = { manifest ->
                                        addon.copy(
                                            manifest = manifest,
                                            isRefreshing = false,
                                            errorMessage = null,
                                        )
                                    },
                                    onFailure = { error ->
                                        addon.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: getString(Res.string.addon_load_manifest_failed),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            } finally {
                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                    activeRefreshJobs.remove(manifestUrl)
                }
            }
        }
        activeRefreshJobs[manifestUrl] = refreshJob
    }

    private fun pushToServer() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        // Add/remove/reorder/enable all reach here, and the addon manager works fine
        // signed out — so without this the RPC goes out as `anon` and comes back
        // 42501, once per edit. Nothing is lost by refusing: the list was already
        // persist()ed locally, and the next pullFromServer() merges it up (AddonSyncMerge).
        if (!SyncSession.canPush()) {
            log.d { "pushToServer() — skipped, no signed-in session" }
            return
        }
        val profileId = currentProfileId
        val addons = pushItemsFor(_uiState.value.addons)
        pushJobsByProfile[profileId]?.cancel()
        syncInFlight.value = true
        var pushJob: Job? = null
        pushJob = scope.launch {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                sendToServer(profileId, addons)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Not retried here: the last-synced list is unchanged, so the next pull merges
                // this edit up instead of overwriting it.
                log.e(error) { "pushToServer() — FAILED" }
            } finally {
                if (pushJobsByProfile[profileId] === pushJob) {
                    pushJobsByProfile.remove(profileId)
                    syncInFlight.value = retryJob?.isActive == true
                }
            }
        }
        pushJobsByProfile[profileId] = pushJob
    }

    private fun pushItemsFor(addons: List<ManagedAddon>): List<AddonPushItem> =
        addons
            .distinctBy { it.manifestUrl }
            .mapIndexed { index, addon ->
                AddonPushItem(
                    url = addon.manifestUrl,
                    name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                    enabled = addon.enabled,
                    sortOrder = index,
                )
            }

    /** Full-replace push; on success records [addons] as the list this device and the server agree on. */
    private suspend fun sendToServer(profileId: Int, addons: List<AddonPushItem>) {
        log.d { "pushToServer() — profileId=$profileId, pushing ${addons.size} addons" }
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_addons", json.encodeToJsonElement(addons))
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
        recordSynced(profileId, addons.map { it.url })
        log.d { "pushToServer() — success" }
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist() {
        val addons = _uiState.value.addons
        AddonStorage.saveInstalledAddonUrls(
            currentProfileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            currentProfileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun loadLocalEnabledStates(): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(currentProfileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}
