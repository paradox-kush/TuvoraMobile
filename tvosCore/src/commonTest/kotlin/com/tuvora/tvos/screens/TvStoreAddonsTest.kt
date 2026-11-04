package com.tuvora.tvos.screens

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.iptv.AccountKind
import com.nuvio.app.features.iptv.HttpProviderSetupApi
import com.nuvio.app.features.iptv.DeviceClaimResult
import com.nuvio.app.features.iptv.DeviceRedeemResult
import com.nuvio.app.features.iptv.ManagedInfo
import com.nuvio.app.features.iptv.ProviderPreviewTransport
import com.nuvio.app.features.iptv.ProviderRpcTransport
import com.nuvio.app.features.iptv.ProviderSetupApi
import com.nuvio.app.features.iptv.ProviderSetupTelemetry
import com.nuvio.app.features.iptv.ProviderSupport
import com.nuvio.app.features.iptv.RedeemResult
import com.nuvio.app.features.iptv.RedeemSummary
import com.nuvio.app.features.iptv.SetupCodeController
import com.nuvio.app.features.iptv.SetupCodeHolder
import com.nuvio.app.features.iptv.SetupCodeOutcome
import com.nuvio.app.features.iptv.SetupPreview
import com.nuvio.app.features.iptv.SetupPreviewPlaylist
import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Add-ons on Apple TV (contract section 8, decision 6.5): the Apple TV build is the App Store build, which has
 * no add-on system, so a setup-code redeem must ask the server not to install the package's add-ons
 * (`p_skip_addons: true`) and the preview must not list them.
 */
class TvStoreAddonsTest {

    @Test
    fun `the Apple TV build resolves add-ons as off`() {
        assertFalse(AppFeaturePolicy.addonsEnabled, "tvosCore must compile the App Store policy, which has no add-ons")
    }

    private class FakeApi : ProviderSetupApi {
        val skipSeen = mutableListOf<Boolean>()
        override suspend fun preview(code: String): SetupCodeOutcome = SetupCodeOutcome.Ready(
            SetupPreview("Acme", ProviderSupport.NONE, "Gold", listOf(SetupPreviewPlaylist("Live", "xtream")), listOf("Cinemeta")),
        )
        override suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean): RedeemResult {
            skipSeen += skipAddons
            return RedeemResult.Redeemed(RedeemSummary(profileIndex, 1, 0, 0, emptyList(), 0, 0))
        }
        override suspend fun deviceRedeem(code: String, claimToken: String): DeviceRedeemResult = error("not used")
        override suspend fun claimDeviceSetup(claimToken: String, profileIndex: Int, skipAddons: Boolean): DeviceClaimResult = error("not used")
        override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> = emptyList()
        override suspend fun detach(profileId: Int, playlistKey: String): Boolean = false
    }

    @Test
    fun `a redeem through the default controller asks the server to skip add-ons`() {
        ProviderSetupTelemetry.capture = { _, _ -> }
        val api = FakeApi()
        val controller = SetupCodeController(
            api = { api }, holder = SetupCodeHolder(clock = { 0L }), accountKind = { AccountKind.REAL },
            activeProfileIndex = { 1 }, profileExists = { true }, localAccountKeys = { emptySet() },
            pullPlaylists = {}, refreshManaged = {}, requestSignIn = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        controller.onTyped("TUV-ABCD-EFGH-JKMN")
        controller.onContinue()
        controller.loadPreview(force = true)
        controller.confirm()
        assertEquals(listOf(true), api.skipSeen)
    }

    private class CapturingRpc : ProviderRpcTransport {
        var params: JsonObject? = null
        override suspend fun call(function: String, params: JsonObject): JsonElement {
            this.params = params
            return buildJsonObject { put("ok", true); put("status", "redeemed") }
        }
    }

    private val noPreview = object : ProviderPreviewTransport {
        override suspend fun get(url: String, headers: Map<String, String>): RawHttpResponse = error("not used")
        override suspend fun post(url: String, headers: Map<String, String>, body: String): RawHttpResponse = error("not used")
    }

    @Test
    fun `the real api sends p_skip_addons when asked and leaves it out otherwise`() = runBlocking {
        val rpc = CapturingRpc()
        val api = HttpProviderSetupApi(preview = noPreview, rpc = rpc, webBaseUrl = { "https://example.test" }, bearerToken = { null })
        api.redeem("ABCDEFGHJKMN", 1, skipAddons = true)
        assertEquals(JsonPrimitive(true), rpc.params!!["p_skip_addons"])
        api.redeem("ABCDEFGHJKMN", 1, skipAddons = false)
        assertNull(rpc.params!!["p_skip_addons"])
        assertTrue(rpc.params!!.containsKey("p_code"))
    }
}
