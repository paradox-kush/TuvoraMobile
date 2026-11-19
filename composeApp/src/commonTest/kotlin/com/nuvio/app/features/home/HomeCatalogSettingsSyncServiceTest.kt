package com.nuvio.app.features.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    // ---- home_sections on the wire ------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(raw: String) =
        decodeHomeCatalogPayload(json, json.parseToJsonElement(raw).jsonObject)

    @Test
    fun `an older payload without home_sections still decodes`() {
        val decoded = decode("{\"show_catalog_type\":false,\"show_live_on_home\":false,\"items\":[]}")
        assertNull(decoded.homeSections, "absent key = never customised")
        assertEquals(false, decoded.showCatalogType)
        assertEquals(false, decoded.showLiveOnHome)
    }

    @Test
    fun `home_sections round-trips with the fixed wire shape`() {
        val config = HomeSectionsConfig(
            customized = true,
            sections = listOf(HomeSectionEntry("continue_watching", true), HomeSectionEntry("hero", false), HomeSectionEntry("future_row", true)),
        )
        val encoded = json.encodeToString(SyncHomeCatalogPayload.serializer(), SyncHomeCatalogPayload(homeSections = config))
        assertEquals(
            true,
            encoded.contains(
                "\"home_sections\":{\"customized\":true,\"sections\":[{\"id\":\"continue_watching\",\"enabled\":true}," +
                    "{\"id\":\"hero\",\"enabled\":false},{\"id\":\"future_row\",\"enabled\":true}]}",
            ),
            "wire shape shared with Android TV and Desktop: $encoded",
        )
        assertEquals(config, decode(encoded).homeSections, "unknown ids survive the round-trip")
    }

    @Test
    fun `an entry without enabled reads as shown and extra keys are ignored`() {
        val decoded = decode("{\"home_sections\":{\"customized\":true,\"sections\":[{\"id\":\"hero\"},{\"id\":\"sports\",\"enabled\":false,\"x\":1}],\"v\":2}}")
        assertEquals(
            HomeSectionsConfig(true, listOf(HomeSectionEntry("hero", true), HomeSectionEntry("sports", false))),
            decoded.homeSections,
        )
    }

    @Test
    fun `an unreadable home_sections costs only that key`() {
        val decoded = decode("{\"show_catalog_type\":false,\"home_sections\":{\"customized\":\"yes\",\"sections\":7}}")
        assertNull(decoded.homeSections)
        assertEquals(false, decoded.showCatalogType, "the rest of the blob still applies")
        assertNull(decode("{\"home_sections\":null}").homeSections)
    }

    @Test
    fun `merge on push keeps home_sections written by another device`() {
        val remote = json.parseToJsonElement(
            "{\"future_setting\":1,\"home_sections\":{\"customized\":true,\"sections\":[{\"id\":\"catalogs\",\"enabled\":true}]}}",
        ).jsonObject
        // This device never customised: its payload encodes home_sections as null.
        val local = json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), SyncHomeCatalogPayload()).jsonObject
        assertEquals(true, local.containsKey("home_sections"), "encodeDefaults writes the null")

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = local)

        assertEquals(remote.getValue("home_sections"), merged.getValue("home_sections"), "remote value kept")
        assertEquals("1", merged.getValue("future_setting").jsonPrimitive.content, "other unknown keys kept as before")
    }

    @Test
    fun `merge on push lets a local reset overwrite remote and a local list win`() {
        val remote = json.parseToJsonElement(
            "{\"home_sections\":{\"customized\":true,\"sections\":[{\"id\":\"catalogs\",\"enabled\":true}]}}",
        ).jsonObject
        val reset = json.encodeToJsonElement(
            SyncHomeCatalogPayload.serializer(),
            SyncHomeCatalogPayload(homeSections = HomeSectionOrderPolicy.reset()),
        ).jsonObject
        val afterReset = mergeHomeCatalogSettingsJson(remote, reset)
        assertEquals(
            HomeSectionsConfig(customized = false),
            decode(afterReset.toString()).homeSections,
            "reset is written as customized false because a merge cannot delete a key",
        )

        val mine = HomeSectionsConfig(true, listOf(HomeSectionEntry("hero", false)))
        val afterEdit = mergeHomeCatalogSettingsJson(
            remote,
            json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), SyncHomeCatalogPayload(homeSections = mine)).jsonObject,
        )
        assertEquals(mine, decode(afterEdit.toString()).homeSections)
    }
}
