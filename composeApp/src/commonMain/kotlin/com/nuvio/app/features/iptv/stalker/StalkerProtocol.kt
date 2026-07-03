package com.nuvio.app.features.iptv.stalker

/**
 * Pure Stalker-portal (MAG/Ministra) protocol helpers — no I/O, so they're unit-testable against the
 * documented md5/sha256 device-identity derivation and real create_link responses. Ported verbatim
 * from NuvioTV so both apps derive the SAME identity for a given MAC (a playlist paired on one plays
 * on the other). The I/O flow lives in [StalkerSession] + [StalkerClient]; this is only the string math.
 */
object StalkerProtocol {

    /** Ordered endpoint candidates to probe (the user enters just the base portal URL). The first
     *  that answers a handshake with a token wins and is remembered for the session. */
    val ENDPOINT_CANDIDATES: List<String> = listOf(
        "/portal.php",
        "/stalker_portal/server/load.php",
        "/server/load.php",
        "/c/portal.php",
        "/stb/server/load.php"
    )

    /**
     * The device identity a MAG box derives from its MAC (client convention, matches the reference
     * players):
     *   sn        = md5(mac).hex.upper()[:13]
     *   deviceId  = deviceId2 = sha256(mac).hex.upper()
     *   signature = sha256(mac + sn + deviceId + deviceId2).hex.upper()
     * User-supplied Serial / Device ID override the derived sn / deviceId (and feed the signature).
     */
    data class DeviceIdentity(
        val mac: String,
        val serialNumber: String,
        val deviceId: String,
        val deviceId2: String,
        val signature: String
    )

    fun deriveDeviceIdentity(
        mac: String,
        serialOverride: String? = null,
        deviceIdOverride: String? = null
    ): DeviceIdentity {
        val normalizedMac = mac.trim()
        val sn = serialOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: md5Hex(normalizedMac).uppercase().take(13)
        val deviceId = deviceIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: sha256Hex(normalizedMac).uppercase()
        // MAG sends device_id2 == device_id; a Device ID override applies to both.
        val deviceId2 = deviceId
        val signature = sha256Hex(normalizedMac + sn + deviceId + deviceId2).uppercase()
        return DeviceIdentity(normalizedMac, sn, deviceId, deviceId2, signature)
    }

    /**
     * The Referer a MAG box sends is the portal's `.../c/` directory, derived from the resolved
     * endpoint path. [baseUrl] is scheme+host[:port] (no trailing slash); [endpointPath] is one of
     * [ENDPOINT_CANDIDATES]. Rule: take everything before the final path segment, drop a `/server`
     * tail (load.php lives under .../server, but /c/ is its sibling), append `c/`.
     */
    fun refererFor(baseUrl: String, endpointPath: String): String {
        val base = baseUrl.trimEnd('/')
        val dir = endpointPath.substringBeforeLast('/', "")
            .removeSuffix("/server")
            .trim('/')
        return if (dir.isEmpty()) "$base/c/" else "$base/$dir/c/"
    }

    /**
     * create_link returns a launcher-prefixed command, e.g. "ffmpeg http://host/live/u/p/745149.ts?..."
     * or "auto http://..." or "ffrt3 http://...". Strip the launcher token by taking the LAST
     * whitespace-separated token that parses as an http(s) URL. Null if there's no URL at all — covers
     * auto/ffmpeg/ffrt/ffrt2/ffrt3 and any future launcher without hardcoding the list.
     */
    fun extractStreamUrl(cmd: String?): String? {
        if (cmd.isNullOrBlank()) return null
        val trimmed = cmd.trim()
        if (isHttpUrl(trimmed)) return trimmed
        return trimmed.split(WHITESPACE).lastOrNull { isHttpUrl(it) }
    }

    private fun isHttpUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** MAC url-encoding for the Cookie header: only the colons become %3A (MAG convention). */
    fun encodeMacForCookie(mac: String): String = mac.trim().replace(":", "%3A")

    fun md5Hex(input: String): String = StalkerCrypto.md5Hex(input)
    fun sha256Hex(input: String): String = StalkerCrypto.sha256Hex(input)

    private val WHITESPACE = Regex("\\s+")
}
