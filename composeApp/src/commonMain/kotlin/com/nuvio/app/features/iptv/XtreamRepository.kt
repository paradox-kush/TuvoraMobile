package com.nuvio.app.features.iptv

import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
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

    private fun verifyAndSave(account: XtreamAccount?, parseError: String, onResult: (Boolean) -> Unit) {
        if (account == null) {
            _uiState.update { it.copy(error = parseError) }
            onResult(false)
            return
        }
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            XtreamClient.verify(account)
                .onSuccess {
                    val updated = _uiState.value.accounts.filterNot { it.id == account.id } + account
                    _uiState.update { it.copy(accounts = updated, isValidating = false) }
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
    private fun verifyAndReplace(oldId: String, candidate: XtreamAccount?, parseError: String, onResult: (Boolean) -> Unit) {
        val old = _uiState.value.accounts.firstOrNull { it.id == oldId }
        if (old == null || candidate == null) {
            _uiState.update { it.copy(error = if (old == null) "Account no longer exists" else parseError) }
            onResult(false)
            return
        }
        // Carry every option over — a credential/URL edit must not wipe content-type or
        // category choices (nor the other playlist options).
        val account = candidate.copy(
            enabled = old.enabled,
            epgUrl = old.epgUrl,
            dnsProvider = old.dnsProvider,
            autoRefreshHours = old.autoRefreshHours,
            contentTypes = old.contentTypes,
            categorySelections = old.categorySelections,
        )
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            XtreamClient.verify(account)
                .onSuccess {
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
                    // Re-run the discovery cycle: drop caches/URLs built with the old server/creds.
                    XtreamItemRegistry.resetForProfile()
                    XtreamHubRepository.resetForProfile()
                    XtreamSearchIndex.resetForProfile()
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
        val samePlaylist = old.username == new.username || old.baseUrl == new.baseUrl
        val oldPrefix = XtreamItemRegistry.accountPrefix(old.id)
        val newPrefix = if (samePlaylist) XtreamItemRegistry.accountPrefix(new.id) else null
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
        persist()
    }

    fun remove(id: String) {
        _uiState.update { it.copy(accounts = it.accounts.filterNot { acc -> acc.id == id }) }
        persist()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Replace this profile's accounts from a remote pull WITHOUT echoing a push back. */
    fun applyFromRemote(profileId: Int, accounts: List<XtreamAccount>) {
        loaded = true
        currentProfileId = profileId
        _uiState.update { it.copy(accounts = accounts) }
        XtreamAccountStorage.saveAccountsJson(profileId, json.encodeToString(accounts))
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
