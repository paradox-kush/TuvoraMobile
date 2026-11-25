package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeSectionIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredContinueWatchingPreferences(
    val isVisible: Boolean = true,
    val style: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Card,
    val upNextFromFurthestEpisode: Boolean = true,
    @SerialName("use_episode_thumbnails_in_cw")
    val useEpisodeThumbnails: Boolean = true,
    @SerialName("show_unaired_next_up")
    val showUnairedNextUp: Boolean = true,
    @SerialName("blur_continue_watching_next_up")
    val blurNextUp: Boolean = false,
    val dismissedNextUpKeys: Set<String> = emptySet(),
    val showResumePromptOnLaunch: Boolean = true,
    @SerialName("sort_mode")
    val sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
    // Device-local like sortMode: the TV app has one row and nothing to sync it with.
    @SerialName("split_continue_watching_by_type")
    val splitByType: Boolean = false,
    @SerialName("click_action")
    val clickAction: ContinueWatchingClickAction = ContinueWatchingClickAction.STREAM,
    // "Pick up where I left off". A NEW key, not a new ContinueWatchingSortMode value: this blob is synced
    // verbatim and older clients decode sort_mode strictly (an unknown enum value there throws away the whole
    // payload and resets every Continue Watching preference). Old clients ignore this key. null = no choice
    // recorded (existing users: recency order); see ContinueWatchingOrderDefaultPolicy.
    @SerialName("resume_first_order")
    val resumeFirst: Boolean? = null,
)

private val continueWatchingPreferencesJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // A value from a future version (an enum entry this build does not know) falls back to the field's
    // default instead of discarding the whole payload.
    coerceInputValues = true
}

/** Decodes the persisted/synced payload; null when it is not usable. Pure so it is testable. */
internal fun decodeContinueWatchingPreferences(payload: String): ContinueWatchingPreferencesUiState? {
    val stored = runCatching {
        continueWatchingPreferencesJson.decodeFromString<StoredContinueWatchingPreferences>(payload)
    }.getOrNull() ?: return null
    return ContinueWatchingPreferencesUiState(
        isVisible = stored.isVisible,
        style = stored.style,
        upNextFromFurthestEpisode = stored.upNextFromFurthestEpisode,
        useEpisodeThumbnails = stored.useEpisodeThumbnails,
        showUnairedNextUp = stored.showUnairedNextUp,
        blurNextUp = stored.blurNextUp,
        dismissedNextUpKeys = stored.dismissedNextUpKeys.map { it.trim().substringBefore("|") }
            .filter(String::isNotBlank).toSet(),
        showResumePromptOnLaunch = stored.showResumePromptOnLaunch,
        sortMode = stored.sortMode,
        splitByType = stored.splitByType,
        clickAction = stored.clickAction,
        resumeFirst = stored.resumeFirst,
    )
}

object ContinueWatchingPreferencesRepository {
    private val json = continueWatchingPreferencesJson

    private val _uiState = MutableStateFlow(ContinueWatchingPreferencesUiState())
    val uiState: StateFlow<ContinueWatchingPreferencesUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    // One-shot "is this a brand-new install?" probe, registered by the composition root. Asked at the first
    // load from disk (every platform's storage is initialised by then) and then dropped, so a later profile
    // switch or sync pull never re-decides.
    private var installProbe: (() -> Boolean)? = null

