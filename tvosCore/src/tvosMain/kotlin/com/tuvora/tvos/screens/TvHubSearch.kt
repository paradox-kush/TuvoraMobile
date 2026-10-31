package com.tuvora.tvos.screens

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadStatus
import com.nuvio.app.features.iptv.XtreamHubSearchController
import com.nuvio.app.features.iptv.XtreamHubSection
import com.nuvio.app.features.iptv.XtreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where the Apple TV hub's search is: one phase at a time, so a failed search can never read as "no matches". */
enum class TvHubSearchPhase { IDLE, LOADING, FAILED, EMPTY, LOADED }

/**
 * What the SwiftUI results page renders. [open] is true from the first submit until [TvIptvHubSearch.close]; the
 * rest mirrors the shared controller (F48). [msUntilDeadline] is set only while LOADING — the page wakes once at
 * it and asks [TvIptvHubSearch.effectivePhase], so a wait that never returns still ends on Retry.
 */
data class TvHubSearchState(
    val open: Boolean = false,
    val query: String = "",
    val phase: TvHubSearchPhase = TvHubSearchPhase.IDLE,
    val items: List<MetaPreview> = emptyList(),
    val msUntilDeadline: Long? = null,
)

/**
 * F48 on Apple TV: search inside ONE playlist's Movies or Series. Logic is the shared
 * [XtreamHubSearchController] (the phone's); the OS-facing parts — Siri Remote keyboard, results page, focus —
 * are SwiftUI. Searches on submit (OK on Search opens the keyboard), so no debounce.
 */
object TvIptvHubSearch {
    private val controller = XtreamHubSearchController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        debounceMs = 0L,
    )

    val state: Flow<TvHubSearchState> = controller.state.map { s ->
        val load = s.load
        TvHubSearchState(
            open = load != LoadStatus.Idle,
            query = s.query,
            phase = when (load) {
                LoadStatus.Idle -> TvHubSearchPhase.IDLE
                is LoadStatus.Loading -> TvHubSearchPhase.LOADING
                is LoadStatus.Failed -> TvHubSearchPhase.FAILED
                LoadStatus.Empty -> TvHubSearchPhase.EMPTY
                LoadStatus.Loaded -> TvHubSearchPhase.LOADED
            },
            items = s.items,
            msUntilDeadline = (load as? LoadStatus.Loading)?.let { (it.deadlineAtMs - BoundedLoad.clock()).coerceAtLeast(0) },
        )
    }

    fun submit(accountId: String, section: XtreamHubSection, query: String) {
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return
        controller.onQueryChange(account, section, query)
    }

    fun retry() = controller.retry()

    /** Back from the results, or another playlist/section was picked. */
    fun close() = controller.clear()

    /** LOADING past its own deadline is FAILED (the phone's rememberEffectiveLoadStatus). */
    fun effectivePhase(state: TvHubSearchState): TvHubSearchPhase =
        if (state.phase == TvHubSearchPhase.LOADING && (state.msUntilDeadline ?: 0L) <= 0L) TvHubSearchPhase.FAILED else state.phase
}
