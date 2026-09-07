package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.isLocalOnly
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

@Serializable
private data class StoredProfilePayload(
    val userId: String,
    val activeProfileIndex: Int = 1,
    val hasEverSelectedProfile: Boolean = false,
    val rememberLastProfileEnabled: Boolean = false,
    val profiles: List<NuvioProfile> = emptyList(),
)

object ProfileRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profileSwitchMutex = Mutex()
    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private var activeProfileIndex: Int = 1
    private var loadedCacheForUserId: String? = null
    private var seedingDefaultProfile = false

    val activeProfileId: Int get() = activeProfileIndex

    fun setRememberLastProfileEnabled(enabled: Boolean) {
        if (_state.value.rememberLastProfileEnabled == enabled) return

        _state.value = _state.value.copy(rememberLastProfileEnabled = enabled)
        persist()
    }

    fun loadCachedProfiles(): Boolean {
        val stored = decodeStoredPayload() ?: return false
        loadedCacheForUserId = stored.userId
        applyStoredPayload(stored)
        ThemeSettingsRepository.onProfileChanged()
        return _state.value.profiles.isNotEmpty()
    }

    fun ensureLoaded(userId: String) {
        if (loadedCacheForUserId == userId && _state.value.isLoaded) return

        val stored = decodeStoredPayload()
        loadedCacheForUserId = userId
        if (stored == null) {
            _state.value = ProfileState()
            activeProfileIndex = 1
            return
        }

        if (stored.userId != userId) {
            _state.value = ProfileState()
            activeProfileIndex = 1
            return
        }

        applyStoredPayload(stored)
    }

    fun clearInMemory() {
        loadedCacheForUserId = null
        activeProfileIndex = 1
        _state.value = ProfileState()
    }

    suspend fun pullProfiles() {
        if (AuthRepository.state.value.isLocalOnly) {
            if (!_state.value.isLoaded) {
                _state.value = _state.value.copy(isLoaded = true)
            }
            return
        }
        // A signed-in account whose session has lapsed must not fire the RPC: postgrest would
        // send it as `anon` and Postgres answers 42501 (one of the launch-trio errors measured
        // on the backend). The catch below already ends in "mark loaded, keep state" for that
        // shape — this skips the doomed round trip and lands on the same outcome. The next
        // successful refresh (or sign-in) pulls for real.
        if (!com.nuvio.app.core.sync.SyncSession.canSync()) {
            if (!_state.value.isLoaded) {
                _state.value = _state.value.copy(isLoaded = true)
            }
            return
        }
        try {
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profiles")
            val profiles = result.decodeList<NuvioProfile>()
            _state.value = _state.value.copy(
                profiles = profiles.sortedBy { it.profileIndex },
                isLoaded = true,
                activeProfile = profiles.find { it.profileIndex == activeProfileIndex }
                    ?: profiles.firstOrNull(),
            )
            if (_state.value.activeProfile != null) {
                activeProfileIndex = _state.value.activeProfile!!.profileIndex
            }
            persist()
        } catch (e: Throwable) {
            if (AuthRepository.signOutIfSessionInvalid(e, "Profile pull")) return
            log.e(e) { "Failed to pull profiles" }
            if (!_state.value.isLoaded) {
                _state.value = _state.value.copy(isLoaded = true)
            }
        }
    }

    suspend fun switchToProfile(profileIndex: Int) {
        profileSwitchMutex.withLock {
            withContext(Dispatchers.Default) {
                selectProfile(profileIndex)
            }
        }
    }

    private fun selectProfile(profileIndex: Int) {
        activeProfileIndex = profileIndex
        val selectedProfile = _state.value.profiles.find { it.profileIndex == profileIndex }
        _state.value = _state.value.copy(
            activeProfile = selectedProfile,
            hasEverSelectedProfile = selectedProfile != null || _state.value.hasEverSelectedProfile,
        )
        persist()
        WatchedRepository.onProfileChanged(profileIndex)
        TrackingSettingsRepository.onProfileChanged()
        ensureTrackingProvidersRegistered()
        TrackingProviderRegistry.onProfileChanged()
        LibraryRepository.onProfileChanged(profileIndex)
        LibraryDisplaySettingsRepository.onProfileChanged()
        WatchProgressRepository.onProfileChanged(profileIndex)
        AddonRepository.onProfileChanged(profileIndex)
        if (com.nuvio.app.core.build.AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.onProfileChanged(profileIndex)
        }
        // Fork features (IPTV, Sports Centre) reload/drop their cross-profile state via the
        // participant registry — the switch stays free of any fork import (firewall). Order is the
        // registration order set in FeatureWiring.
        com.nuvio.app.core.contracts.ProfileChangeParticipants.all().forEach {
            it.onProfileChanged(profileIndex)
        }
        ThemeSettingsRepository.onProfileChanged()
        PosterCardStyleRepository.onProfileChanged()
        CardDepthStyleRepository.onProfileChanged()
        PlayerSettingsRepository.onProfileChanged()
        StreamBadgeSettingsRepository.onProfileChanged()
        P2pSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.onProfileChanged()
        HomeRepository.clear()
        MetaScreenSettingsRepository.onProfileChanged()
        ContinueWatchingPreferencesRepository.onProfileChanged()
        com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache.onProfileChanged()
        EpisodeReleaseNotificationsRepository.onProfileChanged()
        TmdbSettingsRepository.onProfileChanged()
        MdbListSettingsRepository.onProfileChanged()
        SearchHistoryRepository.onProfileChanged()
        SearchRepository.reset()
        CollectionRepository.onProfileChanged()
        CollectionMobileSettingsRepository.onProfileChanged()
        DownloadsRepository.onProfileChanged()
    }

    /**
     * Returns false when the change did not land, so callers can tell the user instead of assuming.
     *
     * This used to return Unit and swallow every failure into a log line, which is how a signed-out
     * client silently discarded profile edits for weeks: the RPC 42501'd, nothing persisted, and the
     * edit screen still closed as though it had saved.
     */
    suspend fun pushProfiles(profiles: List<ProfilePushPayload>): Boolean {
        if (AuthRepository.state.value.isLocalOnly) {
            applyPayloadsLocally(profiles)
            return true
        }
        return try {
            val params = buildJsonObject {
                put("p_client_max_profiles", MAX_PROFILES)
                put("p_profiles", json.encodeToJsonElement(profiles))
                putSyncOriginClientId()
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_profiles", params)
            pullProfiles()
            true
        } catch (e: Throwable) {
            // A session that just went invalid is still a failed save - the caller must not report
            // success just because the sign-out was handled.
            if (AuthRepository.signOutIfSessionInvalid(e, "Profile push")) return false
            log.e(e) { "Failed to push profiles" }
            false
        }
    }

    /**
     * The profile_index [createProfile] would claim next, or null when the account is full.
     *
     * Exposed because an avatar can be uploaded before the profile it belongs to exists — the object
     * path embeds the index, and a not-yet-created profile has none to embed.
     */
    fun nextFreeProfileIndex(): Int =
        ((1..MAX_PROFILES).toSet() - _state.value.profiles.map { it.profileIndex }.toSet())
            .minOrNull()
            ?: MAX_PROFILES

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        avatarUrl: String? = null,
        usesPrimaryAddons: Boolean = false,
    ): Boolean {
        val existing = _state.value.profiles
        val nextIndex = ((1..MAX_PROFILES).toSet() - existing.map { it.profileIndex }.toSet()).minOrNull()
            ?: return false

        val allPayloads = existing.map { profile ->
            ProfilePushPayload(
                profileIndex = profile.profileIndex,
                name = profile.name,
                avatarColorHex = profile.avatarColorHex,
                usesPrimaryAddons = profile.usesPrimaryAddons,
                usesPrimaryPlugins = profile.usesPrimaryPlugins,
                avatarId = profile.avatarId,
                avatarUrl = profile.avatarUrl,
                profileBackgroundId = profile.profileBackgroundId,
                profileBackgroundUrl = profile.profileBackgroundUrl,
            )
        } + ProfilePushPayload(
            profileIndex = nextIndex,
            name = name,
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            avatarId = avatarId,
            avatarUrl = avatarUrl,
        )

        return pushProfiles(allPayloads)
    }

    /**
     * Seed one default profile for a fresh local-only (anonymous / "Continue Without Account")
     * session so it lands straight in the app instead of the empty "Who's watching?" grid. No-op for
     * a signed-in account (its profiles come from the backend pull) or when a profile already exists
     * ([DefaultProfileSeedPolicy]). Local-only, so [createProfile] routes through applyPayloadsLocally
     * — never a sync_push_profiles that would 42501 for a session-less client. The flag guards the
     * check-then-create against a concurrent second call (the gate can recompose mid-seed).
     */
    suspend fun ensureDefaultLocalProfile() {
        if (seedingDefaultProfile) return
        if (!DefaultProfileSeedPolicy.shouldSeed(
                isLocalOnly = AuthRepository.state.value.isLocalOnly,
                existingProfileCount = _state.value.profiles.size,
            )
        ) {
            return
        }
        seedingDefaultProfile = true
        try {
            createProfile(
                // "Profile 1" — matches NuvioTV's default primary-profile name (and the app's own
                // profile_label_number convention) rather than inventing a new default string.
                name = getString(Res.string.profile_label_number, 1),
                avatarColorHex = PROFILE_COLORS.first(),
            )
        } finally {
            seedingDefaultProfile = false
        }
    }

    suspend fun updateProfile(
        profileIndex: Int,
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        avatarUrl: String? = null,
        profileBackgroundId: String? = null,
        profileBackgroundUrl: String? = null,
        usesPrimaryAddons: Boolean = false,
    ): Boolean {
        val allPayloads = _state.value.profiles.map { profile ->
            if (profile.profileIndex == profileIndex) {
                ProfilePushPayload(
                    profileIndex = profileIndex,
                    name = name,
                    avatarColorHex = avatarColorHex,
                    usesPrimaryAddons = usesPrimaryAddons,
                    avatarId = avatarId,
                    avatarUrl = avatarUrl,
                    profileBackgroundId = profileBackgroundId,
                    profileBackgroundUrl = profileBackgroundUrl,
                )
            } else {
                ProfilePushPayload(
                    profileIndex = profile.profileIndex,
                    name = profile.name,
                    avatarColorHex = profile.avatarColorHex,
                    usesPrimaryAddons = profile.usesPrimaryAddons,
                    usesPrimaryPlugins = profile.usesPrimaryPlugins,
                    avatarId = profile.avatarId,
                    avatarUrl = profile.avatarUrl,
                    profileBackgroundId = profile.profileBackgroundId,
                    profileBackgroundUrl = profile.profileBackgroundUrl,
                )
            }
        }

        return pushProfiles(allPayloads)
    }

    /**
     * Make [profileIndex] the account's primary profile by swapping it with index 1.
     *
     * Index 1 is the anchor: it can't be deleted and the others inherit from it via
     * usesPrimaryAddons / usesPrimaryPlugins. Someone who outgrows the profile they first created
     * otherwise has no way out — their real data sits on index 2+ and the empty first profile is
     * permanent. Swapping is the only move that preserves both profiles' data.
     *
     * Returns true when the profiles actually changed places.
     */
    suspend fun promoteToPrimary(profileIndex: Int): Boolean {
        if (profileIndex == PRIMARY_PROFILE_INDEX) return false
        val profiles = _state.value.profiles
        if (profiles.none { it.profileIndex == profileIndex }) return false
        if (profiles.none { it.profileIndex == PRIMARY_PROFILE_INDEX }) return false

        if (AuthRepository.state.value.isLocalOnly) {
            swapProfileIndexesLocally(PRIMARY_PROFILE_INDEX, profileIndex)
            return true
        }

        return try {
            val params = buildJsonObject {
                put("p_a", PRIMARY_PROFILE_INDEX)
                put("p_b", profileIndex)
                putSyncOriginClientId()
            }
            SupabaseProvider.client.postgrest.rpc("sync_swap_profile_index", params)
            // The server moved every per-profile row; the PIN cache is local, so move it here.
            swapPinCache(PRIMARY_PROFILE_INDEX, profileIndex)
            // Follow the profile the user was on rather than the index, which now means someone else.
            activeProfileIndex = when (activeProfileIndex) {
                PRIMARY_PROFILE_INDEX -> profileIndex
                profileIndex -> PRIMARY_PROFILE_INDEX
                else -> activeProfileIndex
            }
            pullProfiles()
            true
        } catch (e: Throwable) {
            if (AuthRepository.signOutIfSessionInvalid(e, "Profile promote")) return false
            log.e(e) { "Failed to promote profile $profileIndex to primary" }
            false
        }
    }

    /**
     * Signed-out equivalent of the server's sync_swap_profile_index: there are no synced rows to
     * move, so exchanging the two profiles' indexes in the local list is the whole operation.
     */
    private fun swapProfileIndexesLocally(a: Int, b: Int) {
        val swapped = _state.value.profiles.map { profile ->
            when (profile.profileIndex) {
                a -> profile.copy(profileIndex = b)
                b -> profile.copy(profileIndex = a)
                else -> profile
            }
        }.sortedBy { it.profileIndex }
        swapPinCache(a, b)
        activeProfileIndex = when (activeProfileIndex) {
            a -> b
            b -> a
            else -> activeProfileIndex
        }
        _state.value = _state.value.copy(
            profiles = swapped,
            activeProfile = swapped.find { it.profileIndex == activeProfileIndex },
        )
        persist()
    }

    private fun swapPinCache(a: Int, b: Int) {
        val payloadA = ProfilePinCacheStorage.loadPayload(a)
        val payloadB = ProfilePinCacheStorage.loadPayload(b)
        if (payloadB != null) ProfilePinCacheStorage.savePayload(a, payloadB) else ProfilePinCacheStorage.removePayload(a)
        if (payloadA != null) ProfilePinCacheStorage.savePayload(b, payloadA) else ProfilePinCacheStorage.removePayload(b)
    }

    suspend fun deleteProfile(profileIndex: Int) {
        if (AuthRepository.state.value.isLocalOnly) {
            val remaining = _state.value.profiles.filter { it.profileIndex != profileIndex }
            ProfilePinCacheStorage.removePayload(profileIndex)
            _state.value = _state.value.copy(
                profiles = remaining,
                activeProfile = if (_state.value.activeProfile?.profileIndex == profileIndex) remaining.firstOrNull() else _state.value.activeProfile,
            )
            if (_state.value.activeProfile != null) {
                activeProfileIndex = _state.value.activeProfile!!.profileIndex
            }
            persist()
            return
        }
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                putSyncOriginClientId()
            }
            SupabaseProvider.client.postgrest.rpc("sync_delete_profile_data", params)
            pullProfiles()
        } catch (e: Throwable) {
            if (AuthRepository.signOutIfSessionInvalid(e, "Profile delete")) return
            log.e(e) { "Failed to delete profile $profileIndex" }
        }
    }

    suspend fun verifyPin(profileIndex: Int, pin: String): PinVerifyResult {
        if (AuthRepository.state.value !is AuthState.Authenticated) {
            return verifyPinLocally(profileIndex, pin)
        }

        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                put("p_pin", pin)
            }
            val result = SupabaseProvider.client.postgrest.rpc("verify_profile_pin", params)
            result.decodeSingle<PinVerifyResult>().also { verifyResult ->
                if (verifyResult.unlocked) {
                    pullProfiles()
                    rememberVerifiedPin(profileIndex = profileIndex, pin = pin)
                }
            }
        }.getOrElse { e ->
            log.e(e) { "Failed to verify pin" }
            verifyPinLocally(profileIndex, pin)
        }
    }

    suspend fun setPin(profileIndex: Int, pin: String, currentPin: String? = null): PinVerifyResult {
        if (AuthRepository.state.value !is AuthState.Authenticated) {
            return PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_set_requires_internet))
        }

        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                put("p_pin", pin)
                currentPin?.let { put("p_current_pin", it) }
            }
            SupabaseProvider.client.postgrest.rpc("set_profile_pin", params)
            pullProfiles()
            rememberVerifiedPin(profileIndex = profileIndex, pin = pin)
            PinVerifyResult(unlocked = true)
        }.onFailure { e ->
            log.e(e) { "Failed to set pin" }
        }.getOrElse { e ->
            // The server knows about a PIN this device didn't. Say so, and let the caller collect it
            // — otherwise the user retries the same rejected call forever.
            if (e.isCurrentPinRequired()) {
                PinVerifyResult(
                    unlocked = false,
                    currentPinRequired = true,
                    message = getString(Res.string.profile_pin_current_required),
                )
            } else {
                PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_set_failed))
            }
        }
    }

    /**
     * `set_profile_pin` raises `Current PIN is required` when the profile already has a `pin_hash`
     * and `p_current_pin` was null. Matched on the message because that is all PostgREST forwards —
     * a plpgsql `raise exception` arrives as a generic error with the text intact.
     */
    private fun Throwable.isCurrentPinRequired(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.message?.contains("current pin is required", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    suspend fun clearPin(profileIndex: Int, currentPin: String? = null): PinVerifyResult {
        if (AuthRepository.state.value !is AuthState.Authenticated) {
            return PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_clear_requires_internet))
        }

        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                currentPin?.let { put("p_current_pin", it) }
            }
            SupabaseProvider.client.postgrest.rpc("clear_profile_pin", params)
            pullProfiles()
            ProfilePinCacheStorage.removePayload(profileIndex)
            PinVerifyResult(unlocked = true)
        }.onFailure { e ->
            log.e(e) { "Failed to clear pin" }
        }.getOrElse {
            PinVerifyResult(unlocked = false, message = getString(Res.string.profile_pin_clear_failed))
        }
    }

    suspend fun clearPinWithPassword(profileIndex: Int, accountPassword: String) {
        runCatching {
            val params = buildJsonObject {
                put("p_account_password", accountPassword)
                put("p_profile_id", profileIndex)
            }
            SupabaseProvider.client.postgrest.rpc("clear_profile_pin_with_account_password", params)
            pullProfiles()
            ProfilePinCacheStorage.removePayload(profileIndex)
        }.onFailure { e ->
            log.e(e) { "Failed to clear pin with password" }
        }
    }

    suspend fun pullProfileLocks(): List<ProfileLockState> {
        // Signed-out / lapsed session: skip the RPC (it would run as `anon` → 42501, the single
        // biggest source of those on the backend). Mirrors the gate in pullProfiles() above.
        if (!com.nuvio.app.core.sync.SyncSession.canSync()) return emptyList()
        return runCatching {
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_locks")
            result.decodeList<ProfileLockState>()
        }.getOrElse { e ->
            log.e(e) { "Failed to pull profile locks" }
            emptyList()
        }
    }

    private fun applyPayloadsLocally(payloads: List<ProfilePushPayload>) {
        // Signed-out callers reach this too now, and they have no user id. Bailing out here would
        // reinstate the very data loss isLocalOnly was added to stop, so fall back to a blank id -
        // it is only local bookkeeping, and pullProfiles overwrites it once an account signs in.
        val localUserId = (AuthRepository.state.value as? AuthState.Authenticated)?.userId.orEmpty()
        val profiles = payloads.map { p ->
            NuvioProfile(
                id = "",
                userId = localUserId,
                profileIndex = p.profileIndex,
                name = p.name,
                avatarColorHex = p.avatarColorHex,
                avatarId = p.avatarId,
                avatarUrl = p.avatarUrl,
                profileBackgroundId = p.profileBackgroundId,
                profileBackgroundUrl = p.profileBackgroundUrl,
                usesPrimaryAddons = p.usesPrimaryAddons,
                usesPrimaryPlugins = p.usesPrimaryPlugins,
            )
        }.sortedBy { it.profileIndex }
        _state.value = _state.value.copy(
            profiles = profiles,
            isLoaded = true,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: profiles.firstOrNull(),
        )
        if (_state.value.activeProfile != null) {
            activeProfileIndex = _state.value.activeProfile!!.profileIndex
        }
        syncPinCache(profiles)
        persist()
    }

    private fun decodeStoredPayload(): StoredProfilePayload? {
        val payload = ProfileStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return null

        return runCatching {
            json.decodeFromString<StoredProfilePayload>(payload)
        }.getOrNull()
    }

    private fun applyStoredPayload(stored: StoredProfilePayload) {
        val profiles = stored.profiles.sortedBy { it.profileIndex }
        activeProfileIndex = stored.activeProfileIndex
        _state.value = ProfileState(
            profiles = profiles,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: profiles.firstOrNull(),
            isLoaded = profiles.isNotEmpty(),
            hasEverSelectedProfile = stored.hasEverSelectedProfile,
            rememberLastProfileEnabled = stored.rememberLastProfileEnabled,
        )
        _state.value.activeProfile?.let { activeProfileIndex = it.profileIndex }
        syncPinCache(profiles)
    }

    private fun rememberVerifiedPin(profileIndex: Int, pin: String) {
        val profile = _state.value.profiles.find { it.profileIndex == profileIndex }
        val salt = generateProfilePinSalt()
        val payload = CachedProfilePinPayload(
            salt = salt,
            digest = hashProfilePin(profileIndex = profileIndex, salt = salt, pin = pin),
            profileUpdatedAt = profile?.updatedAt.orEmpty(),
        )
        ProfilePinCacheStorage.savePayload(profileIndex, json.encodeToString(payload))
    }

    private fun verifyPinLocally(profileIndex: Int, pin: String): PinVerifyResult {
        val profile = _state.value.profiles.find { it.profileIndex == profileIndex }
        if (profile?.pinEnabled != true) {
            return PinVerifyResult(unlocked = true)
        }

        val payload = ProfilePinCacheStorage.loadPayload(profileIndex).orEmpty().trim()
        if (payload.isEmpty()) {
            return PinVerifyResult(
                unlocked = false,
                message = localizedString(Res.string.profile_pin_offline_verification_requires_online),
            )
        }

        val cached = runCatching {
            json.decodeFromString<CachedProfilePinPayload>(payload)
        }.getOrNull() ?: return PinVerifyResult(
            unlocked = false,
            message = localizedString(Res.string.profile_pin_offline_verification_requires_online),
        )

        if (
            cached.profileUpdatedAt.isNotBlank() &&
            profile.updatedAt.isNotBlank() &&
            cached.profileUpdatedAt != profile.updatedAt
        ) {
            ProfilePinCacheStorage.removePayload(profileIndex)
            return PinVerifyResult(
                unlocked = false,
                message = localizedString(Res.string.profile_pin_changed_requires_refresh),
            )
        }

        val digest = hashProfilePin(profileIndex = profileIndex, salt = cached.salt, pin = pin)
        return if (digest == cached.digest) {
            PinVerifyResult(unlocked = true)
        } else {
            PinVerifyResult(unlocked = false, message = localizedString(Res.string.pin_incorrect))
        }
    }

    private fun syncPinCache(profiles: List<NuvioProfile>) {
        val profilesByIndex = profiles.associateBy { it.profileIndex }
        for (profileIndex in 1..MAX_PROFILES) {
            val profile = profilesByIndex[profileIndex]
            if (profile == null || !profile.pinEnabled) {
                ProfilePinCacheStorage.removePayload(profileIndex)
                continue
            }

            val raw = ProfilePinCacheStorage.loadPayload(profileIndex).orEmpty().trim()
            if (raw.isEmpty()) continue

            val cached = runCatching {
                json.decodeFromString<CachedProfilePinPayload>(raw)
            }.getOrNull() ?: run {
                ProfilePinCacheStorage.removePayload(profileIndex)
                continue
            }

            if (
                cached.profileUpdatedAt.isNotBlank() &&
                profile.updatedAt.isNotBlank() &&
                cached.profileUpdatedAt != profile.updatedAt
            ) {
                ProfilePinCacheStorage.removePayload(profileIndex)
            }
        }
    }

    private fun persist() {
        // Same reason as applyPayloadsLocally: signed-out users have no user id, and returning here
        // meant nothing was ever written to disk for them - their edits survived until the process
        // died and then vanished. ensureLoaded() compares this id to detect an account change, and a
        // blank id correctly mismatches a real one, so signing in still discards the local set.
        val localUserId = (AuthRepository.state.value as? AuthState.Authenticated)?.userId.orEmpty()
        val state = _state.value
        ProfileStorage.savePayload(
            json.encodeToString(
                StoredProfilePayload(
                    userId = localUserId,
                    activeProfileIndex = activeProfileIndex,
                    hasEverSelectedProfile = state.hasEverSelectedProfile,
                    rememberLastProfileEnabled = state.rememberLastProfileEnabled,
                    profiles = state.profiles,
                ),
            ),
        )
    }
}

@kotlinx.serialization.Serializable
data class ProfileLockState(
    @kotlinx.serialization.SerialName("profile_index") val profileIndex: Int,
    @kotlinx.serialization.SerialName("pin_enabled") val pinEnabled: Boolean = false,
    @kotlinx.serialization.SerialName("pin_locked_until") val pinLockedUntil: String? = null,
)
