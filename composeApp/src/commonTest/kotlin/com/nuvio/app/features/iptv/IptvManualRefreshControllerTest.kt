package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** UX179 — "Refresh now" ends honestly: only REFRESHED is success, a failure is Failed, one refresh at a time. */
class IptvManualRefreshControllerTest {
    private val account = XtreamAccount(id = "acc1", name = "Home", baseUrl = "http://panel.example:8080", username = "u", password = "p")

    private fun controller(refresh: suspend (XtreamAccount) -> ManualRefreshResult) =
        IptvManualRefreshController(CoroutineScope(SupervisorJob() + Dispatchers.Default), refresh)

    private suspend fun IptvManualRefreshController.settled(): ManualRefreshUiState =
        withTimeout(5_000) { state.first { it.load !is LoadStatus.Loading && it.load != LoadStatus.Idle } }

    @Test
    fun `a refresh that landed is Loaded`(): Unit = runBlocking {
        val c = controller { ManualRefreshResult.REFRESHED }
        c.start(account)
        val done = c.settled()
        assertEquals(LoadStatus.Loaded, done.load)
        assertEquals(ManualRefreshResult.REFRESHED, done.result)
    }

    @Test
    fun `a failed or partial refresh is Failed never done`(): Unit = runBlocking {
        for (bad in listOf(ManualRefreshResult.FAILED, ManualRefreshResult.PARTIAL)) {
            val c = controller { bad }
            c.start(account)
            val done = c.settled()
            assertIs<LoadStatus.Failed>(done.load)
            assertEquals(bad, done.result)
        }
    }

    @Test
    fun `an engine that throws ends Failed`(): Unit = runBlocking {
        val c = controller { error("provider down") }
        c.start(account)
        val done = c.settled()
        assertIs<LoadStatus.Failed>(done.load)
        assertEquals(ManualRefreshResult.FAILED, done.result)
    }

    @Test
    fun `cooldown stalker and missing-file answers are Loaded and carry their own result`(): Unit = runBlocking {
        for (answer in listOf(ManualRefreshResult.COOLDOWN, ManualRefreshResult.RELOAD_ON_OPEN, ManualRefreshResult.NEEDS_LOCAL_FILE)) {
            val c = controller { answer }
            c.start(account)
            val done = c.settled()
            assertEquals(LoadStatus.Loaded, done.load)
            assertEquals(answer, done.result)
        }
    }

    @Test
    fun `a second press while one is running does not start another`(): Unit = runBlocking {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val c = controller { calls++; release.await(); ManualRefreshResult.REFRESHED }
        c.start(account)
        c.start(account)
        c.start(account)
        delay(100)
        assertTrue(c.state.value.refreshing)
        release.complete(Unit)
        c.settled()
        assertEquals(1, calls)
    }

    @Test
    fun `a slow last-updated read cannot overwrite the result of a refresh that already finished`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val c = IptvManualRefreshController(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            refresh = { ManualRefreshResult.REFRESHED },
            lastRefreshAt = { if (reads++ == 0) { release.await(); 1L } else 999L },
        )
        c.load(account)
        c.start(account)
        val done = c.settled()
        assertEquals(999L, done.lastRefreshAtMs)
        release.complete(Unit)
        delay(150)
        assertEquals(999L, c.state.value.lastRefreshAtMs, "the stale read must not win")
    }
}
