package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.match.XtreamTmdbResolver
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class XtreamUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    val isValidating: Boolean = false,
    val error: String? = null
)

/**
 * Xtream IPTV accounts, persisted locally per profile. Object-singleton with a
 * MutableStateFlow, mirroring AddonRepository / DebridSettingsRepository. KMP twin of
 * NuvioTV's XtreamAccountStore + XtreamSettingsViewModel.
 */
object XtreamRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(XtreamUiState())
    val uiState: StateFlow<XtreamUiState> = _uiState.asStateFlow()

    private var loaded = false
    private var currentProfileId = 1

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        currentProfileId = ProfileRepository.activeProfileId
        _uiState.update { it.copy(accounts = parse(XtreamAccountStorage.loadAccountsJson(currentProfileId))) }
    }

    /** Reload this profile's accounts on a profile switch so no data leaks across profiles. */
    fun onProfileChanged(profileId: Int) {
        loaded = true
        currentProfileId = profileId
        _uiState.value = XtreamUiState(accounts = parse(XtreamAccountStorage.loadAccountsJson(profileId)))
        XtreamTmdbResolver.warmUp(_uiState.value.accounts)
    }

    /**
     * Kick background catalog-index builds so the first play/search doesn't pay the
     * full-catalog download on demand (minutes on budget devices). Idempotent — a
     * fresh index short-circuits. Called at app start with a delay so the home
     * screen wins the cold-start bandwidth.
     */
    fun warmUpMatchIndexes(startDelayMs: Long = 0L) {
        ensureLoaded()
        XtreamTmdbResolver.warmUp(_uiState.value.accounts, startDelayMs)
    }

    /** Parse a pasted portal/M3U URL, verify the credentials live, then persist. */
    fun addFromUrl(input: String, name: String?, onResult: (Boolean) -> Unit) {
        verifyAndSave(parseXtreamAccount(input, name), "Couldn't read a username & password from that URL", onResult)
    }

    /** Add from manually-entered server URL + username + password. */
    fun addManual(serverUrl: String, username: String, password: String, name: String?, onResult: (Boolean) -> Unit) {
        verifyAndSave(
            xtreamAccountFromFields(serverUrl, username, password, name),
            "Enter a server URL, username and password",
            onResult
        )
    }

    /**
     * Add from the full "Add Playlist" form: server URL + username + password + optional name plus
     * the playlist options collected on the form (EPG URL, DNS provider, auto-refresh). The identity
     * comes from the fields (a pasted portal URL is auto-filled into them upstream);
     * [xtreamAccountFromForm] layers the option fields on before the live verify + persist.
     */
    internal fun addFromForm(input: XtreamFormInput, onResult: (Boolean) -> Unit) {
        when (input.sourceType) {
            SOURCE_TYPE_M3U_FILE -> addFileFromForm(input, existingId = null, onResult = onResult)
            SOURCE_TYPE_M3U_URL -> verifyAndSave(m3uAccountFromForm(input), "Enter an M3U playlist URL", onResult)
            SOURCE_TYPE_STALKER -> verifyAndSave(stalkerAccountFromForm(input), "Enter a portal URL and MAC address", onResult)
            else -> verifyAndSave(xtreamAccountFromForm(input), "Enter a server URL, username and password", onResult)
        }
    }

    /**
     * Add/replace an M3U-FILE playlist: build the account (stable id), copy the picked file into app
     * storage at `{id}.m3u`, THEN verify (which ingests the LOCAL copy). The copy happens before verify
     * so the ingest has bytes to read; a failed verify leaves the copy in place harmlessly (it'll be
     * overwritten on the next attempt). Editing options with no re-pick reuses the existing copy.
     */
    private fun addFileFromForm(input: XtreamFormInput, existingId: String?, onResult: (Boolean) -> Unit) {
        val account = m3uFileAccountFromForm(input, existingId = existingId, uniqueSuffix = TraktPlatformClock.nowEpochMs())
        if (account == null) {
            _uiState.update { it.copy(error = "Choose an M3U file to import") }
            onResult(false)
            return
        }
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            // Copy the picked bytes into local storage first (skip when editing with no re-pick).
            val copyOk = runCatching {
                input.pickedFile?.let { copyM3UFileToStorage(account.id, it) }
            }.isSuccess
            if (!copyOk) {
                _uiState.update { it.copy(isValidating = false, error = "Could not read the selected file") }
                onResult(false)
                return@launch
            }
            // No pick + no existing local copy = nothing to ingest.
            if (input.pickedFile == null && !M3UFileStore.hasLocalCopy(account)) {
                _uiState.update { it.copy(isValidating = false, error = "Choose an M3U file to import") }
                onResult(false)
                return@launch
            }
            M3UClient.verify(account)
                .onSuccess {
                    val updated = _uiState.value.accounts.filterNot { it.id == account.id } + account
                    _uiState.update { it.copy(accounts = updated, isValidating = false) }
                    persist()
                    onResult(true)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isValidating = false, error = e.message ?: "Could not read that playlist file") }
                    onResult(false)
                }
        }
    }

    /**
     * Edit an existing playlist from the full form. Re-verifies the (possibly changed) credentials,
     * swaps the account in place, and applies the edited option fields. Provider-specific carry-over
     * (category ids, when the identity is unchanged) is handled by [verifyAndReplace]/[carryPlaylistOptions];
     * the form's own option fields (EPG/DNS/auto-refresh) always win because the form shows them.
     */
    internal fun editFromForm(oldId: String, input: XtreamFormInput, onResult: (Boolean) -> Unit) {
        // A file playlist edit keeps its id (so its local copy + saved data carry over); a re-pick just
        // overwrites the copy. Route through the same add-file path with the existing id.
        if (input.sourceType == SOURCE_TYPE_M3U_FILE) {
            addFileFromForm(input, existingId = oldId, onResult = onResult)
            return
        }
        val candidate = when (input.sourceType) {
            SOURCE_TYPE_M3U_URL -> m3uAccountFromForm(input)
            SOURCE_TYPE_STALKER -> stalkerAccountFromForm(input)
            else -> xtreamAccountFromForm(input)
        }
        verifyAndReplace(
            oldId,
            candidate,
            when (input.sourceType) {
                SOURCE_TYPE_M3U_URL -> "Enter an M3U playlist URL"
                SOURCE_TYPE_STALKER -> "Enter a portal URL and MAC address"
                else -> "Enter a server URL, username and password"
            },
            onResult,
            // The form shows EPG/DNS/auto-refresh, so its candidate already carries the user's choices —
            // don't let carry-over revert them to the old account's values.
            keepCandidateFormOptions = true,
        )
    }

    private fun verifyAndSave(account: XtreamAccount?, parseError: String, onResult: (Boolean) -> Unit) {
        if (account == null) {
            _uiState.update { it.copy(error = parseError) }
            onResult(false)
            return
        }
        val profileAtStart = currentProfileId
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            // Xtream verifies creds against player_api.php; M3U verifies by ingesting the playlist.
            IptvClient.forAccount(account).verify(account)
                .onSuccess {
                    // Profile switched while verifying — saving now would write this playlist
                    // into the wrong profile's list. Drop it; re-add on the right profile.
                    if (currentProfileId != profileAtStart) {
                        _uiState.update { it.copy(isValidating = false) }
                        onResult(false)
                        return@onSuccess
                    }
                    val updated = _uiState.value.accounts.filterNot { it.id == account.id } + account
                    _uiState.update { it.copy(accounts = updated, isValidating = false) }
                    // Start the catalog index now, not on first play — minutes on budget devices.
                    XtreamTmdbResolver.warmUp(listOf(account))
                    persist()
                    onResult(true)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isValidating = false, error = e.message ?: "Could not reach the panel") }
                    onResult(false)
                }
        }
    }

    /** Re-verify + replace an existing account from a pasted portal/M3U URL (playlist edit). */
    fun editFromUrl(oldId: String, input: String, onResult: (Boolean) -> Unit) {
        val oldName = _uiState.value.accounts.firstOrNull { it.id == oldId }?.name
        verifyAndReplace(oldId, parseXtreamAccount(input, oldName), "Couldn't read a username & password from that URL", onResult)
    }

    /** Re-verify + replace an existing account from manually-edited fields (playlist edit). */
    fun editManual(oldId: String, serverUrl: String, username: String, password: String, name: String?, onResult: (Boolean) -> Unit) {
        verifyAndReplace(
            oldId,
            xtreamAccountFromFields(serverUrl, username, password, name),
            "Enter a server URL, username and password",
            onResult
        )
    }

    /**
     * Verifies the edited credentials live, then swaps the account in place (keeping its
     * position + enabled flag) and re-runs the discovery cycle. Saved items (library,
     * watch progress, watched marks, recent channels) follow the account when it's still
     * the same playlist; a completely different playlist purges them instead.
     */
    private fun verifyAndReplace(
        oldId: String,
        candidate: XtreamAccount?,
        parseError: String,
        onResult: (Boolean) -> Unit,
        keepCandidateFormOptions: Boolean = false,
    ) {
        val old = _uiState.value.accounts.firstOrNull { it.id == oldId }
        if (old == null || candidate == null) {
            _uiState.update { it.copy(error = if (old == null) "Account no longer exists" else parseError) }
            onResult(false)
            return
        }
        // A credential/URL edit must not wipe the playlist options — but provider-specific
        // ones only carry when the edit still targets the same playlist (see carryPlaylistOptions).
        val account = carryPlaylistOptions(old, candidate, keepCandidateFormOptions)
        val profileAtStart = currentProfileId
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            IptvClient.forAccount(account).verify(account)
                .onSuccess {
                    // Profile switched while verifying — see verifyAndSave.
                    if (currentProfileId != profileAtStart) {
                        _uiState.update { it.copy(isValidating = false) }
                        onResult(false)
                        return@onSuccess
                    }
                    _uiState.update { st ->
                        st.copy(
                            // Replace in place; drop any pre-existing duplicate of the new identity.
                            accounts = st.accounts
                                .filterNot { it.id == account.id && it.id != oldId }
                                .map { if (it.id == oldId) account else it },
                            isValidating = false,
                        )
                    }
                    if (account.id != oldId) migrateSavedData(old, account)
                    // A changed M3U URL invalidates the old catalog rows — drop them.
                    if (old.sourceType == SOURCE_TYPE_M3U_URL && old.id != account.id) M3UClient.clear(old)
                    // Re-run the discovery cycle: drop caches/URLs built with the old server/creds.
                    XtreamItemRegistry.resetForProfile()
                    XtreamHubRepository.resetForProfile()
                    XtreamSearchIndex.resetForProfile()
                    XtreamTmdbResolver.warmUp(listOf(account))
                    persist()
                    onResult(true)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isValidating = false, error = e.message ?: "Could not reach the panel") }
                    onResult(false)
                }
        }
    }

    /**
     * Same playlist (same server or same username, e.g. a panel that moved domains or
     * rotated creds) -> rewrite saved xtream content ids to the new account id. A completely
     * different playlist -> the old ids point at content that no longer exists, so drop them.
     */
    private fun migrateSavedData(old: XtreamAccount, new: XtreamAccount) {
        val oldPrefix = XtreamItemRegistry.accountPrefix(old.id)
        val newPrefix = if (samePlaylist(old, new)) XtreamItemRegistry.accountPrefix(new.id) else null
        LibraryRepository.migrateIdPrefix(oldPrefix, newPrefix)
        WatchProgressRepository.migrateIdPrefix(oldPrefix, newPrefix)
        WatchedRepository.migrateIdPrefix(oldPrefix, newPrefix)
        XtreamLiveRecents.migrateIdPrefix(oldPrefix, newPrefix)
    }

    /**
     * Option-only edit (content types, category selections, …): swap the account in place and
     * persist + sync-push. No credential re-verify — the identity fields don't change.
     */
    fun updateOptions(id: String, transform: (XtreamAccount) -> XtreamAccount) {
        _uiState.update { st ->
            st.copy(accounts = st.accounts.map { if (it.id == id) transform(it) else it })
        }
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _uiState.update { state ->
            state.copy(accounts = state.accounts.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
        if (enabled) _uiState.value.accounts.firstOrNull { it.id == id }?.let { XtreamTmdbResolver.warmUp(listOf(it)) }
        persist()
    }

    fun remove(id: String) {
        val removed = _uiState.value.accounts.firstOrNull { it.id == id }
        _uiState.update { it.copy(accounts = it.accounts.filterNot { acc -> acc.id == id }) }
        // Caches keyed by this id leak otherwise (match db rows survive forever); saved
        // refs would be dead ids (phantom favorites / continue-watching rows).
        XtreamItemRegistry.resetForProfile()
        XtreamHubRepository.resetForProfile()
        XtreamSearchIndex.resetForProfile()
        scope.launch { runCatching { XtreamMatchIndex.purge(id) } }
        val prefix = XtreamItemRegistry.accountPrefix(id)
        LibraryRepository.migrateIdPrefix(prefix, null)
        WatchProgressRepository.migrateIdPrefix(prefix, null)
        WatchedRepository.migrateIdPrefix(prefix, null)
        XtreamLiveRecents.migrateIdPrefix(prefix, null)
        // Free the parsed catalog rows + EPG for a removed M3U playlist (can be hundreds of MB of DB),
        // and drop a file playlist's saved local copy.
        if (removed != null && removed.sourceType.isM3u()) scope.launch {
            M3UClient.clear(removed)
            if (removed.sourceType == SOURCE_TYPE_M3U_FILE) deleteM3UFile(removed.id)
        }
        persist()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Replace this profile's accounts from a remote pull WITHOUT echoing a push back. */
    fun applyFromRemote(profileId: Int, accounts: List<XtreamAccount>) {
        loaded = true
        currentProfileId = profileId
        val before = _uiState.value.accounts
        _uiState.update { it.copy(accounts = accounts) }
        XtreamAccountStorage.saveAccountsJson(profileId, json.encodeToString(accounts))
        if (before != accounts) {
            // Same discovery-cycle reset as a local edit: cached stream URLs embed the old
            // server/creds, and a playlist deleted on another device leaves index rows behind.
            XtreamItemRegistry.resetForProfile()
            XtreamHubRepository.resetForProfile()
            XtreamSearchIndex.resetForProfile()
            val remaining = accounts.map { it.id }.toSet()
            before.filter { it.id !in remaining }
                .forEach { gone -> scope.launch { runCatching { XtreamMatchIndex.purge(gone.id) } } }
        }
        // An account added on another device should index here before its first play.
        XtreamTmdbResolver.warmUp(accounts)
    }

    private fun persist() {
        XtreamAccountStorage.saveAccountsJson(currentProfileId, json.encodeToString(_uiState.value.accounts))
        XtreamAccountSyncService.triggerPush()
    }

    private fun parse(stored: String?): List<XtreamAccount> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<XtreamAccount>>(stored) }.getOrDefault(emptyList())
    }
}

