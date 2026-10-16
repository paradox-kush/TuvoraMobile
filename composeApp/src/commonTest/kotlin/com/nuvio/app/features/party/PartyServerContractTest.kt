package com.nuvio.app.features.party

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Facts of nuvio-backend's watch-party implementation (feat/watch-party-2026-10-16) the client must honour. */
class PartyServerContractTest {

    @Test
    fun `a signed-out or anonymous caller is told to sign in, not that the network failed`() {
        // The backend answers HTTP 200 {"error":"sign_in_required"} instead of RAISE.
        assertEquals(PartyError.SignInRequired, partyErrorOf("sign_in_required"))
        assertEquals(PartyError.Network, partyErrorOf("not_host"), "other unknown codes stay generic")
    }

    @Test
    fun `server-sent realtime payloads carry an extra id key and still decode`() {
        // realtime.send adds "id" to every broadcast payload.
        val playback = """{"id":"9b2c","seq":3,"member_id":"m1","paused":false,"position_ms":1000,"rate":1.0,"at":5,"media_key":"movie|tt1||"}"""
        val decoded = PartyApi.json.decodeFromString(PartyPlayback.serializer(), playback)
        assertEquals(3L, decoded.seq)
        val media = """{"id":"x","media":{"type":"movie","meta_id":"tt1","meta_type":"movie","title":"Film","extra":1},"playback":null}"""
        val obj = PartyApi.json.parseToJsonElement(media).jsonObject
        assertEquals("tt1", PartyApi.json.decodeFromJsonElement(PartyMedia.serializer(), obj.getValue("media")).metaId)
    }

    @Test
    fun `seat limit is three including the host when the server omits it`() {
        val snapshot = """{"party":{"id":"p","code":"K7M2QX","media":{"type":"movie","meta_id":"tt1","meta_type":"movie"}},"member_id":"m"}"""
        assertEquals(3, PartyApi.json.decodeFromString(PartySnapshot.serializer(), snapshot).party?.maxMembers)
    }
}
