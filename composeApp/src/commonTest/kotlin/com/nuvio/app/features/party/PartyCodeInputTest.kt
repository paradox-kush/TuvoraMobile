package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertEquals

class PartyCodeInputTest {

    @Test
    fun `a pasted website invite gives its code`() {
        assertEquals("K7M2QX", PartyCode.fromInput("https://tuvora.co/party/K7M2QX"))
        assertEquals("K7M2QX", PartyCode.fromInput("https://www.tuvora.co/party/k7m2qx/"))
        assertEquals("K7M2QX", PartyCode.fromInput("HTTPS://TUVORA.CO/party/K7M2QX?utm=x"))
        assertEquals("K7M2QX", PartyCode.fromInput("tuvora.co/party/K7M2QX"))
    }

    @Test
    fun `a pasted app link gives its code`() {
        assertEquals("K7M2QX", PartyCode.fromInput("tuvora://party/K7M2QX"))
        assertEquals("K7M2QX", PartyCode.fromInput("nuvio://party/k7m-2qx"))
        assertEquals("K7M2QX", PartyCode.fromInput("nuvio://party?code=K7M2QX"))
    }

    @Test
    fun `an invite message with a link inside gives the code`() {
        assertEquals("K7M2QX", PartyCode.fromInput("Join my Tuvora watch party with code K7M 2QX\nhttps://tuvora.co/party/K7M2QX"))
    }

    @Test
    fun `a typed code is cleaned the same as before`() {
        assertEquals("K7M2QX", PartyCode.fromInput(" k7m-2qx "))
        assertEquals("K7M", PartyCode.fromInput("k7m"))
        assertEquals("K7M2QX", PartyCode.fromInput("K7M2QXZZ"))
        assertEquals("ABCDEF", PartyCode.fromInput("ab c-d ef"))
    }

    @Test
    fun `characters outside the code alphabet never survive`() {
        assertEquals("", PartyCode.fromInput("IO01"))
        assertEquals("K7M2Q", PartyCode.fromInput("https://tuvora.co/party/K7M2Q"))
        assertEquals("", PartyCode.fromInput("https://tuvora.co/party/"))
    }
}
