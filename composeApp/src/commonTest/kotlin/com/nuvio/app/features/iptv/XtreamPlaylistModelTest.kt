package com.nuvio.app.features.iptv

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playlist-manager P1 contract tests: additive model defaults (old persisted JSON must load
 * unchanged), the sync_push_iptv_playlists payload shape (field names + omission rules match
 * the backend migration exactly), and lenient category_selections decoding.
 */
class XtreamPlaylistModelTest {

    // Same config as XtreamRepository's Json.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val base = XtreamAccount(
        id = "http://h:80|u",
        name = "My panel",
        baseUrl = "http://h:80",
        username = "u",
        password = "p",
    )

    @Test
    fun oldPersistedJsonDecodesWithPlaylistDefaults() {
        // Exactly what pre-playlist-manager builds wrote to SharedPreferences/NSUserDefaults.
        val stored = """[{"id":"http://h:80|u","name":"My panel","baseUrl":"http://h:80","username":"u","password":"p","enabled":false}]"""
        val acc = json.decodeFromString<List<XtreamAccount>>(stored).single()
        assertEquals("xtream", acc.sourceType)
        assertNull(acc.epgUrl)
        assertEquals("system", acc.dnsProvider)
        // missing field → the 24h product default
        assertEquals(24, acc.autoRefreshHours)
        assertEquals(setOf("live", "movies", "series"), acc.contentTypes)
        assertTrue(acc.categorySelections.allNull)
        assertFalse(acc.enabled)
    }

    @Test
    fun roundTripPreservesPlaylistOptions() {
        val tuned = base.copy(
            epgUrl = "http://epg.example/xmltv.php",
            dnsProvider = "cloudflare",
            autoRefreshHours = 24,
            contentTypes = setOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_MOVIES),
            categorySelections = CategorySelections(live = listOf("1", "2"), movies = emptyList()),
        )
        val decoded = json.decodeFromString<List<XtreamAccount>>(json.encodeToString(listOf(tuned))).single()
        assertEquals(tuned, decoded)
    }

    @Test
    fun pushPayloadShapeMatchesMigrationContract() {
        val plain = base.copy(name = "")   // blank name -> omitted
        val tuned = base.copy(
            name = "Named",
            enabled = false,
            epgUrl = "http://epg.example",
            dnsProvider = "quad9",
            autoRefreshHours = 12,
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1", "2")),
        )
        val payload = playlistPushPayload(listOf(plain, tuned))

        val first = payload[0].jsonObject
        assertEquals("xtream", first["source_type"]!!.jsonPrimitive.content)
        assertFalse("name" in first)                    // blank name omitted
        assertFalse("epg_url" in first)                 // null epg_url omitted
        assertFalse("category_selections" in first)     // all-null selections omitted
        assertEquals(true, first["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, first["sort_order"]!!.jsonPrimitive.int)
        assertEquals("http://h:80", first["base_url"]!!.jsonPrimitive.content)
        assertEquals("u", first["username"]!!.jsonPrimitive.content)
        assertEquals("p", first["password"]!!.jsonPrimitive.content)
        assertEquals("system", first["dns_provider"]!!.jsonPrimitive.content)
        assertEquals(24, first["auto_refresh_hours"]!!.jsonPrimitive.int)   // default
        assertEquals(
            setOf("live", "movies", "series"),
            first["content_types"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val second = payload[1].jsonObject
        assertEquals("Named", second["name"]!!.jsonPrimitive.content)
        assertEquals(false, second["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, second["sort_order"]!!.jsonPrimitive.int)
        assertEquals("http://epg.example", second["epg_url"]!!.jsonPrimitive.content)
        assertEquals("quad9", second["dns_provider"]!!.jsonPrimitive.content)
        assertEquals(12, second["auto_refresh_hours"]!!.jsonPrimitive.int)
        assertEquals(listOf("live"), second["content_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        val selections = second["category_selections"]!!.jsonObject
        assertEquals(listOf("1", "2"), selections["live"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("movies" in selections)             // null per-type list omitted
        assertFalse("series" in selections)
    }

    @Test
    fun categorySelectionsColumnDecodesLeniently() {
        assertTrue(parseCategorySelections(null).allNull)
        assertTrue(parseCategorySelections(JsonNull).allNull)
        assertTrue(parseCategorySelections(JsonPrimitive(42)).allNull)

        val parsed = parseCategorySelections(json.parseToJsonElement("""{"live":["5","6"],"series":null}"""))
        assertEquals(listOf("5", "6"), parsed.live)
        assertNull(parsed.movies)
        assertNull(parsed.series)

        // Wrong-typed shapes never throw, they just mean "all".
        assertNull(parseCategorySelections(json.parseToJsonElement("""{"live":"oops"}""")).live)
        val empty = parseCategorySelections(json.parseToJsonElement("""{"movies":[]}"""))
        assertEquals(emptyList(), empty.movies)   // explicit empty = none selected, NOT all
        assertNull(empty.live)
    }

    @Test
    fun typeEnabledAndAllowsCategorySemantics() {
        val acc = base.copy(
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1")),
        )
        assertTrue(acc.typeEnabled(CONTENT_TYPE_LIVE))
        assertFalse(acc.typeEnabled(CONTENT_TYPE_MOVIES))
        assertTrue(acc.allowsCategory(CONTENT_TYPE_LIVE, "1"))
        assertFalse(acc.allowsCategory(CONTENT_TYPE_LIVE, "2"))
        assertFalse(acc.allowsCategory(CONTENT_TYPE_LIVE, null))     // listed selection can't match no-category
        assertTrue(acc.allowsCategory(CONTENT_TYPE_MOVIES, "anything"))  // null selection = all
        assertTrue(acc.allowsCategory(CONTENT_TYPE_MOVIES, null))

        // withType/forType round-trip incl. the materialize-then-toggle flow
        val materialized = acc.categorySelections.withType(CONTENT_TYPE_MOVIES, listOf("a", "b"))
        assertEquals(listOf("a", "b"), materialized.forType(CONTENT_TYPE_MOVIES))
        assertEquals(listOf("1"), materialized.forType(CONTENT_TYPE_LIVE))
        assertNull(materialized.withType(CONTENT_TYPE_MOVIES, null).forType(CONTENT_TYPE_MOVIES))
    }
}