    fun registerInstallProbe(installHasUserState: () -> Boolean) {
        installProbe = installHasUserState
    }

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = ContinueWatchingPreferencesUiState()
    }

    internal fun applyFromSync(
        isVisible: Boolean,
        style: ContinueWatchingSectionStyle,
        upNextFromFurthestEpisode: Boolean,
        useEpisodeThumbnails: Boolean = true,
        showUnairedNextUp: Boolean = true,
        blurNextUp: Boolean = false,
        dismissedNextUpKeys: Set<String>,
    ) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(
            isVisible = isVisible,
            style = style,
            upNextFromFurthestEpisode = upNextFromFurthestEpisode,
            useEpisodeThumbnails = useEpisodeThumbnails,
            showUnairedNextUp = showUnairedNextUp,
            blurNextUp = blurNextUp,
            dismissedNextUpKeys = normalizeDismissedNextUpKeys(dismissedNextUpKeys),
        )
        persist()
        HomeCatalogSettingsRepository.mirrorLegacyToggle(HomeSectionIds.CONTINUE_WATCHING, isVisible)
    }

    private fun loadFromDisk() {
        hasLoaded = true

        val payload = ContinueWatchingPreferencesStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = ContinueWatchingPreferencesUiState()
            resolveInitialOrderOnce()
            return
        }

        _uiState.value = decodeContinueWatchingPreferences(payload) ?: ContinueWatchingPreferencesUiState()
        resolveInitialOrderOnce()
    }

    /**
     * First-run only: records "Pick up where I left off" as the order for a NEW install. [ContinueWatchingOrderDefaultPolicy]
     * decides from what is on disk: anyone with a stored choice, a synced payload or existing user state is
     * left exactly as they were.
     */
    private fun resolveInitialOrderOnce() {
        val probe = installProbe ?: return
        installProbe = null
        val payload = ContinueWatchingPreferencesStorage.loadPayload().orEmpty().trim()
        val initial = ContinueWatchingOrderDefaultPolicy.initialResumeFirst(
            storedResumeFirst = _uiState.value.resumeFirst,
            hasStoredPayload = payload.isNotEmpty(),
            installHasUserState = { runCatching(probe).getOrDefault(true) },
        ) ?: return
        _uiState.value = _uiState.value.copy(resumeFirst = initial)
        persist()
    }

    fun setVisible(isVisible: Boolean) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(isVisible = isVisible)
        persist()
        // Home's section list has a Continue Watching switch too: keep the two in step.
        HomeCatalogSettingsRepository.mirrorLegacyToggle(HomeSectionIds.CONTINUE_WATCHING, isVisible)
    }

    fun setStyle(style: ContinueWatchingSectionStyle) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(style = style)
        persist()
    }

    fun setUpNextFromFurthestEpisode(enabled: Boolean) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(upNextFromFurthestEpisode = enabled)
        persist()
    }

    fun setUseEpisodeThumbnails(enabled: Boolean) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(useEpisodeThumbnails = enabled)
        persist()
    }

    fun setShowUnairedNextUp(enabled: Boolean) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(showUnairedNextUp = enabled)
        persist()
    }

    fun setBlurNextUp(enabled: Boolean) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(blurNextUp = enabled)
        persist()
    }

    fun addDismissedNextUpKey(key: String) {
        ensureLoaded()
        val normalizedKey = key.trim().substringBefore("|")
        if (normalizedKey.isBlank()) return
        val current = _uiState.value.dismissedNextUpKeys
        if (normalizedKey in current) return
        _uiState.value = _uiState.value.copy(dismissedNextUpKeys = current + normalizedKey)
        persist()
    }

    fun setShowResumePromptOnLaunch(enabled: Boolean) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(showResumePromptOnLaunch = enabled)
        persist()
    }

    fun setSplitByType(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.splitByType == enabled) return
        _uiState.value = _uiState.value.copy(splitByType = enabled)
        persist()
    }

    /** Picking any recency-based order is an explicit choice: it also ends "Pick up where I left off". */
    fun setSortMode(mode: ContinueWatchingSortMode) {
        ensureLoaded()
        if (_uiState.value.sortMode == mode && _uiState.value.resumeFirst == false) return
        _uiState.value = _uiState.value.copy(sortMode = mode, resumeFirst = false)
        persist()
    }

    /** Explicit choice of "Pick up where I left off". [sortMode] is left untouched for older clients. */
    fun setResumeFirst() {
        ensureLoaded()
        if (_uiState.value.resumeFirst == true) return
        _uiState.value = _uiState.value.copy(resumeFirst = true)
        persist()
    }

    fun setClickAction(action: ContinueWatchingClickAction) {
        ensureLoaded()
        if (_uiState.value.clickAction == action) return
        _uiState.value = _uiState.value.copy(clickAction = action)
        persist()
    }

    fun removeDismissedNextUpKeysForContent(contentId: String) {
        ensureLoaded()
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return
        val prefix = "$normalizedContentId|"
        val filtered = _uiState.value.dismissedNextUpKeys.filterNot {
            it == normalizedContentId || it.startsWith(prefix)
        }.toSet()
        if (filtered == _uiState.value.dismissedNextUpKeys) return
        _uiState.value = _uiState.value.copy(dismissedNextUpKeys = filtered)
        persist()
    }

    private fun normalizeDismissedNextUpKeys(keys: Set<String>): Set<String> =
        keys.map { it.trim().substringBefore("|") }
            .filter(String::isNotBlank)
            .toSet()

    private fun persist() {
        ContinueWatchingPreferencesStorage.savePayload(
            json.encodeToString(
                StoredContinueWatchingPreferences(
                    isVisible = _uiState.value.isVisible,
                    style = _uiState.value.style,
                    upNextFromFurthestEpisode = _uiState.value.upNextFromFurthestEpisode,
                    useEpisodeThumbnails = _uiState.value.useEpisodeThumbnails,
                    showUnairedNextUp = _uiState.value.showUnairedNextUp,
                    blurNextUp = _uiState.value.blurNextUp,
                    dismissedNextUpKeys = _uiState.value.dismissedNextUpKeys,
                    showResumePromptOnLaunch = _uiState.value.showResumePromptOnLaunch,
                    sortMode = _uiState.value.sortMode,
                    splitByType = _uiState.value.splitByType,
                    clickAction = _uiState.value.clickAction,
                    resumeFirst = _uiState.value.resumeFirst,
                ),
            ),
        )
    }
}
