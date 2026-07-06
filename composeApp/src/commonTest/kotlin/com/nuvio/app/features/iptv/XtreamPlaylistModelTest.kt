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
 * unchanged), the sync_push_iptv_playlists payload/params shape (field names, omission rules
 * and source-type scoping match the backend migration exactly), the pull's usable-row filter,
 * edit option carry-over, and lenient category_selections decoding.
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
    fun pushParamsScopeEveryPushToTheKnownSourceTypes() {
        val params = playlistPushParams(profileId = 3, accounts = listOf(base))
        assertEquals(3, params["p_profile_id"]!!.jsonPrimitive.int)
        assertEquals(1, params["p_playlists"]!!.jsonArray.size)
        // Every push is scoped so the full-replace can't delete a FUTURE client's unknown rows.
        assertEquals(
            listOf("xtream", "m3u_url", "m3u_file", "stalker"),
            params["p_source_types"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // Regular pushes omit the migration guard entirely.
        assertFalse("p_only_if_empty" in params)
    }

    @Test
    fun migrationPushParamsSetOnlyIfEmpty() {
        val params = playlistPushParams(profileId = 1, accounts = listOf(base), onlyIfEmpty = true)
        // Two-device first-login race: the loser's migration push must no-op server-side.
        assertEquals(true, params["p_only_if_empty"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("xtream", "m3u_url", "m3u_file", "stalker"),
            params["p_source_types"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun pushPayloadCarriesPerTypeExtras() {
        val m3u = XtreamAccount(
            id = "m3u|http://h/list.m3u", name = "M3U", baseUrl = "http://h/list.m3u",
            username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL, userAgent = "VLC/3.0",
        )
        val file = XtreamAccount(
            id = "m3u_file|tv.m3u|1", name = "tv", baseUrl = "",
            username = "", password = "", sourceType = SOURCE_TYPE_M3U_FILE, fileName = "tv.m3u",
        )
        val stalker = XtreamAccount(
            id = "stalker|http://p:8080|00:1A:79:AA:BB:CC", name = "Portal", baseUrl = "http://p:8080",
            username = "", password = "", sourceType = SOURCE_TYPE_STALKER,
            macAddress = "00:1A:79:AA:BB:CC", serialNumber = "SN1", sendDeviceId = false,
        )
        val payload = playlistPushPayload(listOf(m3u, file, stalker))

        val m3uRow = payload[0].jsonObject
        assertEquals("m3u_url", m3uRow["source_type"]!!.jsonPrimitive.content)
        assertEquals("http://h/list.m3u", m3uRow["url"]!!.jsonPrimitive.content)
        assertEquals("VLC/3.0", m3uRow["user_agent"]!!.jsonPrimitive.content)

        val fileRow = payload[1].jsonObject
        assertEquals("m3u_file", fileRow["source_type"]!!.jsonPrimitive.content)
        assertEquals("tv.m3u", fileRow["file_name"]!!.jsonPrimitive.content)

        val stalkerRow = payload[2].jsonObject
        assertEquals("stalker", stalkerRow["source_type"]!!.jsonPrimitive.content)
        assertEquals("http://p:8080", stalkerRow["portal_url"]!!.jsonPrimitive.content)
        assertEquals("00:1A:79:AA:BB:CC", stalkerRow["mac_address"]!!.jsonPrimitive.content)
        assertEquals("SN1", stalkerRow["serial_number"]!!.jsonPrimitive.content)
        assertEquals(false, stalkerRow["send_device_id"]!!.jsonPrimitive.content.toBoolean())
        // null optionals stay omitted so the RPC's defaults apply
        assertFalse("stalker_username" in stalkerRow)
        assertFalse("device_id" in stalkerRow)
    }

    @Test
    fun pullMapsEverySourceTypeToTheFormBuilderShape() {
        // m3u_url: id/baseUrl are the playlist URL; UA rides its dedicated column.
        val m3u = PlaylistRow(sourceType = "m3u_url", url = "http://h/list.m3u", userAgent = "VLC/3.0").toAccount()!!
        assertEquals("m3u|http://h/list.m3u", m3u.id)
        assertEquals("http://h/list.m3u", m3u.baseUrl)
        assertEquals("VLC/3.0", m3u.userAgent)

        // m3u_file: a re-import ghost — deterministic id, fileName kept, no local bytes implied.
        val file = PlaylistRow(sourceType = "m3u_file", fileName = "tv.m3u", name = "TV").toAccount()!!
        assertEquals("m3u_file|tv.m3u|synced", file.id)
        assertEquals("tv.m3u", file.fileName)
        assertEquals(SOURCE_TYPE_M3U_FILE, file.sourceType)

        // stalker: portal in baseUrl, MAC + overrides on their fields, id matches the form builder.
        val stalker = PlaylistRow(
            sourceType = "stalker", portalUrl = "http://p:8080", macAddress = "00:1A:79:AA:BB:CC",
            serialNumber = "SN1", sendDeviceId = false,
        ).toAccount()!!
        assertEquals("stalker|http://p:8080|00:1A:79:AA:BB:CC", stalker.id)
        assertEquals("http://p:8080", stalker.baseUrl)
        assertEquals("00:1A:79:AA:BB:CC", stalker.macAddress)
        assertEquals("SN1", stalker.serialNumber)
        assertFalse(stalker.sendDeviceId)

        // TV's internal spellings are tolerated as aliases on the wire.
        assertEquals(SOURCE_TYPE_M3U_URL, PlaylistRow(sourceType = "url", url = "http://h/l.m3u").toAccount()!!.sourceType)
    }

    @Test
    fun reconcileKeepsTheLocalFilePlaylistId() {
        val local = XtreamAccount(
            id = "m3u_file|tv.m3u|1719000000", name = "tv", baseUrl = "",
            username = "", password = "", sourceType = SOURCE_TYPE_M3U_FILE, fileName = "tv.m3u",
        )
        val pulled = PlaylistRow(sourceType = "m3u_file", fileName = "tv.m3u", dnsProvider = "quad9").toAccount()!!
        val reconciled = reconcileLocalIds(listOf(pulled), listOf(local)).single()
        // The local id (and with it the local file copy + saved content keys) survives the pull;
        // the remote's option edits still apply.
        assertEquals("m3u_file|tv.m3u|1719000000", reconciled.id)
        assertEquals("quad9", reconciled.dnsProvider)
        // No local match -> the deterministic synced id is kept.
        assertEquals("m3u_file|tv.m3u|synced", reconcileLocalIds(listOf(pulled), emptyList()).single().id)
    }

    @Test
    fun pullWithOnlyForeignOrMalformedRowsIsAnEmptyRemote() {
        val foreign = listOf(
            PlaylistRow(sourceType = "plex", name = "Future type"),      // unknown -> stays remote-only
            PlaylistRow(sourceType = "m3u_url", name = "M3U"),           // malformed: no url
            PlaylistRow(sourceType = "stalker", name = "Portal"),        // malformed: no portal/mac
        )
        // Zero usable rows => empty remote; never applied as an empty list over local state.
        assertTrue(usableRemoteAccounts(foreign).isEmpty())

        // Malformed xtream rows (missing identity columns) are skipped too.
        assertTrue(usableRemoteAccounts(listOf(PlaylistRow(baseUrl = null, username = "u"))).isEmpty())

        val mixed = foreign + PlaylistRow(baseUrl = "http://h:80", username = "u", password = "p")
        val usable = usableRemoteAccounts(mixed).single()
        assertEquals("http://h:80|u", usable.id)
    }

    @Test
    fun editCarriesProviderSpecificOptionsOnlyForTheSamePlaylist() {
        val old = base.copy(
            enabled = false,
            epgUrl = "http://epg.example",
            dnsProvider = "quad9",
            autoRefreshHours = 12,
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1")),
        )

        // Same playlist (same username, moved domain): everything carries over.
        val moved = XtreamAccount(id = "http://new:80|u", name = "Moved", baseUrl = "http://new:80", username = "u", password = "p2")
        val sameCarried = carryPlaylistOptions(old, moved)
        assertFalse(sameCarried.enabled)
        assertEquals("http://epg.example", sameCarried.epgUrl)
        assertEquals("quad9", sameCarried.dnsProvider)
        assertEquals(12, sameCarried.autoRefreshHours)
        assertEquals(setOf(CONTENT_TYPE_LIVE), sameCarried.contentTypes)
        assertEquals(CategorySelections(live = listOf("1")), sameCarried.categorySelections)

        // Same server, rotated username: still the same playlist.
        val rotated = XtreamAccount(id = "http://h:80|u2", name = "Rotated", baseUrl = "http://h:80", username = "u2", password = "p")
        assertEquals(CategorySelections(live = listOf("1")), carryPlaylistOptions(old, rotated).categorySelections)

        // Different playlist: provider-specific options reset, provider-agnostic ones carry.
        val other = XtreamAccount(id = "http://other:80|x", name = "Other", baseUrl = "http://other:80", username = "x", password = "y")
        val reset = carryPlaylistOptions(old, other)
        assertEquals(CategorySelections(), reset.categorySelections)
        assertNull(reset.epgUrl)
        assertFalse(reset.enabled)
        assertEquals("quad9", reset.dnsProvider)
        assertEquals(12, reset.autoRefreshHours)
        assertEquals(setOf(CONTENT_TYPE_LIVE), reset.contentTypes)
    }

    @Test
    fun addPlaylistFormMapsOptionFieldsOntoAccount() {
        // The "Add Playlist" form collects epgUrl/dnsProvider/autoRefreshHours; the mapping must
        // land them on the XtreamAccount that gets verified + persisted (the model already syncs them).
        val account = xtreamAccountFromForm(
            XtreamFormInput(
                serverUrl = "host.example.org:8080",
                username = "user1",
                password = "secret",
                name = "  My Playlist  ",
                epgUrl = "  http://epg.example/xmltv.php  ",
                dnsProvider = "cloudflare",
                autoRefreshHours = 48,
            ),
        )!!
        assertEquals("http://host.example.org:8080|user1", account.id)
        assertEquals("My Playlist", account.name)                 // trimmed
        assertEquals("http://host.example.org:8080", account.baseUrl)
        assertEquals("user1", account.username)
        assertEquals("secret", account.password)
        assertEquals("http://epg.example/xmltv.php", account.epgUrl)   // trimmed, persisted
        assertEquals("cloudflare", account.dnsProvider)               // persisted
        assertEquals(48, account.autoRefreshHours)                    // persisted
        assertEquals("xtream", account.sourceType)
        // Content types + category selections aren't on the form → stay at defaults.
        assertEquals(setOf("live", "movies", "series"), account.contentTypes)
        assertTrue(account.categorySelections.allNull)
    }

    @Test
    fun addPlaylistFormBlanksBecomeNullOrDefault() {
        val account = xtreamAccountFromForm(
            XtreamFormInput(
                serverUrl = "http://h:80",
                username = "u",
                password = "p",
                name = null,
                epgUrl = "   ",           // blank -> null (not persisted / synced as empty)
                dnsProvider = "system",
                autoRefreshHours = 0,     // "Off"
            ),
        )!!
        assertNull(account.epgUrl)
        assertEquals("system", account.dnsProvider)
        assertEquals(0, account.autoRefreshHours)
        assertEquals("h", account.name)   // falls back to host when no name given

        // Missing identity fields => no account (form Save is gated on this too).
        assertNull(
            xtreamAccountFromForm(
                XtreamFormInput(serverUrl = "", username = "u", password = "p", name = null, epgUrl = null, dnsProvider = "system", autoRefreshHours = 24),
            ),
        )
    }

    @Test
    fun editFormKeepsCandidateOptionsButCarriesContentSelections() {
        // Editing via the full form: the form OWNS epg/dns/auto-refresh (it shows them), so the
        // candidate's values win over the old account's; content types + category selections live on
        // a different page the form doesn't touch, so they carry over (same-playlist edit).
        val old = base.copy(
            epgUrl = "http://old.epg",
            dnsProvider = "quad9",
            autoRefreshHours = 12,
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1")),
        )
        val candidate = xtreamAccountFromForm(
            XtreamFormInput(
                serverUrl = "http://h:80", username = "u", password = "p2", name = "Renamed",
                epgUrl = "http://new.epg", dnsProvider = "google", autoRefreshHours = 72,
            ),
        )!!
        val merged = carryPlaylistOptions(old, candidate, keepCandidateFormOptions = true)
        // form-owned option fields: candidate wins
        assertEquals("http://new.epg", merged.epgUrl)
        assertEquals("google", merged.dnsProvider)
        assertEquals(72, merged.autoRefreshHours)
        // not-on-form fields: carried from old
        assertEquals(setOf(CONTENT_TYPE_LIVE), merged.contentTypes)
        assertEquals(CategorySelections(live = listOf("1")), merged.categorySelections)
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
    fun m3uFileAccountFromFormBuildsFileIdentity() {
        val account = m3uFileAccountFromForm(
            XtreamFormInput(
                serverUrl = "", username = "", password = "", name = "  Movie Night  ",
                epgUrl = "  http://epg.example/guide.xml  ", dnsProvider = "system", autoRefreshHours = 24,
                sourceType = SOURCE_TYPE_M3U_FILE,
                fileName = "movies.m3u",
                userAgent = "  UA  ",
            ),
            existingId = null,
            uniqueSuffix = 1719900000000L,
        )!!
        assertEquals(SOURCE_TYPE_M3U_FILE, account.sourceType)
        assertEquals("m3u_file|movies.m3u|1719900000000", account.id)   // stable id from name + suffix
        assertEquals("", account.baseUrl)                              // no URL — local file is the source
        assertEquals("Movie Night", account.name)                     // trimmed
        assertEquals("movies.m3u", account.fileName)                  // persisted for re-import affordance
        assertEquals("http://epg.example/guide.xml", account.epgUrl)  // trimmed
        assertEquals("UA", account.userAgent)                         // trimmed
        assertEquals("", account.username)
        assertEquals("", account.password)
    }

    @Test
    fun m3uFileAccountFromFormNameDefaultsToFileStem_andEditKeepsId() {
        // No explicit name -> the file stem (no extension) becomes the display name.
        val added = m3uFileAccountFromForm(
            XtreamFormInput(serverUrl = "", username = "", password = "", name = null, epgUrl = null, dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_FILE, fileName = "My Playlist.m3u8"),
            existingId = null,
            uniqueSuffix = 42L,
        )!!
        assertEquals("My Playlist", added.name)
        assertEquals("m3u_file|My Playlist.m3u8|42", added.id)

        // Editing (re-pick) keeps the SAME id so the local copy + saved data carry over.
        val edited = m3uFileAccountFromForm(
            XtreamFormInput(serverUrl = "", username = "", password = "", name = "Renamed", epgUrl = null, dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_FILE, fileName = "different.m3u"),
            existingId = "m3u_file|My Playlist.m3u8|42",
            uniqueSuffix = 999L,   // ignored because existingId wins
        )!!
        assertEquals("m3u_file|My Playlist.m3u8|42", edited.id)
        assertEquals("Renamed", edited.name)
        assertEquals("different.m3u", edited.fileName)

        // No file name and not editing -> no account (nothing to key off / import).
        assertNull(
            m3uFileAccountFromForm(
                XtreamFormInput(serverUrl = "", username = "", password = "", name = null, epgUrl = null, dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_FILE, fileName = null),
                existingId = null,
            ),
        )
    }

    @Test
    fun fileNameFieldRoundTripsThroughJson_andLegacyDecodesNull() {
        val account = m3uFileAccountFromForm(
            XtreamFormInput(serverUrl = "", username = "", password = "", name = "F", epgUrl = null, dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_FILE, fileName = "list.m3u"),
            existingId = null, uniqueSuffix = 1L,
        )!!
        val decoded = json.decodeFromString<List<XtreamAccount>>(json.encodeToString(listOf(account))).single()
        assertEquals(account, decoded)
        assertEquals("list.m3u", decoded.fileName)

        // Pre-fileName persisted JSON decodes with fileName = null (additive, no migration).
        val legacy = """[{"id":"http://h:80|u","name":"P","baseUrl":"http://h:80","username":"u","password":"p"}]"""
        assertNull(json.decodeFromString<List<XtreamAccount>>(legacy).single().fileName)
    }

    @Test
    fun isM3uCoversBothUrlAndFileSources() {
        assertTrue(SOURCE_TYPE_M3U_URL.isM3u())
        assertTrue(SOURCE_TYPE_M3U_FILE.isM3u())
        assertFalse(SOURCE_TYPE_XTREAM.isM3u())
        assertFalse("stalker".isM3u())
    }

    @Test
    fun fileSourcePlaylistsSyncMetadataOnlyNeverBytes() {
        // A file playlist syncs as METADATA (file_name + options) so another device can offer
        // "re-import here"; the file BYTES never leave the device (spec §3.2) — the payload has
        // no url and an empty base_url.
        val fileAcc = m3uFileAccountFromForm(
            XtreamFormInput(serverUrl = "", username = "", password = "", name = "F", epgUrl = null, dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_FILE, fileName = "l.m3u"),
            existingId = null, uniqueSuffix = 1L,
        )!!
        val params = playlistPushParams(profileId = 1, accounts = listOf(fileAcc))
        assertTrue("m3u_file" in params["p_source_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        val row = params["p_playlists"]!!.jsonArray.single().jsonObject
        assertEquals("m3u_file", row["source_type"]!!.jsonPrimitive.content)
        assertEquals("l.m3u", row["file_name"]!!.jsonPrimitive.content)
        assertEquals("", row["base_url"]!!.jsonPrimitive.content)
        assertFalse("url" in row)
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
