package com.nuvio.app.core.network

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.jan.supabase.auth.auth

object SyncBackendRepository {
    private val log = Logger.withTag("SyncBackendRepository")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(SyncBackendState())
    val state: StateFlow<SyncBackendState> = _state.asStateFlow()

    val selectedBackend: SyncBackendConfig
        get() {
            ensureLoaded()
            return _state.value.selectedBackend
        }

    fun ensureLoaded() {
        if (_state.value.isLoaded) return

        val storedSelection = SyncBackendStorage.loadSelectionPayload()
            ?.takeIf { it.isNotBlank() }
            ?.let { payload ->
                runCatching { json.decodeFromString<StoredSyncBackendSelection>(payload) }
                    .onFailure { error -> log.w(error) { "Failed to parse stored sync backend selection" } }
                    .getOrNull()
            }

        val storedManualDebugOverride = storedSelection?.manualDebugOverride == true
        val backend = if (storedManualDebugOverride && !AppFeaturePolicy.debugBackendSwitcherEnabled) {
            SyncBackendDefaults.hosted()
        } else {
            storedSelection
                ?.let { selection ->
                    selection.backendId.ifBlank { selection.backend?.id.orEmpty() }
                }
                ?.let(SyncBackendDefaults::byId)
                ?: SyncBackendDefaults.hosted()
        }

        _state.value = SyncBackendState(
            selectedBackend = backend,
            appliedRevision = if (storedManualDebugOverride && !AppFeaturePolicy.debugBackendSwitcherEnabled) {
                ""
            } else {
                storedSelection?.appliedRevision.orEmpty()
            },
            isLoaded = true,
            isManualDebugOverride = storedManualDebugOverride && AppFeaturePolicy.debugBackendSwitcherEnabled,
        )
    }

    suspend fun refreshFromManifest(): SyncBackendRefreshResult {
        ensureLoaded()

        if (_state.value.isManualDebugOverride && AppFeaturePolicy.debugBackendSwitcherEnabled) {
            return SyncBackendRefreshResult.Unchanged
        }

        val manifestUrl = SyncBackendBootstrapConfig.SWITCH_MANIFEST_URL.trim()
        if (manifestUrl.isBlank()) {
            return SyncBackendRefreshResult.NotConfigured
        }

        val manifest = runCatching {
            json.decodeFromString<SyncBackendManifest>(
                fetchSyncBackendManifestText(manifestUrl),
            )
        }.onFailure { error ->
            val message = error.message ?: "Failed to fetch sync backend manifest"
            log.w(error) { message }
            _state.value = _state.value.copy(lastManifestError = message)
        }.getOrNull() ?: return SyncBackendRefreshResult.Failed(
            _state.value.lastManifestError ?: "Failed to fetch sync backend manifest",
        )

        // B24 — resolve this client's effective v2 rollout from the manifest cohort (percent-by-account
        // + platform) and publish it into the core-owned signal the IPTV feature reads. Cohorting is a
        // client-side feature-flag decision (the backend contract enforces safety regardless), so it is
        // evaluated here against this client's own account id + platform. Null mode leaves the build
        // default. A missing account (not signed in yet) buckets out of an enabled cohort until sign-in.
        PlaylistSyncRolloutSignal.raw = manifest.iptvPlaylistV2?.let { rawMode ->
            PlaylistV2CohortPolicy.resolveMode(
                rawMode = rawMode,
                percent = manifest.iptvPlaylistV2Percent,
                platforms = manifest.iptvPlaylistV2Platforms,
                userId = runCatching { SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id }.getOrNull(),
                platform = if (com.nuvio.app.isIos) "ios" else "android",
            )
        }

        val targetBackend = manifest.backendConfigForActiveBackend()
            ?: return SyncBackendRefreshResult.Failed("Sync backend manifest is invalid")
        val revision = manifest.revision.trim()
        val currentBackend = _state.value.selectedBackend

        if (currentBackend.hasSameConnectionIdentity(targetBackend)) {
            saveSelection(targetBackend, revision)
            return SyncBackendRefreshResult.Unchanged
        }

        if (!manifest.forceLogoutOnChange) {
            saveSelection(targetBackend, revision)
            return SyncBackendRefreshResult.Applied(targetBackend, revision)
        }

        return SyncBackendRefreshResult.RequiresLogout(
            currentBackend = currentBackend,
            targetBackend = targetBackend,
            revision = revision,
            forceLogout = true,
        )
    }

    fun applyBackendAfterLogout(
        backend: SyncBackendConfig,
        revision: String,
    ): SyncBackendConfig {
        val normalizedBackend = backend.normalized()
        saveSelection(normalizedBackend, revision, manualDebugOverride = false)
        return normalizedBackend
    }

    fun debugSelectableBackends(): List<SyncBackendConfig> =
        listOf(SyncBackendDefaults.hosted(), SyncBackendDefaults.nuvio())
            .filter { backend -> backend.isUsableClientConfig() }

    fun applyDebugBackendAfterLogout(backend: SyncBackendConfig): SyncBackendConfig? {
        if (!AppFeaturePolicy.debugBackendSwitcherEnabled) return null

        val normalizedBackend = backend.normalized()
            .takeIf { it.isUsableClientConfig() }
            ?: return null

        saveSelection(
            backend = normalizedBackend,
            revision = DEBUG_MANUAL_REVISION,
            manualDebugOverride = true,
        )
        return normalizedBackend
    }

    private fun saveSelection(
        backend: SyncBackendConfig,
        revision: String,
        manualDebugOverride: Boolean = false,
    ) {
        val normalizedBackend = backend.normalized()
        val payload = json.encodeToString(
            StoredSyncBackendSelection(
                backendId = normalizedBackend.id,
                appliedRevision = revision,
                manualDebugOverride = manualDebugOverride,
            ),
        )
        SyncBackendStorage.saveSelectionPayload(payload)
        _state.value = SyncBackendState(
            selectedBackend = normalizedBackend,
            appliedRevision = revision,
            isLoaded = true,
            isManualDebugOverride = manualDebugOverride,
        )
    }

    private const val DEBUG_MANUAL_REVISION = "debug-manual"
}
