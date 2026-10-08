package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.content.IptvContentDb
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.nuvio.app.core.contracts.IptvCatalog

data class XtreamUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    val isValidating: Boolean = false,
    val error: String? = null,
    /** accountId -> a non-blocking note about a saved edit (B60: the provider check failed, but the
     *  edit was kept). Shown on the playlist's row; cleared by the next edit of that playlist. */
    val saveWarnings: Map<String, String> = emptyMap(),
)

/**
 * An immutable, atomically-captured outgoing-push snapshot (B24 §2): the target [profileId], whether
 * the loaded state is authoritative enough to full-replace, and the exact accounts to send. Because
 * it is a value snapshot taken in one synchronous read, a profile switch or a local edit occurring
 * during the network push can neither redirect it nor change its payload. The KMP twin of NuvioTV's
 * PlaylistPushSnapshot.
 */
data class PlaylistPushSnapshot(
    val profileId: Int,
    val canFullReplace: Boolean,
    val accounts: List<XtreamAccount>,
)

/**
 * Xtream IPTV accounts, persisted locally per profile. Object-singleton with a
 * MutableStateFlow, mirroring AddonRepository / DebridSettingsRepository. KMP twin of
 * NuvioTV's XtreamAccountStore + XtreamSettingsViewModel.
 */
