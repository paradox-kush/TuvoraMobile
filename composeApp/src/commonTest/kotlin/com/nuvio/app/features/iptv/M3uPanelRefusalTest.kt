package com.nuvio.app.features.iptv

import com.nuvio.app.core.sources.SourceDisabledOnDeviceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pasting a panel's get.php URL must not sidestep a refusal by saving the same login as a new m3u: playlist. */
class M3uPanelRefusalTest {
    @BeforeTest
    fun setUp() {
        XtreamRepository.persistWriteForTest = { _, _ -> }
        XtreamRepository.installAccountsForTest(emptyList())
    }

    @AfterTest
    fun reset() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
    }

    private fun pasted() = XtreamFormInput(
        serverUrl = "", username = "", password = "", name = "Pasted", epgUrl = null,
        dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_URL,
        m3uUrl = "http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus",
    )

    private fun add(input: XtreamFormInput): Boolean = runBlocking {
        val done = CompletableDeferred<Boolean>()
        XtreamRepository.addFromForm(input) { done.complete(it) }
        withTimeout(10_000) { done.await() }
    }

    @Test
    fun `a refused panel check shows the refusal and does not fall back to an m3u playlist`() {
        var checks = 0
        XtreamRepository.verifyForTest = { checks++; Result.failure(SourceDisabledOnDeviceException("iptv", "ready")) }
        assertFalse(add(pasted()))
        assertEquals(1, checks, "no second verify for a fallback m3u playlist")
        assertTrue(XtreamRepository.uiState.value.accounts.isEmpty(), "nothing saved")
        assertEquals(PlaylistSaveError.SOURCE_DISABLED_ON_DEVICE.fallbackText, XtreamRepository.uiState.value.error)
    }

    @Test
    fun `an ordinary panel failure still falls back to the m3u lane`() {
        var checks = 0
        XtreamRepository.verifyForTest = { acc -> checks++; if (checks == 1) Result.failure(IllegalStateException("api refuses")) else Result.success(Unit) }
        assertTrue(add(pasted()))
        assertEquals(2, checks)
    }
}