/**
 * Same playlist = same server or same username (e.g. a panel that moved domains or rotated
 * creds) — the predicate both saved-data migration and option carry-over key off.
 */
internal fun samePlaylist(old: XtreamAccount, new: XtreamAccount): Boolean =
    old.username == new.username || old.baseUrl == new.baseUrl

/**
 * Options carried onto an edited account. Provider-agnostic options (enabled, content types,
 * DNS, auto-refresh) always carry over; provider-specific ones (category ids, EPG URL) only
 * when the edit still targets the same playlist — another provider's category ids are
 * meaningless there and would silently filter its whole catalog. internal for tests.
 *
 * When [keepCandidateFormOptions] is set (the full "Add Playlist" form, which shows these fields),
 * the candidate's own EPG URL / DNS provider / auto-refresh win instead of being reverted to the
 * old account's — the user just edited them on the form. Content types + category selections are
 * still carried (they live on a different page the form doesn't touch).
 */
internal fun carryPlaylistOptions(
    old: XtreamAccount,
    candidate: XtreamAccount,
    keepCandidateFormOptions: Boolean = false,
): XtreamAccount {
    val same = samePlaylist(old, candidate)
    return candidate.copy(
        enabled = old.enabled,
        dnsProvider = if (keepCandidateFormOptions) candidate.dnsProvider else old.dnsProvider,
        autoRefreshHours = if (keepCandidateFormOptions) candidate.autoRefreshHours else old.autoRefreshHours,
        contentTypes = old.contentTypes,
        epgUrl = when {
            keepCandidateFormOptions -> candidate.epgUrl
            same -> old.epgUrl
            else -> null
        },
        categorySelections = if (same) old.categorySelections else CategorySelections(),
    )
}
