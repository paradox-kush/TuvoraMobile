package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.SupportCodeGenerator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportCodeGeneratorTest {
    @Test
    fun `a code is 8 characters from the unambiguous alphabet`() {
        val rnd = Random(42)
        repeat(500) {
            val code = SupportCodeGenerator.generate { rnd.nextInt(it) }
            assertEquals(8, code.length)
            assertTrue(code.all { c -> c in SupportCodeGenerator.ALPHABET }, code)
        }
    }

    @Test
    fun `the alphabet has no look-alike characters`() {
        for (bad in "01OIL") assertFalse(bad in SupportCodeGenerator.ALPHABET, "contains $bad")
        assertEquals(31, SupportCodeGenerator.ALPHABET.length)
    }

    @Test
    fun `display splits the code into two chunks of four`() {
        assertEquals("K7Q2-9MPX", SupportCodeGenerator.display("K7Q29MPX"))
    }

    @Test
    fun `lookup accepts lowercase and a missing or wrong dash`() {
        assertEquals("K7Q29MPX", SupportCodeGenerator.normalize("k7q2-9mpx"))
        assertEquals("K7Q29MPX", SupportCodeGenerator.normalize(" K7Q29MPX "))
        assertEquals("K7Q29MPX", SupportCodeGenerator.normalize("K7Q2 9MPX"))
        assertNull(SupportCodeGenerator.normalize("K7Q2-9MP"))
        assertNull(SupportCodeGenerator.normalize("K7Q2-9MP0"))
    }

    @Test
    fun `validity rejects wrong length and characters outside the alphabet`() {
        assertTrue(SupportCodeGenerator.isValid("K7Q29MPX"))
        assertFalse(SupportCodeGenerator.isValid(null))
        assertFalse(SupportCodeGenerator.isValid("K7Q29MP"))
        assertFalse(SupportCodeGenerator.isValid("K7Q29MPL"))
    }
}
