package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyChatAvailabilityPolicyTest {

    // The supabase-kt 3.6.0 message shape for a function the backend does not have.
    private val missingFunctionMessage = """
        Could not find the function public.watch_party_list_messages(p_device_id, p_party_id) in the schema cache
        Code: PGRST202
        Hint: null
        Details: Searched for the function public.watch_party_list_messages
    """.trimIndent()

    @Test
    fun `chat starts hidden until the backend answers`() {
        assertFalse(PartyChatAvailabilityPolicy.next(previous = false, probe = PartyChatProbe.Inconclusive))
    }

    @Test
    fun `the first answered chat RPC reveals the tab`() {
        assertTrue(PartyChatAvailabilityPolicy.next(previous = false, probe = PartyChatProbe.Answered))
    }

    @Test
    fun `a missing chat RPC hides the tab even if it was showing`() {
        val probe = PartyChatAvailabilityPolicy.probeOfFailure(statusCode = 404, message = missingFunctionMessage)
        assertEquals(PartyChatProbe.MissingFunction, probe)
        assertFalse(PartyChatAvailabilityPolicy.next(previous = true, probe = probe), "a tab whose sends all fail")
    }

    @Test
    fun `a bare 404 on the RPC path is a missing function`() {
        assertEquals(PartyChatProbe.MissingFunction, PartyChatAvailabilityPolicy.probeOfFailure(404, null))
    }

    @Test
    fun `a transient failure keeps the previous verdict either way`() {
        val probe = PartyChatAvailabilityPolicy.probeOfFailure(statusCode = 503, message = "upstream timeout")
        assertEquals(PartyChatProbe.Inconclusive, probe)
        assertTrue(PartyChatAvailabilityPolicy.next(previous = true, probe = probe))
        assertFalse(PartyChatAvailabilityPolicy.next(previous = false, probe = probe))
    }

    @Test
    fun `a permission error from an existing function is not a missing function`() {
        val probe = PartyChatAvailabilityPolicy.probeOfFailure(403, "permission denied\nCode: 42501")
        assertEquals(PartyChatProbe.Inconclusive, probe)
    }
}
