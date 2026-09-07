package com.nuvio.app.features.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun `shared settings merge preserves unknown remote fields`() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("show_catalog_type", false)
        }
        val local = buildJsonObject {
            put("show_catalog_type", true)
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("show_catalog_type").jsonPrimitive.content.toBoolean())
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }

    // The sync blob is opaque jsonb: adding a key needs no backend migration. This proves the new
    // show_live_on_home key serializes and round-trips, and that a legacy blob lacking it defaults to
    // true (current behaviour) via kotlinx ignoreUnknownKeys/default handling.
    @Test
    fun `show live on home round-trips through the opaque sync blob`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val encoded = json.encodeToString(
            SyncHomeCatalogPayload.serializer(),
            SyncHomeCatalogPayload(showLiveOnHome = false),
        )
        assertEquals(true, encoded.contains("\"show_live_on_home\":false"), "field must serialize under its snake_case key")

        val decoded = json.decodeFromString(SyncHomeCatalogPayload.serializer(), encoded)
        assertEquals(false, decoded.showLiveOnHome, "value must survive a serialize/deserialize round-trip")

        val legacyBlob = json.decodeFromString(
            SyncHomeCatalogPayload.serializer(),
            "{\"show_catalog_type\":true}",
        )
        assertEquals(true, legacyBlob.showLiveOnHome, "a legacy blob without the key defaults to true")
    }
}
