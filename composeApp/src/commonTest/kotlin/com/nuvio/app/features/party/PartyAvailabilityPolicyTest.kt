package com.nuvio.app.features.party

import com.nuvio.app.core.network.officialConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyAvailabilityPolicyTest {

    // The real supabase-kt 3.6.0 message shape for a function the backend does not have.
    private val missingFunctionMessage = """
        Could not find the function public.watch_party_enabled without parameters in the schema cache
        Code: PGRST202
        Hint: null
        Details: Searched for the function public.watch_party_enabled without parameters
        URL: https://example.supabase.co/rest/v1/rpc/watch_party_enabled
        Headers: []
    """.trimIndent()

    @Test
    fun `the official Tuvora backend advertises watch parties`() {
        assertTrue(officialConfiguration().capabilities.watchParty, "official capability must be on")
    }

    @Test
    fun `a missing watch_party_enabled RPC turns the feature off instead of keeping it on`() {
        val probe = PartyAvailabilityPolicy.probeOfFailure(statusCode = 404, message = missingFunctionMessage)
        assertEquals(PartyEnabledProbe.MissingFunction, probe)
        assertFalse(PartyAvailabilityPolicy.nextEnabled(previous = true, probe = probe), "dead UI if it stays on")
    }

    @Test
    fun `a bare 404 on the RPC path also means the function is missing`() {
        assertEquals(PartyEnabledProbe.MissingFunction, PartyAvailabilityPolicy.probeOfFailure(404, "Not Found\nCode: null"))
        assertEquals(PartyEnabledProbe.MissingFunction, PartyAvailabilityPolicy.probeOfFailure(404, null))
    }

    @Test
    fun `a transient failure keeps the previous verdict`() {
        val offline = PartyAvailabilityPolicy.probeOfFailure(statusCode = null, message = "Unable to resolve host")
        val gateway = PartyAvailabilityPolicy.probeOfFailure(statusCode = 503, message = "Unknown error\nCode: null")
        assertEquals(PartyEnabledProbe.Failed, offline)
        assertEquals(PartyEnabledProbe.Failed, gateway)
        assertTrue(PartyAvailabilityPolicy.nextEnabled(previous = true, probe = offline))
        assertFalse(PartyAvailabilityPolicy.nextEnabled(previous = false, probe = gateway))
    }

    @Test
    fun `a 404 carrying another server code is not a missing function`() {
        assertEquals(PartyEnabledProbe.Failed, PartyAvailabilityPolicy.probeOfFailure(404, "nope\nCode: P0001"))
    }

    @Test
    fun `the server kill switch still decides when it answers`() {
        assertFalse(PartyAvailabilityPolicy.nextEnabled(previous = true, probe = PartyEnabledProbe.Answered(false)))
        assertTrue(PartyAvailabilityPolicy.nextEnabled(previous = false, probe = PartyEnabledProbe.Answered(true)))
    }

    @Test
    fun `a backend that gains the RPC later re-enables on the next answer`() {
        val afterMissing = PartyAvailabilityPolicy.nextEnabled(true, PartyEnabledProbe.MissingFunction)
        assertTrue(PartyAvailabilityPolicy.nextEnabled(afterMissing, PartyEnabledProbe.Answered(true)))
    }
}
