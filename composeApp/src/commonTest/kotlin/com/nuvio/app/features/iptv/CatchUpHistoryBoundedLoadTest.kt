package com.nuvio.app.features.iptv

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UX275 (ported from the TV six-bugs fix): the catch-up history fetch swallowed every failure in runCatching, so a
 * panel that errored or never answered read as "this channel has no history". It is a BoundedLoad now: it ends,
 * and a failure is Failed, never empty (bounded-load rule).
 */
class CatchUpHistoryBoundedLoadTest {
    private class Fake(val onStream: suspend () -> Unit) : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?): String = throw IllegalStateException("HTTP 503")
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) = onStream()
    }

    private fun account(id: String) = XtreamAccount(
        id = id, name = "P", baseUrl = "http://hist-$id.test", username = "u", password = "p",
    )

    @BeforeTest
    fun setUp() {
        BoundedLoad.stallOverrideMsForTests = 150L
        BoundedLoad.resetReportsForTests()
    }

    @AfterTest
    fun tearDown() {
        BoundedLoad.stallOverrideMsForTests = null
        IptvTransport.current = IptvTransport.Platform
    }

    @Test
    fun `a history fetch the panel refuses is Failed and never empty`(): Unit = runBlocking {
        IptvTransport.current = Fake { throw IllegalStateException("HTTP 503") }

        val status = CatchUpEpgRepository.ensureHistory(account("refused"), 7)

        assertEquals(LoadStatus.Failed(timedOut = false), status, "a failed fetch must be reported as Failed")
    }

    @Test
    fun `a history fetch that never answers ends Failed at its deadline`(): Unit = runBlocking {
        IptvTransport.current = Fake { awaitCancellation() }

        val status = CatchUpEpgRepository.ensureHistory(account("hung"), 8)

        assertEquals(LoadStatus.Failed(timedOut = true), status, "the wait must end, not spin")
    }
}
