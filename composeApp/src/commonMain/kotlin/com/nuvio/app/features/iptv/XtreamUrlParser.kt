package com.nuvio.app.features.iptv

import io.ktor.http.Url

/**
 * Turns a pasted portal/M3U URL into an [XtreamAccount]. KMP twin of NuvioTV's
 * XtreamUrlParser (uses Ktor's Url instead of okhttp HttpUrl). Accepts any of:
 *   http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts
 *   http://host:port/player_api.php?username=U&password=P
 * The path is ignored; only scheme+host+port and the username/password query params matter.
 */
fun parseXtreamAccount(input: String, name: String? = null): XtreamAccount? {
    val url = try {
        Url(input.trim())
    } catch (e: Exception) {
        return null
    }
    if (url.host.isBlank()) return null
    val user = url.parameters["username"]?.takeIf { it.isNotBlank() } ?: return null
    val pass = url.parameters["password"]?.takeIf { it.isNotBlank() } ?: return null
    val base = buildString {
        append(url.protocol.name).append("://").append(url.host)
        if (url.port != url.protocol.defaultPort) append(":").append(url.port)
    }
    return XtreamAccount(
        id = "$base|$user",
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = base,
        username = user,
        password = pass
    )
}

/**
 * Builds an account from manually-entered fields. The server field may be "host", "host:port",
 * or a full URL; only scheme+host+port are kept. Defaults to http when no scheme is given.
 * KMP twin of NuvioTV's xtreamAccountFromFields.
 */
fun xtreamAccountFromFields(serverUrl: String, username: String, password: String, name: String? = null): XtreamAccount? {
    val user = username.trim()
    val pass = password.trim()
    if (user.isEmpty() || pass.isEmpty()) return null
    val raw = serverUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val url = try {
        Url(withScheme)
    } catch (e: Exception) {
        return null
    }
    if (url.host.isBlank()) return null
    val base = buildString {
        append(url.protocol.name).append("://").append(url.host)
        if (url.port != url.protocol.defaultPort) append(":").append(url.port)
    }
    return XtreamAccount(
        id = "$base|$user",
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = base,
        username = user,
        password = pass
    )
}

/**
 * Builds an account from the full "Add Playlist" form: the identity fields (server/username/password/
 * name) plus the playlist options the form collects (EPG URL, DNS provider, auto-refresh). Returns
 * null if the identity fields don't resolve to a valid host + credentials. Content types + category
 * selections are edited on the separate "Content & Categories" page, so they keep XtreamAccount's
 * defaults here. internal for unit tests.
 */
internal fun xtreamAccountFromForm(input: XtreamFormInput): XtreamAccount? {
    val base = xtreamAccountFromFields(input.serverUrl, input.username, input.password, input.name)
        ?: return null
    return base.copy(
        epgUrl = input.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
        dnsProvider = input.dnsProvider,
        autoRefreshHours = input.autoRefreshHours,
    )
}

/**
 * Builds an M3U-URL playlist account from the "Add Playlist" form. The M3U URL IS the identity —
 * there's no username/password — so the id is `m3u|$url`, baseUrl carries the URL, and username/
 * password stay empty. Returns null if the URL is blank or unparseable. internal for unit tests.
 */
internal fun m3uAccountFromForm(input: XtreamFormInput): XtreamAccount? {
    val raw = input.m3uUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "http://$raw"
    val url = try { Url(withScheme) } catch (e: Exception) { return null }
    if (url.host.isBlank()) return null
    return XtreamAccount(
        id = "m3u|$withScheme",
        name = input.name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = withScheme,             // the full M3U URL (path + query kept — it's the fetch target)
        username = "",
        password = "",
        sourceType = SOURCE_TYPE_M3U_URL,
        epgUrl = input.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
        dnsProvider = input.dnsProvider,
        autoRefreshHours = input.autoRefreshHours,
        userAgent = input.userAgent?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * Builds a Stalker (MAG/Ministra) playlist account from the form. Stalker auths by MAC (not creds),
 * so the identity is portal base + MAC: id = "stalker|$base|$mac" (matches NuvioTV so a playlist
 * added on one app resolves to the same id on the other), baseUrl carries the portal base, and
 * username/password stay empty. Returns null if the portal URL is blank/unparseable or the MAC is
 * missing. internal for unit tests.
 */
internal fun stalkerAccountFromForm(input: XtreamFormInput): XtreamAccount? {
    val mac = input.macAddress.trim()
    if (mac.isEmpty()) return null
    val raw = input.serverUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "http://$raw"
    val url = try { Url(withScheme) } catch (e: Exception) { return null }
    if (url.host.isBlank()) return null
    val base = buildString {
        append(url.protocol.name).append("://").append(url.host)
        if (url.port != url.protocol.defaultPort) append(":").append(url.port)
    }
    return XtreamAccount(
        id = "stalker|$base|$mac",
        name = input.name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = base,
        username = "",
        password = "",
        sourceType = SOURCE_TYPE_STALKER,
        dnsProvider = input.dnsProvider,
        autoRefreshHours = input.autoRefreshHours,
        macAddress = mac,
        stalkerUsername = input.stalkerUsername?.trim()?.takeIf { it.isNotEmpty() },
        stalkerPassword = input.stalkerPassword?.trim()?.takeIf { it.isNotEmpty() },
        serialNumber = input.serialNumber?.trim()?.takeIf { it.isNotEmpty() },
        deviceId = input.deviceId?.trim()?.takeIf { it.isNotEmpty() },
        sendDeviceId = input.sendDeviceId,
    )
}

/**
 * Builds an M3U-FILE playlist account from the "Add Playlist" form. The local file IS the source —
 * there's no URL — so baseUrl stays empty (the ingest reads `filesDir/playlists/{id}.m3u`). The id
 * must be stable per playlist: [existingId] is reused when editing (so the same local copy + saved
 * data carry over), else a fresh unique id is minted from the file name + [uniqueSuffix] (a clock ms).
 * Returns null when there's no file name to key off (no pick made, not editing). internal for tests.
 */
internal fun m3uFileAccountFromForm(
    input: XtreamFormInput,
    existingId: String? = null,
    uniqueSuffix: Long = 0,
): XtreamAccount? {
    val fileName = input.fileName?.trim()?.takeIf { it.isNotEmpty() }
        ?: return existingId?.let { id ->
            // Editing options with no re-pick and no name: keep the id but we still need SOME name.
            m3uFileAccount(id, "Playlist", input)
        }
    val id = existingId ?: "m3u_file|${fileName}|$uniqueSuffix"
    val displayName = input.name?.trim()?.takeIf { it.isNotEmpty() } ?: fileName.substringBeforeLast('.')
    return m3uFileAccount(id, displayName, input, fileName)
}

private fun m3uFileAccount(id: String, name: String, input: XtreamFormInput, fileName: String? = null): XtreamAccount =
    XtreamAccount(
        id = id,
        name = name,
        baseUrl = "",                     // no URL — the local file copy is the source
        username = "",
        password = "",
        sourceType = SOURCE_TYPE_M3U_FILE,
        epgUrl = input.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
        dnsProvider = input.dnsProvider,
        autoRefreshHours = input.autoRefreshHours,
        userAgent = input.userAgent?.trim()?.takeIf { it.isNotEmpty() },
        fileName = fileName,
    )
