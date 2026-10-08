package com.nuvio.app.features.iptv

/**
 * The full set of fields the "Add Playlist" form collects. Handed to
 * XtreamRepository.addFromForm / editFromForm which verifies + persists. Content types &
 * category selections are edited on the separate "Content & Categories" page, not here.
 */
internal data class XtreamFormInput(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String?,
    val epgUrl: String?,
    val dnsProvider: String,
    val autoRefreshHours: Int,
    // Optional custom User-Agent (below): the M3U-URL source's fetch UA, and — since the WAF-456 fix
    // — the Xtream/Stalker stream UA too. Xtream stays the default so existing call sites are unaffected.
    val sourceType: String = SOURCE_TYPE_XTREAM,
    val m3uUrl: String = "",
    val userAgent: String? = null,
    // M3U-FILE source (sourceType = m3u_file): the picked document (name + a bytes provider). The
    // fileName is remembered for a synced/re-import affordance; pickedFile is null in edit mode when
    // the user didn't re-pick (the existing local copy is reused).
    val fileName: String? = null,
    val pickedFile: PickedM3UFile? = null,
    // Stalker source (sourceType = stalker): the portal base reuses [serverUrl]; the rest are
    // Stalker-only. Auth is by MAC, so username/password stay blank; stalkerUsername/Password are
    // the rare optional portal login. sendDeviceId defaults on (virtually every portal wants it).
    val macAddress: String = "",
    val stalkerUsername: String? = null,
    val stalkerPassword: String? = null,
    val serialNumber: String? = null,
    val deviceId: String? = null,
    // null means no explicit choice: preserve on edit, default enabled on add.
    val sendDeviceId: Boolean? = null,
    // F46: the optional rest of the STB identity (blank = derived / preset, as before).
    val deviceId2: String? = null,
    val signature: String? = null,
    val stbModel: String? = null,
    val hwVersion: String? = null,
    // Step 0.3: the backup-server rows as typed (Xtream base URLs / M3U playlist URLs / Stalker portal
    // URLs, in priority order). Validated + normalized by BackupServerValidation when the account is built.
    val backupUrls: List<String> = emptyList(),
)
