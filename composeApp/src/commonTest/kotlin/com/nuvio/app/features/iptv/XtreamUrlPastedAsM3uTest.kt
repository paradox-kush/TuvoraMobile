package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An Xtream `get.php` URL pasted into the M3U-URL field must be recognised as an Xtream panel at
 * ADD time (Overlay Build Spec triage fix "recognise Xtream-shaped M3U URLs"; catch-up design
 * memory: the same account added as M3U silently loses catch-up because M3U hard-codes
 * `hasArchive = false`).
 *
 * The recognition is a separate add-time offer, [recogniseXtreamPanelInM3uField], and NOT a change
 * to [m3uAccountFromForm]: that builder also runs on edit, and an existing `m3u|…` playlist must
 * keep its identity (M3UParserTest.m3uAccountFromFormBuildsM3uIdentity pins that) — re-keying it
 * would orphan everything saved under the old id.
 */
class XtreamUrlPastedAsM3uTest {

    private fun m3uForm(url: String, name: String? = null, userAgent: String? = null) = XtreamFormInput(
        serverUrl = "", username = "", password = "", name = name, epgUrl = null,
        dnsProvider = "system", autoRefreshHours = 24,
        sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = url, userAgent = userAgent,
    )

    @Test
    fun `an xtream get php url pasted as m3u is recognised as an xtream panel at add time`() {
        val account = recogniseXtreamPanelInM3uField(
            m3uForm("http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus&output=ts", userAgent = " VLC/3.0 "),
        )
        assertNotNull(account)
        assertEquals(SOURCE_TYPE_XTREAM, account.sourceType, "get.php with username+password is an Xtream panel, not a bare M3U")
        assertEquals("http://panel.example.com:8080", account.baseUrl)
        assertEquals("u", account.username)
        assertEquals("p", account.password)
        assertEquals("VLC/3.0", account.userAgent, "the form's playlist options carry over")
    }

    @Test
    fun `the player api root is recognised too`() {
        val account = recogniseXtreamPanelInM3uField(m3uForm("http://panel.example.com/player_api.php?username=u&password=p"))
        assertNotNull(account)
        assertEquals(SOURCE_TYPE_XTREAM, account.sourceType)
        assertEquals("http://panel.example.com", account.baseUrl)
    }

    @Test
    fun `a plain m3u url is not a panel`() {
        assertNull(recogniseXtreamPanelInM3uField(m3uForm("http://lists.example.com/uk.m3u")))
        assertNull(recogniseXtreamPanelInM3uField(m3uForm("http://lists.example.com/get.php?type=m3u")), "no credentials means no panel")
    }

    @Test
    fun `the m3u identity builder itself is unchanged`() {
        // Edit safety: the builder never re-keys, whatever the URL looks like.
        val account = m3uAccountFromForm(m3uForm("http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus"))
        assertNotNull(account)
        assertEquals(SOURCE_TYPE_M3U_URL, account.sourceType)
        assertEquals("m3u|http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus", account.id)
    }
}
