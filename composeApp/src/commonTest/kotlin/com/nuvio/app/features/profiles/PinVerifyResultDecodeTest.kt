package com.nuvio.app.features.profiles

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The verify_profile_pin RPC returns a single JSON OBJECT, e.g.
 * {"unlocked":true,"retry_after_seconds":0} — never an array.
 *
 * supabase-kt's PostgrestResult.decodeSingle == decodeList<T>().first(), so it deserializes the body
 * as a List and throws on an object; the surrounding runCatching in ProfileRepository.verifyPin then
 * fell through to verifyPinLocally, so a correct server PIN read as "wrong". The fix decodes the
 * object directly (decodeAs). These tests pin the wire shape that fix relies on.
 */
class PinVerifyResultDecodeTest {

    // supabase-kt configures its serializer with ignoreUnknownKeys = true; mirror it here so the
    // test exercises the same lenient object decode the client performs.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes from a JSON object`() {
        val result = json.decodeFromString<PinVerifyResult>(
            """{"unlocked":true,"retry_after_seconds":0}""",
        )
        assertTrue(result.unlocked)
        assertEquals(0, result.retryAfterSeconds)
    }

    @Test
    fun `decoding the object as a list fails`() {
        // This is exactly what decodeSingle did (decodeList().first()) and why the RPC result was lost.
        assertFails {
            json.decodeFromString<List<PinVerifyResult>>(
                """{"unlocked":true,"retry_after_seconds":0}""",
            )
        }
    }

    @Test
    fun `transient currentPinRequired is not part of the wire shape`() {
        // The RPC never sends this field; it must not be required to decode, and must default false.
        val result = json.decodeFromString<PinVerifyResult>(
            """{"unlocked":false,"retry_after_seconds":30,"message":"locked"}""",
        )
        assertFalse(result.unlocked)
        assertEquals(30, result.retryAfterSeconds)
        assertEquals("locked", result.message)
        assertFalse(result.currentPinRequired)
    }
}
