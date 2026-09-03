package com.nuvio.app.features.iptv.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Canon-v1 cross-language PARITY test. The expected ids come from the reference JS implementation
 * (research/canon-v1/canon_v1.mjs), which is the SAME logic the website's TypeScript ships. If this
 * Kotlin reproduces them, the app and tuvora.co agree on channel identity — the precondition for a
 * web edit to apply to the right channel. GENERATED vectors (research/canon-v1/emit.mjs); do not hand-edit.
 *
 * Runs on BOTH the JVM host runner and the Kotlin/Native (iOS) runner — a fold that touches no
 * platform Unicode API must be identical on both, which is the whole point of the frozen table.
 */
class IptvIdentityTest {

    private data class V(val playlistId: String, val name: String, val tvgId: String?, val canon: String, val entityId: String)

    private val golden = listOf(
        V("http://p|u", "BBC One HD", "BBCOne.uk", "bbc one hd", "fp:v1:e55ce7edec20b239c248ef06432d164b"),
        V("http://p|u", "bbc.one|hd", "BBCOne.uk", "bbc one hd", "fp:v1:e55ce7edec20b239c248ef06432d164b"),
        V("http://p|u", "BBC One FHD", "BBCOne.uk", "bbc one fhd", "fp:v1:414a3080e15b6ec5f6de53424aa7dc61"),
        V("http://p|u", "BBC ONE 4K", "BBCOne.uk", "bbc one 4k", "fp:v1:c662340d8ea844e9d9841dcea87b8c1b"),
        V("http://p|u", "BBC One HD", null, "bbc one hd", "fp:v1:690eff9e6f6fcd8686c62d49285859e9"),
        V("http://p|u", "  BBC   One   HD  ", null, "bbc one hd", "fp:v1:690eff9e6f6fcd8686c62d49285859e9"),
        V("http://p|u", "T\u00e9l\u00e9 Mont\u00e9-Carlo", null, "tele monte carlo", "fp:v1:eea61c82cd024590d21760a18241808d"),
        V("http://p|u", "TELE MONTE CARLO", null, "tele monte carlo", "fp:v1:eea61c82cd024590d21760a18241808d"),
        V("http://p|u", "\u0420\u043e\u0441\u0441\u0438\u044f 1", null, "\u0440\u043e\u0441\u0441\u0438\u044f 1", "fp:v1:9552cc980c4d47015c87f588979448dc"),
        V("http://p|u", "\u0420\u041e\u0421\u0421\u0418\u042f 1", null, "\u0440\u043e\u0441\u0441\u0438\u044f 1", "fp:v1:9552cc980c4d47015c87f588979448dc"),
        V("http://p|u", "\u03a3\u039a\u0391\u03aa HD", null, "\u03c3\u03ba\u03b1\u03b9 hd", "fp:v1:fb50d7897686a64d85f8a06e5b62d8ea"),
        V("http://p|u", "\uff2e\uff28\uff2b", null, "nhk", "fp:v1:54da7fb416b3d717e998c66c0cc1ddcd"),
        V("http://p|u", "NHK", null, "nhk", "fp:v1:54da7fb416b3d717e998c66c0cc1ddcd"),
        V("http://p|u", "\u0642\u0646\u0627\u0629 \u0627\u0644\u062c\u0632\u064a\u0631\u0629", null, "\u0642\u0646\u0627\u0629 \u0627\u0644\u062c\u0632\u064a\u0631\u0629", "fp:v1:00284663a96aacca7a7396664021407b"),
        V("http://p|u", "Sky Sports F1 UHD", "sky.f1", "sky sports f1 uhd", "fp:v1:31dddca14438ea4bb78cbcb7d82e9978"),
    )

    @Test
    fun `canon and entityId match the cross-language golden vectors`() {
        for (v in golden) {
            assertEquals(v.canon, IptvIdentity.canon(v.name), "canon mismatch for ${v.name}")
            assertEquals(v.entityId, IptvIdentity.entityId(v.playlistId, v.name, v.tvgId), "entityId mismatch for ${v.name}")
            assertTrue(v.entityId.startsWith("fp:v1:"), "ids carry their canon version: ${v.entityId}")
        }
    }

    @Test
    fun `case separator whitespace accents cyrillic and fullwidth all fold to one identity`() {
        // golden[0]=BBC One HD, [1]=bbc.one|hd, [4]=BBC One HD(no tvg), [5]=spaced,
        // [6]=Tele Monte-Carlo accented, [7]=TELE MONTE CARLO, [8..9]=Cyrillic case, [11..12]=fullwidth vs NHK
        assertEquals(golden[0].entityId, golden[1].entityId, "case+separator fold")
        assertEquals(golden[4].entityId, golden[5].entityId, "whitespace collapse")
        assertEquals(golden[6].entityId, golden[7].entityId, "accent strip")
        assertEquals(golden[8].entityId, golden[9].entityId, "cyrillic case fold")
        assertEquals(golden[11].entityId, golden[12].entityId, "fullwidth fold")
    }

    @Test
    fun `sibling quality variants stay distinct`() {
        assertNotEquals(golden[0].entityId, golden[2].entityId, "HD vs FHD")
        assertNotEquals(golden[0].entityId, golden[3].entityId, "HD vs 4K")
    }

    @Test
    fun `categoryKey value matches the website node computation`() {
        // node/web canonV1.ts computed this for ("http://onnipsite.site|fifi","live","PPV | LIVE EVENTS").
        assertEquals("c:v1:806c0988a05d456bf5a7a6957ed98a2e", IptvIdentity.categoryKey("http://onnipsite.site|fifi", "live", "PPV | LIVE EVENTS"))
    }

    @Test
    fun `categoryKey is deterministic version-tagged and canon-folded`() {
        val a = IptvIdentity.categoryKey("http://p|u", "live", "UK | Entertainment")
        val b = IptvIdentity.categoryKey("http://p|u", "live", "uk entertainment")
        assertEquals(a, b, "category name folds through canon")
        assertTrue(a.startsWith("c:v1:"), "category keys are version-tagged: $a")
        assertNotEquals(a, IptvIdentity.categoryKey("http://p|u", "movies", "UK | Entertainment"), "scoped by content type")
    }
}
