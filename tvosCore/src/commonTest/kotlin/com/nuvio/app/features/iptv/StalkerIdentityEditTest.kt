package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalkerIdentityEditTest {
    @AfterTest
    fun reset() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
    }

    private fun form() = XtreamFormInput(
        serverUrl = "http://portal.example.test", username = "", password = "", name = "Renamed",
        epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
        sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:AB:CD:EF",
    )

    private fun edit(input: XtreamFormInput): XtreamAccount = runBlocking {
        val account = stalkerAccountFromForm(form())!!.copy(sendDeviceId = false)
        XtreamRepository.installAccountsForTest(listOf(account))
        XtreamRepository.persistWriteForTest = { _, _ -> }
        XtreamRepository.verifyForTest = { Result.success(Unit) }
        val done = CompletableDeferred<Boolean>()
        XtreamRepository.editFromForm(account.id, input) { done.complete(it) }
        assertTrue(withTimeout(10_000) { done.await() })
        XtreamRepository.uiState.value.accounts.single()
    }

    @Test
    fun `an edit omitting identity toggle preserves saved signature choice`() {
        val saved = edit(form())
        assertFalse(saved.sendDeviceId)
        assertEquals("Renamed", saved.name)
    }

    @Test
    fun `explicit toggle changes still take effect`() {
        assertTrue(edit(form().copy(sendDeviceId = true)).sendDeviceId)
    }

    @Test
    fun `new playlists retain signature enabled default`() {
        assertTrue(stalkerAccountFromForm(form())!!.sendDeviceId)
    }
}
