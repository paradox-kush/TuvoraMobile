package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * F48: search inside ONE playlist from the IPTV hub's Movies and Series sections. Live keeps its own guide
 * search (F01), and the main Search tab still mixes every playlist with add-ons — this is the scoped one
 * people asked for. Pure decisions only; the I/O lives in [XtreamHubSearchController].
 */
internal object IptvHubSearchPolicy {
    const val MIN_QUERY_LENGTH = 2

    /** A pause after the last keystroke before asking the playlist, so typing "matrix" is one lookup, not six. */
    const val DEBOUNCE_MS = 350L

    /** The content type a section searches, or null when the section has no hub search (Live). */
    fun contentTypeFor(section: XtreamHubSection): String? = when (section) {
        XtreamHubSection.MOVIES -> CONTENT_TYPE_MOVIES
        XtreamHubSection.SERIES -> CONTENT_TYPE_SERIES
        XtreamHubSection.LIVE -> null
    }

    /** The trimmed query to send, or null when no request should be made (wrong section, too short). */
    fun request(section: XtreamHubSection, raw: String): String? {
        if (contentTypeFor(section) == null) return null
        return raw.trim().takeIf { it.length >= MIN_QUERY_LENGTH }
    }
}

internal data class HubSearchUiState(
    val query: String = "",
    /** Entered only through [BoundedLoad]: a failed search is Failed (with Retry), never "no matches". */
    val load: LoadStatus = LoadStatus.Idle,
    val items: List<MetaPreview> = emptyList(),
)

/**
 * Screen-scoped state holder for the hub search (the composable only renders it). A newer query, a
 * playlist switch or a section switch cancels the one in flight, so a slow old answer can never overwrite
 * a newer one.
 */
internal class XtreamHubSearchController(
    private val scope: CoroutineScope,
    private val debounceMs: Long = IptvHubSearchPolicy.DEBOUNCE_MS,
    private val search: suspend (XtreamAccount, String, String) -> List<MetaPreview> =
        { account, type, query -> XtreamSearchIndex.searchPlaylist(account, type, query) },
) {
    private val mutableState = MutableStateFlow(HubSearchUiState())
    val state: StateFlow<HubSearchUiState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var scopeOf: Pair<XtreamAccount, XtreamHubSection>? = null

    fun onQueryChange(account: XtreamAccount, section: XtreamHubSection, raw: String) {
        scopeOf = account to section
        run(account, section, raw)
    }

    /** The viewer picked another playlist or section: the box keeps its text and re-asks the new scope. */
    fun onScopeChange(account: XtreamAccount, section: XtreamHubSection) {
        val previous = scopeOf
        scopeOf = account to section
        if (previous == account to section) return
        run(account, section, mutableState.value.query)
    }

    fun retry() {
        val (account, section) = scopeOf ?: return
        run(account, section, mutableState.value.query)
    }

    fun clear() {
        job?.cancel()
        mutableState.value = HubSearchUiState()
    }

    private fun run(account: XtreamAccount, section: XtreamHubSection, raw: String) {
        job?.cancel()
        val type = IptvHubSearchPolicy.contentTypeFor(section)
        val query = IptvHubSearchPolicy.request(section, raw)
        if (type == null || query == null) {
            mutableState.value = HubSearchUiState(query = if (type == null) "" else raw)
            return
        }
        mutableState.value = HubSearchUiState(query = raw, load = BoundedLoad.begin(LoadSurface.HUB_SEARCH))
        job = scope.launch {
            delay(debounceMs)
            val outcome = BoundedLoad.run(
                LoadSurface.HUB_SEARCH,
                isEmpty = { it.isEmpty() },
                report = mapOf("section" to type),
            ) { search(account, type, query) }
            mutableState.value = HubSearchUiState(query = raw, load = outcome.status, items = outcome.valueOrNull().orEmpty())
        }
    }
}
