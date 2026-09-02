package com.nuvio.app.features.iptv

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Tolerates the same field arriving as a JSON number, a quoted string, or a bool —
 * real Xtream panels are inconsistent (verified live: tmdb_id is "936075" on one
 * server, 24831 on another). Twin of NuvioTV's FlexIntAdapter.
 */
object FlexIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int? {
        val jd = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val prim = jd.decodeJsonElement() as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content.trim().toIntOrNull()
        else prim.booleanOrNull?.let { if (it) 1 else 0 } ?: prim.intOrNull
    }
    override fun serialize(encoder: Encoder, value: Int?) { encoder.encodeInt(value ?: 0) }
}

/**
 * Xtream Codes `player_api.php` response shapes (only fields we use) plus the
 * domain models the UI consumes. KMP twin of NuvioTV's XtreamDto/XtreamClient.
 *
 * ponytail: ids that panels reliably send as ints are Int; category_id is String
 * because it comes back quoted. If a panel sends category_id as a bare int,
 * decode will fail here — switch it to a JsonElement-tolerant type then.
 */

// --- Wire DTOs (kotlinx.serialization) --------------------------------------

@Serializable
data class XtreamAccountDto(
    @SerialName("user_info") val userInfo: XtreamUserInfoDto? = null
    // ponytail: server_info is skipped via ignoreUnknownKeys — its fields were never read,
    // and panels send `port` as a bare int which broke String decoding. Re-add a DTO
    // (with FlexIntSerializer on port) only if something actually needs server_info.
)

@Serializable
data class XtreamUserInfoDto(
    @Serializable(with = FlexIntSerializer::class) val auth: Int? = null,
    val status: String? = null,
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("is_trial") val isTrial: String? = null
)

@Serializable
data class XtreamCategoryDto(
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String? = null
)

@Serializable
data class XtreamLiveStreamDto(
    val name: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("stream_id") val streamId: Int? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("tv_archive") val tvArchive: Int? = null
)

@Serializable
data class XtreamVodStreamDto(
    val name: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("stream_id") val streamId: Int? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    val rating: String? = null,
    // XUI.one panels ship a TMDB id right in the bulk list (~90% populated on tested
    // providers) — the free tier of TMDB->stream matching.
    @Serializable(with = FlexIntSerializer::class) val tmdb: Int? = null
)

@Serializable
data class XtreamSeriesDto(
    @Serializable(with = FlexIntSerializer::class) @SerialName("series_id") val seriesId: Int? = null,
    val name: String? = null,
    val cover: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    @Serializable(with = FlexIntSerializer::class) val tmdb: Int? = null,
    // panels send BOTH spellings; releaseDate is the one XUI populates
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("release_date") val releaseDateAlt: String? = null
)

@Serializable
data class XtreamShortEpgResponseDto(
    @SerialName("epg_listings") val listings: List<XtreamEpgEntryDto>? = null
)

@Serializable
data class XtreamEpgEntryDto(
    val title: String? = null,            // base64
    val description: String? = null,      // base64
    @SerialName("start_timestamp") val startTimestamp: String? = null,
    @SerialName("stop_timestamp") val stopTimestamp: String? = null,
    @Serializable(with = FlexIntSerializer::class) @SerialName("now_playing") val nowPlaying: Int? = null,
    // Per-programme archive flag (get_simple_data_table rows; get_short_epg omits it): the panel
    // saying, recording by recording, what it actually kept — the strongest catch-up signal
    // there is. Panels send int or "1"; absent -> null = the panel said nothing.
    @Serializable(with = FlexIntSerializer::class) @SerialName("has_archive") val hasArchive: Int? = null
)

// --- Domain models ----------------------------------------------------------

const val CONTENT_TYPE_LIVE = "live"
const val CONTENT_TYPE_MOVIES = "movies"
const val CONTENT_TYPE_SERIES = "series"
val ALL_CONTENT_TYPES: Set<String> = setOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_MOVIES, CONTENT_TYPE_SERIES)

/**
 * Per-content-type category include lists (playlist manager P1).
 * null = ALL categories including ones the provider adds later; a list = only those ids
 * (new provider categories arrive unselected); empty list = none.
 */
@Serializable
data class CategorySelections(
    val live: List<String>? = null,
    val movies: List<String>? = null,
    val series: List<String>? = null,
) {
    fun forType(type: String): List<String>? = when (type) {
        CONTENT_TYPE_LIVE -> live
        CONTENT_TYPE_MOVIES -> movies
        CONTENT_TYPE_SERIES -> series
        else -> null
    }

    fun withType(type: String, selection: List<String>?): CategorySelections = when (type) {
        CONTENT_TYPE_LIVE -> copy(live = selection)
        CONTENT_TYPE_MOVIES -> copy(movies = selection)
        CONTENT_TYPE_SERIES -> copy(series = selection)
        else -> this
    }

    val allNull: Boolean get() = live == null && movies == null && series == null
}

