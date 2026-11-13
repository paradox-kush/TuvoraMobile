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
     *
     * F46: a user-supplied Device ID 2 / Signature (copied off a real box or another STB app) is
     * sent verbatim. Blank keeps today's values EXACTLY — `device_id2 == device_id` and the derived
     * signature — because portals pin the first identity they see to the MAC (stock Ministra
     * `getProfile`, Xtream-Codes `lock_device`): changing the default would lock out every
     * playlist that already works.
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
        deviceIdOverride: String? = null,
        deviceId2Override: String? = null,
        signatureOverride: String? = null,
    ): DeviceIdentity {
        val normalizedMac = mac.trim()
        val sn = serialOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: md5Hex(normalizedMac).uppercase().take(13)
        val deviceId = deviceIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: sha256Hex(normalizedMac).uppercase()
        // MAG convention: device_id2 == device_id (a Device ID override applies to both) unless the
        // user entered a separate Device ID 2.
        val deviceId2 = deviceId2Override?.trim()?.takeIf { it.isNotEmpty() } ?: deviceId
        val signature = signatureOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: sha256Hex(normalizedMac + sn + deviceId + deviceId2).uppercase()
        return DeviceIdentity(normalizedMac, sn, deviceId, deviceId2, signature)
    }

    /**
     * Reduce whatever the user pasted as the portal URL to its origin (`scheme://host[:port]`), so the
     * [ENDPOINT_CANDIDATES] can be appended cleanly. Users routinely paste the STB UI path instead of
     * the API base — `http://host/c/`, `http://host/c/index.html`, `http://host/portal.php`, a trailing
     * slash — and the `/c/`-style panels are then reached via the `/c/portal.php` + `/portal.php`
     * candidates. A bare host (no scheme) defaults to http. Path/query/fragment are dropped.
     */
    fun normalizePortalBase(entered: String): String {
        var s = entered.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true))
            s = "http://$s"
        val afterScheme = s.indexOf("://") + 3
        val pathStart = s.indexOf('/', afterScheme)
        return if (pathStart == -1) s else s.substring(0, pathStart)
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

    /**
     * Strip a leading launcher token off a cmd, keeping the target REGARDLESS of scheme — the
     * scheme-agnostic sibling of [extractStreamUrl] (which surfaces http(s) only by taking the
     * last URL-shaped token). Both live here so launcher knowledge has exactly one home.
     *
     * Kodi pvr.stalker documents the cmd format as `(?:ffrt\d*\s|)(.*)` (ChannelManager, citing
     * the portal's own /c/player.js); the portals this client has met also emit `ffmpeg ` and
     * `auto ` (see [extractStreamUrl]'s examples), so the token class is (ffmpeg|auto|ffrt\d*).
     * Unknown leading tokens are LEFT IN PLACE — callers' guards then fail toward create_link,
     * never toward playing junk.
     */
    fun stripLauncherPrefix(cmd: String): String =
        cmd.trim().replaceFirst(LAUNCHER_PREFIX, "").trim()

    private fun isHttpUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** MAC url-encoding for the Cookie header: only the colons become %3A (MAG convention). */
    fun encodeMacForCookie(mac: String): String = mac.trim().replace(":", "%3A")

    /** The device cookie a portal request (and a portal-owned stream) carries: mac, language, timezone, serial. */
    fun identityCookie(mac: String, serialNumber: String): String = buildString {
        append("mac=").append(encodeMacForCookie(mac))
        append("; stb_lang=en; timezone=Europe/London")
        append("; sn=").append(serialNumber)
        append("; PHPSESSID=null")
    }

    fun md5Hex(input: String): String = StalkerCrypto.md5Hex(input)
    fun sha256Hex(input: String): String = StalkerCrypto.sha256Hex(input)

    private val WHITESPACE = Regex("\\s+")
    private val LAUNCHER_PREFIX = Regex("""^(?:ffmpeg|auto|ffrt\d*)\s+""", RegexOption.IGNORE_CASE)
}