object XtreamRepository : IptvCatalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(XtreamUiState())
    val uiState: StateFlow<XtreamUiState> = _uiState.asStateFlow()

    // --- IptvCatalog read port (S3a) ---
    override fun hasEnabledAccounts(): Boolean = _uiState.value.accounts.any { it.enabled }
    override val enabledAccountCount: Int get() = _uiState.value.accounts.count { it.enabled }
    override val hasAnyPlaylist: StateFlow<Boolean> = uiState
        .map { it.accounts.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)
    override val servedStreamTypes: StateFlow<Set<String>> = uiState
        .map { servedStreamTypesOf(it.accounts) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Step 0.3: playlist key -> active backup index, for playlists NOT on their main server (drives the
     * "Using backup server N" row note). Re-read when the accounts or any failover state change.
     */
    val activeServers: StateFlow<Map<String, Int>> = combine(uiState.map { it.accounts }, PlaylistServerFailover.version) { accounts, _ ->
        PlaylistServerFailover.activeIndexes(accounts)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var loaded = false

    /**
     * Whether the current in-memory state is a faithful, COMPLETE view of this profile's playlists —
     * a clean local decode ([PlaylistLoadOutcome.Valid], including a genuine empty list), a remote
     * pull, or a test install. False when the local decode was [PlaylistLoadOutcome.Recovered] or
     * [PlaylistLoadOutcome.Corrupt]: in that case we show the recovered subset but must NOT overwrite
     * the stored bytes or full-replace the server with it (B24 — an incompatible row must not become
     * a wipe). A subsequent clean local decode or a remote pull heals it.
     */
    private var authoritative = false

    /**
     * Last local decode held rows/fields this build could not fully read ([PlaylistLoadOutcome]
     * Recovered/Corrupt). While damaged we must not overwrite the stored bytes with a truncated
     * snapshot, nor push it up (B24). A clean local decode or a remote pull clears it.
     */
    private var damaged = false

    /**
     * The raw blob currently in storage, kept so a re-persist can preserve any per-row keys this
     * build's serializer doesn't know (B24 §3) instead of stripping them via a plain re-encode.
     */
    private var lastStoredRaw: String? = null

    /**
     * Test seam (B24 §3): when set, persist() routes its durable write through this instead of the
     * platform storage, so a test can simulate a write FAILURE (throw) and assert the recovery
     * contract — no false success, no push, no authority promotion, in-memory keeps the pending
     * edit. Never set in production. Reset it in the test's teardown.
     */
    internal var persistWriteForTest: ((profileId: Int, json: String) -> Unit)? = null

    /** Test seam (B60): stands in for the live provider check of an edit. Never set in production. */
    internal var verifyForTest: (suspend (XtreamAccount) -> Result<Unit>)? = null

    /** Test seam (B24 §3): invoke the private persist() directly to exercise the write path. */
    internal fun persistForTest() = persist()

    /**
     * Test seam (B24 §3): stage an in-memory edit the way the add/edit flow does — mutate the shown
     * accounts WITHOUT touching authority/damaged — so a following [persistForTest] reproduces "user
     * edited a fresh store, then the durable write failed." Never called from production code.
     */
    internal fun stageEditForTest(accounts: List<XtreamAccount>) {
        _uiState.update { it.copy(accounts = accounts) }
    }

    /** Test-only view of whether the in-memory state is authoritative (B24 §3). */
    internal val authoritativeForTest: Boolean get() = authoritative

    /** Test-only view of the last durably-persisted blob (B24 §3). */
    internal val lastStoredRawForTest: String? get() = lastStoredRaw

    /**
     * Test seam: installs [accounts] as the loaded state without touching storage, so unit tests can
     * exercise resolution paths that look accounts up by id (e.g. [XtreamItemRegistry.liveStreamUrlFor]).
     * Same idiom as `PosterEnricher.resetForTests` — never called from production code.
     */
    internal fun installAccountsForTest(accounts: List<XtreamAccount>) {
        loaded = true
        authoritative = true
        damaged = false
        _uiState.value = XtreamUiState(accounts = accounts)
    }

    /**
     * Test seam: load state from a RAW stored blob (as a real decode would), so tests can exercise
     * the authority decision the sync push depends on — a Valid/Absent/Recovered/Corrupt blob and
     * its effect on [canPushFullReplace] (B24 §4). Never called from production code.
     */
    internal fun installRawForTest(raw: String?) {
        loaded = true
        _uiState.value = XtreamUiState(accounts = loadAccounts(raw))
    }
    private var currentProfileId = 1

    /** Decode the stored blob element-wise and record whether it is authoritative / damaged. */
    private fun loadAccounts(stored: String?): List<XtreamAccount> {
        val outcome = decodePlaylistStore(json, stored)
        authoritative = outcome.isAuthoritative
        damaged = outcome.isDamaged
        lastStoredRaw = stored
        return outcome.accounts
    }

    override fun ensureLoaded() {
        if (loaded) return
        loaded = true
        currentProfileId = ProfileRepository.activeProfileId
        _uiState.update { it.copy(accounts = loadAccounts(XtreamAccountStorage.loadAccountsJson(currentProfileId))) }
    }

    /** Reload this profile's accounts on a profile switch so no data leaks across profiles. */
    fun onProfileChanged(profileId: Int) {
        loaded = true
        currentProfileId = profileId
        _uiState.value = XtreamUiState(accounts = loadAccounts(XtreamAccountStorage.loadAccountsJson(profileId)))
        XtreamTmdbResolver.warmUp(_uiState.value.accounts)
    }

    /**
     * True only when the in-memory state may safely full-replace the server (or overwrite local
     * storage). The sync push checks this so a Recovered/Corrupt/unloaded state can never wipe a
     * good server collection (B24). A genuine user "delete every playlist" stays authoritative and
     * still pushes the deletion.
     */
    fun canPushFullReplace(): Boolean = authoritative && !damaged

    /**
     * B24 §2 — one atomic capture of everything the outgoing push needs: the profile the loaded
     * state belongs to, whether that state may full-replace, and the exact accounts to send. Read
     * synchronously (no suspension between the three), so the authority decision and the payload
     * cannot desync, and the returned value is immutable — a profile switch or a local edit during
     * the network push can neither redirect it nor change what it sends. The push must send
     * [PlaylistPushSnapshot.accounts] under [PlaylistPushSnapshot.profileId], never the live
     * active-profile state, so a switch mid-flight cannot redirect the payload.
     */
    fun captureOutgoingPush(): PlaylistPushSnapshot =
        PlaylistPushSnapshot(currentProfileId, canPushFullReplace(), _uiState.value.accounts.toList())

    /**
     * Kick background catalog-index builds so the first play/search doesn't pay the
     * full-catalog download on demand (minutes on budget devices). Idempotent — a
     * fresh index short-circuits. Called at app start with a delay so the home
     * screen wins the cold-start bandwidth.
     */
    override suspend fun refreshDuePlaylists() {
        IptvRefreshScheduler.refreshDuePlaylists()
    }

    override fun warmUpMatchIndexes(startDelayMs: Long) {
        ensureLoaded()
        XtreamTmdbResolver.warmUp(_uiState.value.accounts, startDelayMs)
    }

    /** Parse a pasted portal/M3U URL, verify the credentials live, then persist. */
    fun addFromUrl(input: String, name: String?, onResult: (Boolean) -> Unit) {
        verifyAndSave(parseXtreamAccount(input, name), PARSE_URL_ERROR, onResult)
    }

    /** Add from manually-entered server URL + username + password. */
    fun addManual(serverUrl: String, username: String, password: String, name: String?, onResult: (Boolean) -> Unit) {
        verifyAndSave(
            xtreamAccountFromFields(serverUrl, username, password, name),
            xtreamFieldsError(serverUrl, username, password),
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
        // UX21: say WHICH problem the form has (empty fields vs. an address that isn't one) before
        // any network — the builders below return a bare null for both.
        PlaylistSaveErrorPolicy.validate(input)?.let { error ->
            reportFormError(PlaylistSaveMessage.Known(error), onResult)
            return
        }
        when (input.sourceType) {
            SOURCE_TYPE_M3U_FILE -> addFileFromForm(input, existingId = null, onResult = onResult)
            SOURCE_TYPE_M3U_URL -> {
                // An Xtream panel's own get.php export pasted into the M3U field is saved as the
                // Xtream account it is (catch-up, guide history). Add-time only — editing never
                // re-keys (see recogniseXtreamPanelInM3uField). If the panel's API refuses while
                // get.php serves (CDN-fronted panels do this), the M3U lane still works.
                val panel = recogniseXtreamPanelInM3uField(input)
                if (panel == null) {
                    verifyAndSave(m3uAccountFromForm(input), INVALID_ADDRESS, onResult)
                } else {
                    verifyAndSave(panel, INVALID_ADDRESS) { ok ->
                        if (ok) onResult(true)
                        else verifyAndSave(m3uAccountFromForm(input), INVALID_ADDRESS, onResult)
                    }
                }
            }
            SOURCE_TYPE_STALKER -> verifyAndSave(stalkerAccountFromForm(input), INVALID_ADDRESS, onResult)
            else -> verifyAndSave(xtreamAccountFromForm(input), INVALID_ADDRESS, onResult)
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
            // Step 0: an edit keeps the playlist's (not-on-the-form) backup list.
            ?.let { acc -> _uiState.value.accounts.firstOrNull { it.id == existingId }?.let { acc.copy(backupUrls = it.backupUrls) } ?: acc }
        if (account == null) {
            reportFormError(PlaylistSaveMessage.Known(PlaylistSaveError.MISSING_M3U_FILE), onResult)
            return
        }
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            // Copy the picked bytes into local storage first (skip when editing with no re-pick).
            val copyOk = runCatching {
                input.pickedFile?.let { copyM3UFileToStorage(account.id, it) }
            }.isSuccess
            if (!copyOk) {
                val text = PlaylistSaveError.FILE_UNREADABLE.text()
                _uiState.update { it.copy(isValidating = false, error = text) }
                onResult(false)
                return@launch
            }
            // No pick + no existing local copy = nothing to ingest.
            if (input.pickedFile == null && !M3UFileStore.hasLocalCopy(account)) {
                val text = PlaylistSaveError.MISSING_M3U_FILE.text()
                _uiState.update { it.copy(isValidating = false, error = text) }
                onResult(false)
                return@launch
            }
            M3UClient.verify(account)
                .onSuccess {
                    val updated = _uiState.value.accounts.filterNot { it.id == account.id } + account
                    _uiState.update { it.copy(accounts = updated, isValidating = false) }
                    recordPending { it.recordAdd(account) }   // B24 v2: durable add intent (file playlist)
                    persistAndReport(onResult)
                }
                .onFailure { e ->
                    // UX11: a mapped sentence, never a parser's or the platform's raw message.
                    val text = PlaylistSaveErrorPolicy.classify(e, account.sourceType).text()
                    _uiState.update { it.copy(isValidating = false, error = text) }
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
        // A MANAGED playlist's edit is a rename only, built from the pulled account (never from form
        // fields): re-normalising its address would silently detach it from its provider.
        managedRenameCandidate(oldId, input.name)?.let { candidate ->
            verifyAndReplace(oldId, candidate, INVALID_ADDRESS, onResult)
            return
        }
        // A file playlist edit keeps its id (so its local copy + saved data carry over); a re-pick just
        // overwrites the copy. Route through the same add-file path with the existing id.
        if (input.sourceType == SOURCE_TYPE_M3U_FILE) {
            addFileFromForm(input, existingId = oldId, onResult = onResult)
            return
        }
        PlaylistSaveErrorPolicy.validate(input)?.let { error ->
            reportFormError(PlaylistSaveMessage.Known(error), onResult)
            return
        }
        val candidate = when (input.sourceType) {
            SOURCE_TYPE_M3U_URL -> m3uAccountFromForm(input)
            SOURCE_TYPE_STALKER -> stalkerAccountFromForm(input.copy(
                sendDeviceId = input.sendDeviceId
                    ?: _uiState.value.accounts.firstOrNull { it.id == oldId }?.sendDeviceId
                    ?: true,
            ))
            else -> xtreamAccountFromForm(input)
        }
        verifyAndReplace(
            oldId,
            candidate,
            INVALID_ADDRESS,
            onResult,
            // The form shows EPG/DNS/auto-refresh, so its candidate already carries the user's choices —
            // don't let carry-over revert them to the old account's values.
            keepCandidateFormOptions = true,
        )
    }

    private fun verifyAndSave(account: XtreamAccount?, parseError: PlaylistSaveMessage, onResult: (Boolean) -> Unit) {
        if (account == null) {
            reportFormError(parseError, onResult)
            return
        }
        val profileAtStart = currentProfileId
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            // Xtream verifies creds against player_api.php; M3U verifies by ingesting the playlist.
            (verifyForTest?.invoke(account) ?: IptvClient.forAccount(account).verify(account))
                .onSuccess {
                    // Profile switched while verifying — saving now would write this playlist
                    // into the wrong profile's list. Drop it; re-add on the right profile.
                    if (currentProfileId != profileAtStart) {
                        _uiState.update { it.copy(isValidating = false) }
                        onResult(false)
                        return@onSuccess
                    }
                    // The same server + login as a playlist the PROVIDER manages: this add would replace the
                    // pulled row with the form's re-normalised copy and silently detach it (the server
                    // trigger compares every provider-owned field). Keep the pulled fields byte-identical
                    // and record an update, never an add (code review H1 / security M3).
                    val existing = _uiState.value.accounts.firstOrNull { it.id == account.id }
                    val toStore = if (existing != null && isManagedPlaylist(account.id)) {
                        ManagedEditPolicy.lockProviderFields(existing, account)
                    } else {
                        account
                    }
                    val updated = _uiState.value.accounts.filterNot { it.id == account.id } + toStore
                    _uiState.update { it.copy(accounts = updated, isValidating = false) }
                    // Start the catalog index now, not on first play — minutes on budget devices.
                    XtreamTmdbResolver.warmUp(listOf(toStore))
                    if (existing != null && toStore != account) {
                        recordPending { it.recordUpdate(toStore, base = existing) }
                    } else {
                        recordPending { it.recordAdd(toStore) }   // B24 v2: durable "user added this playlist" intent
                    }
                    persistAndReport(onResult)
                }
                .onFailure { e ->
                    // UX11/UX20: a plain, mapped sentence — "unreachable" is no longer reported as
                    // "Authentication failed", and the platform's raw text never reaches the form.
                    val text = PlaylistSaveErrorPolicy.classify(e, account.sourceType).text()
                    _uiState.update { it.copy(isValidating = false, error = text) }
                    onResult(false)
                }
        }
    }

    /** Re-verify + replace an existing account from a pasted portal/M3U URL (playlist edit). */
    fun editFromUrl(oldId: String, input: String, onResult: (Boolean) -> Unit) {
        managedRenameCandidate(oldId, null)?.let { candidate ->
            verifyAndReplace(oldId, candidate, PARSE_URL_ERROR, onResult)
            return
        }
        val oldName = _uiState.value.accounts.firstOrNull { it.id == oldId }?.name
        verifyAndReplace(oldId, parseXtreamAccount(input, oldName), PARSE_URL_ERROR, onResult)
    }

    /** Re-verify + replace an existing account from manually-edited fields (playlist edit). */
    fun editManual(oldId: String, serverUrl: String, username: String, password: String, name: String?, onResult: (Boolean) -> Unit) {
        managedRenameCandidate(oldId, name)?.let { candidate ->
            verifyAndReplace(oldId, candidate, xtreamFieldsError(serverUrl, username, password), onResult)
            return
        }
        verifyAndReplace(
            oldId,
            xtreamAccountFromFields(serverUrl, username, password, name),
            xtreamFieldsError(serverUrl, username, password),
            onResult
        )
    }

    private fun isManagedPlaylist(id: String): Boolean = ManagedInfoRepository.isManaged(currentProfileId, id)

    /** For a managed playlist: the pulled account with only the name changed; null for any other playlist. */
    private fun managedRenameCandidate(id: String, requestedName: String?): XtreamAccount? {
        if (!isManagedPlaylist(id)) return null
        val pulled = _uiState.value.accounts.firstOrNull { it.id == id } ?: return null
        return ManagedEditPolicy.renamed(pulled, requestedName)
    }

    /**
     * Checks the edited connection live, then swaps the account in place (keeping its
     * position + enabled flag) and re-runs the discovery cycle.
     *
     * Step 0: the playlist KEEPS ITS ID whatever was edited (server, username, password, MAC, URL).
     * The id is the permanent key everything the user made hangs off — library, progress, watched,
     * recents, and the overlay's hashed hidden/pinned channel keys, which cannot be re-keyed at all —
     * so re-deriving it from the new address (the old behaviour) orphaned all of it on a domain move.
     *
     * B60: a failed check never discards the edit — it is saved and the reason shown on the row
     * ([PlaylistEditVerifyPolicy]).
     */
    private fun verifyAndReplace(
        oldId: String,
        candidate: XtreamAccount?,
        parseError: PlaylistSaveMessage,
        onResult: (Boolean) -> Unit,
        keepCandidateFormOptions: Boolean = false,
    ) {
        val old = _uiState.value.accounts.firstOrNull { it.id == oldId }
        if (old == null || candidate == null) {
            reportFormError(if (old == null) PlaylistSaveMessage.Authored("Account no longer exists") else parseError, onResult)
            return
        }
        // A credential/URL edit must not wipe the playlist options — but provider-specific
        // ones only carry when the edit still targets the same playlist (see carryPlaylistOptions).
        val carried = carryPlaylistOptions(old, candidate, keepCandidateFormOptions).copy(id = oldId)
        // Whatever built the candidate, a managed playlist never leaves here with a provider-owned field
        // changed: the server would silently detach it (ManagedEditPolicy).
        val account = if (isManagedPlaylist(oldId)) ManagedEditPolicy.lockProviderFields(old, carried) else carried
        val profileAtStart = currentProfileId
        // Step 0.3: an edited server list (main or backups) starts over on the main server — the old
        // active index may now name a different server or none. Before the verify below, which itself
        // may legitimately land on a backup.
        if (serverListChanged(old, account)) PlaylistServerFailover.reset(oldId)
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            // Options-only edit (name/EPG/DNS/refresh) — nothing about how we reach the provider
            // changed, so it is not checked at all. See sameConnectionAs.
            val verified =
                if (!PlaylistEditVerifyPolicy.needsVerify(old, account)) Result.success(Unit)
                else verifyForTest?.invoke(account) ?: IptvClient.forAccount(account).verify(account)
            // Decision 2026-09-27: a failed check saves anyway and warns (never discards the edit).
            val outcome = PlaylistEditVerifyPolicy.outcome(verified, account.sourceType)
            // UX11: the row warning carries the mapped sentence, never the raw exception text.
            val warning = outcome.failure?.let { playlistSavedUnverifiedWarning(it) }
            // Profile switched while verifying — see verifyAndSave.
            if (currentProfileId != profileAtStart) {
                _uiState.update { it.copy(isValidating = false) }
                onResult(false)
                return@launch
            }
            _uiState.update { st ->
                st.copy(
                    // Replace in place — same id (Step 0), so nothing else in the list moves.
                    accounts = st.accounts.map { if (it.id == oldId) account else it },
                    isValidating = false,
                    saveWarnings = (st.saveWarnings - oldId) +
                        (warning?.let { mapOf(account.id to it) } ?: emptyMap()),
                )
            }
            // A changed M3U URL invalidates the old catalog rows (same id, other source) — drop them.
            if (old.sourceType == SOURCE_TYPE_M3U_URL && old.baseUrl != account.baseUrl) M3UClient.clear(old)
            // Re-run the discovery cycle: drop caches/URLs built with the old server/creds.
            XtreamItemRegistry.resetForProfile()
            XtreamHubRepository.resetForProfile()
            XtreamSearchIndex.resetForProfile()
            XtreamTmdbResolver.warmUp(listOf(account))
            // B24 v2: durable "user edited this playlist" intent — always an update now (Step 0: the
            // id never changes on edit, so the B60 replace op is only replayed from older pending logs).
            recordPending { it.recordUpdate(account, base = old) }
            persistAndReport(onResult)
            // F14: new EPG URLs (or a new server/login) take effect now, not at the 12-hour refresh.
            if (guideSourcesChanged(old, account)) com.nuvio.app.features.iptv.epg.XmltvClient.refreshNow(account)
        }
    }

    /**
     * Step 0 — adopts the server's playlist keys for a pulled set ([PlaylistKeyAdoption]) and executes
     * every re-key it decides, BEFORE the pulled set is applied. Returns the rows as they should be
     * applied. A no-op (no store touched) when every id already matches — so a repeated pull is free.
     */
    internal fun adoptFromPull(profileId: Int, pulled: List<PulledPlaylist>): PlaylistKeyAdoption.Result {
        val result = PlaylistKeyAdoption.resolve(pulled, _uiState.value.accounts)
        adoptPlaylistKeys(profileId, result.rekeys)
        return result
    }

    /**
     * Step 0 — moves each local playlist id in [rekeys] onto its server key, once:
     *  - the account (and its row warning) is renamed in place and stored locally — no push echo, the
     *    pull that decided this already carries the key;
     *  - the durable v2 pending log is rewritten onto the new id, so a not-yet-synced edit still lands;
     *  - the prefix-keyed user data follows ([rekeySavedData] — the same prefix rewrite an id-changing
     *    edit used to do; see it for the synced writes it produces);
     *  - a file playlist's saved copy is moved to the new id's path (its storage is keyed by id);
     *  - caches built under the old id are purged ([PlaylistRemovalOrigin.SyncPull]: caches only, never
     *    user data) and rebuild under the new one.
     * The overlay (hidden/pinned channels) cannot follow: its keys hash the old id (accepted, Step 0).
     */
    internal fun adoptPlaylistKeys(profileId: Int, rekeys: List<PlaylistKeyAdoption.Rekey>) {
        if (rekeys.isEmpty() || profileId != currentProfileId) return
        val map = rekeys.associate { it.oldId to it.newId }
        _uiState.update { st ->
            st.copy(
                accounts = st.accounts.map { acc -> map[acc.id]?.let { acc.copy(id = it) } ?: acc },
                saveWarnings = st.saveWarnings.mapKeys { (id, _) -> map[id] ?: id },
            )
        }
        if (!damaged) {
            val merged = mergePlaylistJson(json, lastStoredRaw, _uiState.value.accounts)
            val wrote = runCatching {
                persistWriteForTest?.invoke(profileId, merged) ?: XtreamAccountStorage.saveAccountsJson(profileId, merged)
            }.isSuccess
            if (wrote) lastStoredRaw = merged
        }
        val st = decodePlaylistSyncState(XtreamAccountStorage.loadPlaylistSyncStateJson(profileId))
        if (st.pending.isNotEmpty()) {
            XtreamAccountStorage.savePlaylistSyncStateJson(
                profileId,
                encodePlaylistSyncState(st.copy(pending = PlaylistKeyAdoption.rewritePending(st.pending, rekeys))),
            )
        }
        for (rekey in rekeys) {
            runCatching { rekeySavedData(rekey.oldId, rekey.newId) }
            runCatching { moveM3UFile(rekey.oldId, rekey.newId) }
        }
        purgeRemovedPlaylists(rekeys.map { it.oldId }, PlaylistRemovalOrigin.SyncPull)
    }

    /**
     * Rewrites every saved `xtream:{oldId}:…` id to `xtream:{newId}:…` — library (incl. live
     * favourites), watch progress, watched marks, recent channels. Synced writes this produces, per
     * store (each is the repository's own migrateIdPrefix, unchanged from the old edit path):
     *  - library: one delta push — upsert of every moved item under the new id + delete of the old ids;
     *  - watch progress: a delete of every old entry + a scrobble (upsert) of every moved entry;
     *  - watched: a delete of every old mark + an upsert (pushMarks) of every moved mark;
     *  - recents: device-local, nothing synced.
     * Nothing is dropped: every entry under the old prefix is re-written, never deleted without its
     * replacement. A store with nothing under the old prefix makes no write at all.
     */
    private fun rekeySavedData(oldId: String, newId: String) {
        val oldPrefix = XtreamItemRegistry.accountPrefix(oldId)
        val newPrefix = XtreamItemRegistry.accountPrefix(newId)
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
        val before = _uiState.value.accounts.firstOrNull { it.id == id }
        // Defence in depth: no option edit may change a field the provider owns on a managed playlist.
        val guarded: (XtreamAccount) -> XtreamAccount =
            if (before != null && isManagedPlaylist(id)) { acc -> ManagedEditPolicy.lockProviderFields(before, transform(acc)) }
            else transform
        _uiState.update { st ->
            st.copy(accounts = st.accounts.map { if (it.id == id) guarded(it) else it })
        }
        // B04: a synced option (content types, category picks) must be recorded as a v2 edit, or the
        // next sync adopts the server's older row and reverts it. Device-local prefs push nothing.
        val after = _uiState.value.accounts.firstOrNull { it.id == id }
        if (before != null && after != null && optionEditNeedsSync(before, after)) {
            recordPending { it.recordUpdate(after, base = before) }
        }
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val before = _uiState.value.accounts.firstOrNull { it.id == id }
        _uiState.update { state ->
            state.copy(accounts = state.accounts.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
        if (enabled) _uiState.value.accounts.firstOrNull { it.id == id }?.let { XtreamTmdbResolver.warmUp(listOf(it)) }
        _uiState.value.accounts.firstOrNull { it.id == id }?.let { recordPending { ops -> ops.recordUpdate(it, base = before) } }
        persist()
    }

    fun remove(id: String) {
        recordPending { ops -> ops.recordDelete(id) }
        _uiState.update { it.copy(accounts = it.accounts.filterNot { acc -> acc.id == id }, saveWarnings = it.saveWarnings - id) }
        // Everything keyed by this id leaks otherwise: caches and indexes sit on disk forever (a
        // parsed M3U catalog can be hundreds of MB), and saved refs would be dead ids (phantom
        // favorites / continue-watching rows). What goes is decided by PlaylistRemovalCleanup.
        purgeRemovedPlaylists(listOf(id), PlaylistRemovalOrigin.UserDelete)
        persist()
    }

    /**
     * Executes [PlaylistRemovalCleanup]'s plan for playlists that left this profile's list. Every step
     * is isolated — one store failing never stops the rest. In-memory and prefs steps run on the
     * caller's thread (the same one that just mutated the account list); the disk purges run on
     * [scope] so a large catalog delete never blocks it.
     */
    private fun purgeRemovedPlaylists(ids: List<String>, origin: PlaylistRemovalOrigin) {
        if (ids.isEmpty()) return
        val plan = PlaylistRemovalCleanup.plan(origin)
        val profileId = currentProfileId
        // Session caches are shared across playlists — one reset covers every removed id.
        if (PlaylistRemovalTarget.SessionCaches in plan) runCatching {
            XtreamItemRegistry.resetForProfile()
            XtreamHubRepository.resetForProfile()
            XtreamSearchIndex.resetForProfile()
        }
        for (id in ids) {
            val prefix = XtreamItemRegistry.accountPrefix(id)
            for (target in plan) runCatching {
                when (target) {
                    PlaylistRemovalTarget.RefreshStamp -> IptvRefreshScheduler.forget(profileId, id)
                    PlaylistRemovalTarget.CatchUp -> CatchUpEpgRepository.forget(id)
                    PlaylistRemovalTarget.ServerFailover -> PlaylistServerFailover.forget(profileId, id)
                    PlaylistRemovalTarget.HubSelection -> forgetHubSelection(profileId, id)
                    PlaylistRemovalTarget.Overlay ->
                        com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.onPlaylistRemoved(id)
                    PlaylistRemovalTarget.LiveChannels -> XtreamLiveRecents.migrateIdPrefix(prefix, null)
                    PlaylistRemovalTarget.SavedRefs -> {
                        // Live favourites are library entries here, so they ride this step.
                        LibraryRepository.migrateIdPrefix(prefix, null)
                        WatchProgressRepository.migrateIdPrefix(prefix, null)
                        WatchedRepository.migrateIdPrefix(prefix, null)
                    }
                    // On disk — below, off the caller's thread. SessionCaches ran once above.
                    PlaylistRemovalTarget.ContentDb, PlaylistRemovalTarget.MatchIndex,
                    PlaylistRemovalTarget.EpgMirror, PlaylistRemovalTarget.M3uFileCopy,
                    PlaylistRemovalTarget.SessionCaches -> Unit
                }
            }
            scope.launch {
                for (target in plan) runCatching {
                    when (target) {
                        // Every source type: Xtream fills the per-playlist EPG tables too (xmltv
                        // store lane + catch-up refills), not just M3U/Stalker catalogs.
                        PlaylistRemovalTarget.ContentDb -> IptvContentDb.clear(id)
                        PlaylistRemovalTarget.MatchIndex -> XtreamMatchIndex.purge(id)
                        PlaylistRemovalTarget.EpgMirror ->
                            com.nuvio.app.features.epg.EpgMirrorRepository.purgeProvider(id)
                        // No-op unless this was a file playlist with a saved copy.
                        PlaylistRemovalTarget.M3uFileCopy -> deleteM3UFile(id)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Forget the hub's remembered provider when it is the removed playlist (keeps the tab). */
    private fun forgetHubSelection(profileId: Int, removedId: String) {
        val stored = parseHubSelection(XtreamAccountStorage.loadHubSelectionJson(profileId)) ?: return
        if (!PlaylistRemovalCleanup.dropsHubSelection(stored.accountId, removedId)) return
        XtreamAccountStorage.saveHubSelectionJson(profileId, encodeHubSelection(stored.copy(accountId = null)))
    }

    /** Drop credential-bearing in-memory state after sign-out or account deletion. */
    fun clearLocalState() {
        loaded = false
        authoritative = false
        damaged = false
        lastStoredRaw = null
        currentProfileId = 1
        _uiState.value = XtreamUiState()
        XtreamItemRegistry.resetForProfile()
        XtreamHubRepository.resetForProfile()
        XtreamSearchIndex.resetForProfile()
        ManagedInfoRepository.clearLocalState()
        ManagedInfoRefresher.clearLocalState()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /**
     * Shows a form error resolved to its localized sentence. Resolution suspends (string resources),
     * so the error lands — and [onResult] fires — from [scope], like every verify failure already did.
     */
    private fun reportFormError(message: PlaylistSaveMessage, onResult: (Boolean) -> Unit) {
        scope.launch {
            val text = message.text()
            _uiState.update { it.copy(isValidating = false, error = text) }
            onResult(false)
        }
    }

    /** The form error for manually-entered Xtream fields that did not build an account. */
    private fun xtreamFieldsError(serverUrl: String, username: String, password: String): PlaylistSaveMessage =
        PlaylistSaveMessage.Known(
            PlaylistSaveErrorPolicy.validate(
                XtreamFormInput(
                    serverUrl = serverUrl, username = username, password = password, name = null,
                    epgUrl = null, dnsProvider = "system", autoRefreshHours = 0,
                ),
            ) ?: PlaylistSaveError.INVALID_ADDRESS,
        )

    private val INVALID_ADDRESS = PlaylistSaveMessage.Known(PlaylistSaveError.INVALID_ADDRESS)
    private val PARSE_URL_ERROR = PlaylistSaveMessage.Authored("Couldn't read a username & password from that URL")

    /** Replace this profile's accounts from a remote pull WITHOUT echoing a push back. */
    fun applyFromRemote(profileId: Int, accounts: List<XtreamAccount>) {
        loaded = true
        // The server copy is authoritative — a pull heals a locally-recovered/corrupt/absent store.
        authoritative = true
        damaged = false
        currentProfileId = profileId
        val before = _uiState.value.accounts
        _uiState.update { it.copy(accounts = accounts) }
        // Step 0.3: a server list changed on another device restarts this device on the main server.
        val beforeById = before.associateBy { it.id }
        for (acc in accounts) {
            val prior = beforeById[acc.id] ?: continue
            if (serverListChanged(prior, acc)) PlaylistServerFailover.reset(acc.id)
        }
        // The server carries only known columns, so a pull's blob has no unknown keys to preserve.
        val encoded = json.encodeToString(accounts)
        XtreamAccountStorage.saveAccountsJson(profileId, encoded)
        lastStoredRaw = encoded
        if (before != accounts) {
            // Same discovery-cycle reset as a local edit: cached stream URLs embed the old
            // server/creds, and a playlist deleted on another device leaves index rows behind.
            XtreamItemRegistry.resetForProfile()
            XtreamHubRepository.resetForProfile()
            XtreamSearchIndex.resetForProfile()
            // Deleted on another device: the same cache purge as a local delete, but never the user's
            // own data — a pull can be transient (see PlaylistRemovalOrigin.SyncPull).
            purgeRemovedPlaylists(
                PlaylistRemovalCleanup.removedIds(before.map { it.id }, accounts.map { it.id }),
                PlaylistRemovalOrigin.SyncPull,
            )
        }
        // An account added on another device should index here before its first play.
        XtreamTmdbResolver.warmUp(accounts)
    }

    /**
     * Persist the in-memory accounts durably and, on success, authorize a sync push. Returns whether
     * the state was DURABLY written: false means the caller must treat the mutation as not-saved (no
     * false success — B24 §3). A false return leaves the in-memory edit pending (not reverted).
     */
    /**
     * B24 v2 — append a user mutation to the durable per-profile pending-op log (a SEPARATE storage
     * key that survives an accounts-store corruption reset). Only records when the v2 sync path is
     * active; in v1 the log is never consumed or grown. This is what lets "reset → add C" reconcile
     * to A+B+C: the accounts blob may be wiped, but the "add C" intent persists here.
     */
    private fun recordPending(transform: (List<PendingOpDto>) -> List<PendingOpDto>) {
        val cur = decodePlaylistSyncState(XtreamAccountStorage.loadPlaylistSyncStateJson(currentProfileId))
        // A profile that already advanced a v2 revision (or holds pending v2 ops) has ADOPTED v2, so it
        // keeps recording pending even while paused — never silently dropping intent (B24 activation).
        val adopted = cur.revision > 0 || cur.pending.isNotEmpty()
        if (!PlaylistSyncConfig.recordsPending(adopted)) return
        XtreamAccountStorage.savePlaylistSyncStateJson(
            currentProfileId,
            encodePlaylistSyncState(cur.copy(pending = transform(cur.pending)))
        )
    }

    private fun persist(): Boolean {
        // A damaged load (Recovered/Corrupt) must not overwrite the stored bytes (which hold rows or
        // forward-compat fields this build can't decode) with a truncated snapshot, nor push that
        // subset up (B24). Preserve the original for a future build or a remote pull to heal.
        if (damaged) return false
        // Preserve any forward-compat per-row keys this build can't decode (B24 §3) instead of
        // stripping them via a plain re-encode.
        val merged = mergePlaylistJson(json, lastStoredRaw, _uiState.value.accounts)
        // B24 §3/§4 — a FAILED local write must not report success, must not authorize a push, and
        // must not promote the state to authoritative. saveAccountsJson here is a SYNCHRONOUS
        // platform write (Android SharedPreferences.apply / iOS NSUserDefaults) — not a suspend
        // call — so no CancellationException can originate inside this runCatching; it only ever
        // captures a genuine storage failure. If the store did not accept the bytes we bail BEFORE
        // touching authoritative/lastStoredRaw/the push: authority is unchanged (a fresh store stays
        // non-authoritative, so a later login-flush cannot full-replace the server from an
        // unpersisted edit), lastStoredRaw still points at the last durable bytes, and the in-memory
        // state keeps the pending edit (recovery contract: pending-in-memory, not yet durable — the
        // next successful edit re-attempts the whole persist).
        val wrote = runCatching {
            val override = persistWriteForTest
            if (override != null) override(currentProfileId, merged)
            else XtreamAccountStorage.saveAccountsJson(currentProfileId, merged)
        }.isSuccess
        if (!wrote) return false
        // Only a SUCCESSFULLY-persisted authored set is authoritative — even when it is empty (the
        // user deleted their last playlist) or was built up from a fresh/Absent store. This is what
        // lets a genuine delete-all push while a corruption-reset (Absent, never persisted) stays
        // withheld, and what keeps a failed write from ever authorizing a push.
        authoritative = true
        lastStoredRaw = merged
        XtreamAccountSyncService.triggerPush()
        return true
    }

    /**
     * Persist a just-verified add/edit and report the DURABLE outcome to the form (B24 §3): a failed
     * write reports failure with a user-facing error instead of falsely dismissing as saved. The
     * in-memory edit stays pending (visible) — it is not rolled back — so a retry re-attempts it.
     */
    private fun persistAndReport(onResult: (Boolean) -> Unit) {
        if (persist()) {
            onResult(true)
        } else {
            _uiState.update { it.copy(error = "Couldn't save the playlist on this device. Free up space and try again.") }
            onResult(false)
        }
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
        // Step 0.3: the full form shows the backup list, so its candidate wins; every other edit
        // path (paste-URL / manual fields) doesn't carry it and must not clear it.
        backupUrls = if (keepCandidateFormOptions) candidate.backupUrls else old.backupUrls,
        // Device-local prefs set from the playlist's own cards, never from the edit form (F10 clean-up +
        // tags, prefer-m3u8 catch-up, catch-up and guide offsets) — an edit used to reset them all
        // (device pass 2026-10-05; TV twin: asEditOf). The LEARNED catch-up winner is a fact about the
        // server, so it only carries while the server is unchanged.
        cleanChannelNames = old.cleanChannelNames,
        channelNameTags = old.channelNameTags,
        catchUpPreferM3u8 = old.catchUpPreferM3u8,
        catchUpTimeCorrectionMinutes = old.catchUpTimeCorrectionMinutes,
        guideEpgCorrectionMinutes = old.guideEpgCorrectionMinutes,
        catchUpWinner = if (old.baseUrl == candidate.baseUrl) old.catchUpWinner else null,
    )
}

/**
 * F14 — whether an edit changed where this playlist's guide comes from (its EPG URL list, or the server
 * / login its own xmltv.php and url-tvg are reached with). Such an edit re-ingests the guide at once;
 * otherwise the old sources' guide stayed until the 12-hour refresh. internal for tests.
 */
internal fun guideSourcesChanged(old: XtreamAccount, new: XtreamAccount): Boolean =
    old.epgUrl?.trim().orEmpty() != new.epgUrl?.trim().orEmpty() ||
        old.baseUrl != new.baseUrl || old.username != new.username || old.password != new.password

/** Step 0.3: whether an edit/pull changed which servers a playlist is reached on (main or backups). */
internal fun serverListChanged(old: XtreamAccount, new: XtreamAccount): Boolean =
    old.baseUrl != new.baseUrl || old.backupUrls != new.backupUrls

/** Stremio content types the enabled accounts serve through the IPTV source lane (live is not a VOD source). */
internal fun servedStreamTypesOf(accounts: List<XtreamAccount>): Set<String> =
    accounts.filter { it.enabled }.flatMap { account ->
        buildList {
            if (account.typeEnabled(CONTENT_TYPE_MOVIES)) add("movie")
            if (account.typeEnabled(CONTENT_TYPE_SERIES)) add("series")
        }
    }.toSet()