/**
 * The catch-up URL shape this panel was last PROVEN to answer, plus the container preference it
 * was proven under. [CatchUpWinnerStore] declines to offer it when the preference has since
 * changed — otherwise flipping "prefer m3u8" would be inert on every account that already learned
 * a TS dialect. [dialect] is a [CatchUpDialectWalk.Dialect] name; an unrecognised one reads as
 * "nothing learned" so a value written by a newer build can never throw.
 */
@Serializable
data class CatchUpWinner(
    val formatsSignature: String,
    val dialect: String,
    val preferM3u8: Boolean = false,
)

// Playlist-manager option fields are ADDITIVE with defaults so JSON persisted by older
// builds (and legacy sync rows) decodes unchanged — same storage keys, no migration.
@Serializable
data class XtreamAccount(
    val id: String,
    val name: String,
    val baseUrl: String,      // http://host:port (no trailing slash, no path)
    val username: String,
    val password: String,
    val enabled: Boolean = true,
    val sourceType: String = "xtream",                        // xtream | m3u_url | m3u_file | stalker
    val epgUrl: String? = null,                               // custom XMLTV override (P2 uses it; synced now)
    val userAgent: String? = null,                            // optional per-playlist UA (catalog + EPG + player stream; see StreamUserAgentPolicy)
    // The picked document's display name for a m3u_file playlist. Synced (spec §3.2) so another device
    // knows the playlist exists + can prompt a re-import; the FILE BYTES are NOT synced (local only).
    val fileName: String? = null,
    val dnsProvider: String = "system",                       // system|cloudflare|google|mullvad|quad9|dnssb (P3)
    val autoRefreshHours: Int = 24,                           // 0 = off; 24 = default (P3 uses it)
    val contentTypes: Set<String> = ALL_CONTENT_TYPES,
    val categorySelections: CategorySelections = CategorySelections(),
    // --- Stalker (MAG/Ministra) source fields (sourceType = stalker; P4) ------------------------
    // All additive with defaults so JSON written by older builds decodes unchanged. For a Stalker
    // playlist baseUrl == the entered portal base (e.g. http://host:port) and username/password stay
    // blank (Stalker auths by MAC, not creds); stalkerUsername/stalkerPassword are only sent when a
    // strict portal demands them.
    val macAddress: String = "",                              // 00:1A:79:xx:xx:xx
    val stalkerUsername: String? = null,                      // optional portal login (rare)
    val stalkerPassword: String? = null,                      // optional portal login (rare)
    val serialNumber: String? = null,                         // optional STB serial override (else derived from MAC)
    val deviceId: String? = null,                             // optional STB device id override (else derived from MAC)
    val sendDeviceId: Boolean = true,                         // send derived/overridden device identity on get_profile
    // --- Catch-up (tv_archive replay) options -------------------------------------------------
    /**
     * Ask the panel for `.m3u8` catch-up first instead of `.ts`. Off by default: TS is what
     * iptvnator ships after evidently finding it the more dependable answer in the field, and a
     * failing m3u8 still walks back to TS either way. On, the viewer gets a seekable replay on
     * panels that serve HLS — which is most of what "catch-up" means to them.
     */
    val catchUpPreferM3u8: Boolean = false,
    /**
     * Manual clock correction for this playlist, in MINUTES (−720..+840, i.e. −12 h..+14 h).
     *
     * The panel's own clock pair is derived automatically ([ServerClockOffset]), but every mature
     * player ships this escape hatch — iptvsimple's `catchup-correction`, TiviMate's per-playlist
     * EPG offset, XUI's server-side `epg_shift` — because geo-mismatched panels that lie about
     * their own clock are common enough that auto-derivation alone strands those users.
     */
    val catchUpTimeCorrectionMinutes: Int = 0,
    /** What shape this panel last answered — learned, not configured. See [CatchUpWinnerStore]. */
    val catchUpWinner: CatchUpWinner? = null,
    /**
     * Manual GUIDE EPG offset in minutes (same −720..+840 range), added to every EPG epoch at the
     * parse boundary. 0 = auto: [XtreamEpochSkew] detects wall-clock-epoch liar panels per
     * response and subtracts the measured clock-pair offset; a non-zero value overrides that vote
     * entirely. Deliberately separate from [catchUpTimeCorrectionMinutes] — that one shifts the
     * `start` STRING sent to the panel for a replay (a different lie with a different fix); this
     * one shifts what the guide believes about when programmes air.
     */
    val guideEpgCorrectionMinutes: Int = 0,
)

