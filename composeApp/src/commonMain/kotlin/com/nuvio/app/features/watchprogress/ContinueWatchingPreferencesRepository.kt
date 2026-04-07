package com.nuvio.app.features.watchprogress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredContinueWatchingPreferences(
    val isVisible: Boolean = true,
    val style: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Wide,
    val upNextFromFurthestEpisode: Boolean = true,
    val dismissedNextUpKeys: Set<String> = emptySet(),
)

object ContinueWatchingPreferencesRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(ContinueWatchingPreferencesUiState())
    val uiState: StateFlow<ContinueWatchingPreferencesUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

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

    private fun loadFromDisk() {
        hasLoaded = true

        val payload = ContinueWatchingPreferencesStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = ContinueWatchingPreferencesUiState()
            return
        }

        val stored = runCatching {
            json.decodeFromString<StoredContinueWatchingPreferences>(payload)
        }.getOrNull()

        _uiState.value = if (stored != null) {
            ContinueWatchingPreferencesUiState(
                isVisible = stored.isVisible,
                style = stored.style,
                upNextFromFurthestEpisode = stored.upNextFromFurthestEpisode,
                dismissedNextUpKeys = stored.dismissedNextUpKeys,
            )
        } else {
            ContinueWatchingPreferencesUiState()
        }
    }

    fun setVisible(isVisible: Boolean) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(isVisible = isVisible)
        persist()
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

    fun addDismissedNextUpKey(key: String) {
        ensureLoaded()
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) return
        val current = _uiState.value.dismissedNextUpKeys
        if (normalizedKey in current) return
        _uiState.value = _uiState.value.copy(dismissedNextUpKeys = current + normalizedKey)
        persist()
    }

    fun removeDismissedNextUpKeysForContent(contentId: String) {
        ensureLoaded()
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return
        val prefix = "$normalizedContentId|"
        val filtered = _uiState.value.dismissedNextUpKeys.filterNot { it.startsWith(prefix) }.toSet()
        if (filtered == _uiState.value.dismissedNextUpKeys) return
        _uiState.value = _uiState.value.copy(dismissedNextUpKeys = filtered)
        persist()
    }

    private fun persist() {
        ContinueWatchingPreferencesStorage.savePayload(
            json.encodeToString(
                StoredContinueWatchingPreferences(
                    isVisible = _uiState.value.isVisible,
                    style = _uiState.value.style,
                    upNextFromFurthestEpisode = _uiState.value.upNextFromFurthestEpisode,
                    dismissedNextUpKeys = _uiState.value.dismissedNextUpKeys,
                ),
            ),
        )
    }
}
