package com.nuvio.app.features.radar

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A fetch result is time-stamped BEFORE it is published: the first composition that sees the new state must find its
 * score already in the delay buffer, or it arms no re-render timer and a delayed score waits for the next poll.
 * kotlin.test → assertEquals(expected, actual, message).
 */
class RadarRecordBeforePublishTest {

    /** Captures, at the moment a new state is published, whether its score was already recorded. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class PublishSpy(private val inner: MutableStateFlow<RadarUiState>) : MutableStateFlow<RadarUiState> by inner {
        val recordedAtPublish = mutableListOf<Boolean>()
        private fun note(next: RadarUiState) { recordedAtPublish += next.scoreLedger.buffer.contains("e1") }
        override var value: RadarUiState
            get() = inner.value
            set(next) { note(next); inner.value = next }
        override fun compareAndSet(expect: RadarUiState, update: RadarUiState): Boolean {
            note(update)
            return inner.compareAndSet(expect, update)
        }
    }

    @Test
    fun `a fetched score is in the buffer when the state is published`() {
        val live = RadarFixture(id = "e1", leagueId = "4328", sport = "Soccer", home = "H", away = "A", ts = "2026-08-25T18:00:00")
        val spy = PublishSpy(MutableStateFlow(RadarUiState(prefs = RadarPrefs(scoreDelaySeconds = 30))))
        spy.updateRecorded(live.startEpochMs!! + 60_000L) {
            it.copy(
                fixturesByLeague = mapOf("4328" to listOf(live)),
                liveEventIds = setOf("e1"),
                liveScores = mapOf("e1" to RadarLiveScore("e1", homeScore = "1", awayScore = "0")),
                livescoreSports = setOf("soccer"),
            )
        }
        assertEquals(listOf(true), spy.recordedAtPublish, "recorded before the UI could see it")
    }
}