/** The manual correction, clamped to the range the settings UI offers, as milliseconds. */
fun XtreamAccount.catchUpTimeCorrectionMs(): Long =
    catchUpTimeCorrectionMinutes.coerceIn(CATCH_UP_CORRECTION_MIN_MINUTES, CATCH_UP_CORRECTION_MAX_MINUTES) * 60_000L

/** The manual guide offset in milliseconds; null = auto-detect (the default). */
fun XtreamAccount.guideEpgCorrectionMs(): Long? = guideEpgCorrectionMinutes
    .takeIf { it != 0 }
    ?.coerceIn(CATCH_UP_CORRECTION_MIN_MINUTES, CATCH_UP_CORRECTION_MAX_MINUTES)
    ?.let { it * 60_000L }

/** −12 h. Matches iptvsimple's `catchup-correction` range, which is the de-facto spec. */
const val CATCH_UP_CORRECTION_MIN_MINUTES: Int = -12 * 60

/** +14 h — the widest real UTC offset (Kiritimati), so no panel is out of reach. */
const val CATCH_UP_CORRECTION_MAX_MINUTES: Int = 14 * 60

fun XtreamAccount.typeEnabled(type: String): Boolean = type in contentTypes

/** null selection = every category incl. future ones; a list = only those ids. */
fun XtreamAccount.allowsCategory(type: String, categoryId: String?): Boolean {
    val selection = categorySelections.forType(type) ?: return true
    return categoryId != null && categoryId in selection
}

/**
 * True when [other] is reached over exactly the same connection as this account: same source type,
 * same host/URL, same credentials, same STB identity.
 *
 * An edit that leaves this true changed only the shared options (name, EPG url, DNS resolver,
 * refresh interval), so it must NOT be gated behind a live verify. Gating it created a catch-22: a
 * playlist whose provider is currently unreachable could not be edited at all — including to change
 * the DNS resolver, which is one of the few settings that can *fix* an unreachable provider (a
 * filtered or broken system resolver on the device).
 */
fun XtreamAccount.sameConnectionAs(other: XtreamAccount): Boolean =
    sourceType == other.sourceType &&
        baseUrl == other.baseUrl &&
        username == other.username &&
        password == other.password &&
        userAgent == other.userAgent &&
        macAddress == other.macAddress &&
        stalkerUsername == other.stalkerUsername &&
        stalkerPassword == other.stalkerPassword &&
        serialNumber == other.serialNumber &&
        deviceId == other.deviceId &&
        sendDeviceId == other.sendDeviceId

data class XtreamCategory(val id: String, val name: String)

data class XtreamChannel(
    val streamId: Int,
    val name: String,
    val logo: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val hasArchive: Boolean,
    /** Panel-declared catch-up window in days; 0 = unknown (three real panels: 3 vs 7, so never assume). */
    val catchUpDays: Int = 0,
    val streamUrl: String
)

data class XtreamMovie(
    val streamId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val rating: String?,
    val streamUrl: String,
    val tmdb: Int? = null,
    val containerExtension: String? = null
)

data class XtreamSeriesItem(
    val seriesId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: String?,
    val tmdb: Int? = null,
    val year: Int? = null
)

data class XtreamProgram(
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val nowPlaying: Boolean,
    /**
     * Per-programme `has_archive`: true/false = the panel spoke, null = it said nothing (every
     * get_short_epg row). Feeds [XtreamCatchUp.actionFor]'s positive override.
     */
    val hasArchive: Boolean? = null
)

data class XtreamAccountInfo(
    val status: String?,
    val isTrial: Boolean,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val expiresText: String? = null,   // Stalker portals return expiry as free text (e.g. "February 20, 2027")
)

// get_vod_info + get_series_info are parsed by hand in XtreamClient (panels send `info` as
// object-or-[] inconsistently), so no strict *_info DTOs live here.

// --- detail domain models ---------------------------------------------------

data class XtreamVodDetail(
    val name: String?,
    val plot: String?,
    val genres: List<String>,
    val rating: String?,
    val releaseDate: String?,
    val tmdbId: Int?,
    val containerExtension: String?
)

data class XtreamSeriesDetail(
    val name: String?,
    val poster: String?,
    val tmdbId: Int?,
    val plot: String?,
    val genres: List<String>,
    val rating: String?,
    /** First-air date — the only verify signal old panels give for series (no tmdb there). */
    val releaseDate: String?,
    val episodes: List<XtreamEpisode>
)

data class XtreamEpisode(
    val episodeId: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val plot: String?,
    val still: String?,
    val containerExtension: String?
)
