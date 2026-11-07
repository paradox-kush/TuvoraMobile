package com.tuvora.tvos.storage

/** Where one stored value lives on Apple TV. */
enum class StorageTier {
    /** Keychain: session tokens, playlist logins, service API keys. Survives, and is not a plain file. */
    Secure,
    /** User defaults, budgeted: the small core that must survive a Caches purge. */
    Durable,
    /** A file in Caches: everything rebuildable. tvOS may delete it while the app isn't running. */
    Cache,
}

/**
 * Apple TV storage tiers for the shared code's key-value writes (Apple's documented limits: user
 * defaults warn at 512 KB and the app is terminated at 1 MB; everything else must be purgeable).
 *
 * Unknown keys default to [StorageTier.Cache] on purpose. Sync cursors ("last pulled at") are
 * unknown keys, and they must be purged together with the data they describe — a purge that kept a
 * cursor saying "up to date" while dropping the data would never pull it again.
 */
object TvStorageTierPolicy {
    const val DURABLE_BUDGET_BYTES = 400_000
    const val DURABLE_KEY_CAP_BYTES = 64_000

    private val secureExact = setOf(
        "xtream_accounts", "trakt_auth_payload", "simkl_access_token", "simkl_auth_metadata", "simkl_code_verifier",
    )

    private val durableExact = setOf(
        // identity and backend
        "anonymous_user_id", "client_instance_id",
        // telemetry identity and consent: a cache purge must neither rotate the support code nor
        // silently turn reporting back on for someone who switched it off
        "tuvora.support_code", "sentry_enabled", "local_data_owner_user_id", "local_data_owner_email",
        "nuvio_sync_backend_selection_payload_v1",
        // profiles and addons
        "profile_payload", "installed_manifest_urls", "installed_manifest_enabled_states",
        // the viewer's own collections, small and precious when signed out
        "library_payload", "collections_payload",
        // preference stores
        "catalog_settings_payload", "meta_screen_settings_payload", "continue_watching_preferences_payload",
        "library_display_settings_payload", "collection_mobile_settings_payload", "card_depth_style_payload",
        "poster_card_style_payload", "trakt_settings_payload", "stream_badge_rules", "debrid_stream_badge_rules",
        "show_addon_logo", "show_file_size_badges", "stream_background_mode", "stream_badge_placement",
        "season_view_mode", "recent_searches_enabled", "comments_enabled", "discover_catalog_key",
        "selected_theme", "amoled_enabled", "custom_theme_colors", "selected_app_language", "nav_bar_style",
        "nav_bar_glow_enabled", "p2p_enabled", "enable_upload", "hide_torrent_stats", "torrent_profile",
    )

    private val durablePrefixes = listOf(
        "server_", "tuvora.telemetry.", "custom_poster_", "tmdb_", "mdblist_", "debrid_",
        // player preference keys (PlayerSettingsStorage / PlayerTrackPreferenceStorage)
        "preferred_", "secondary_preferred_", "subtitle_", "ios_", "stream_auto_play_", "stream_reuse_",
        "next_episode_", "auto_skip_", "skip_intro_", "hold_to_speed_", "resize_mode", "playback_brightness",
        "show_loading_overlay", "show_player_loading_status", "show_stream_info", "show_parental_guide",
        "pause_overlay_enabled", "use_libass", "libass_render_type", "decoder_priority", "animeskip_",
    )

    fun tierFor(key: String): StorageTier {
        if (key.startsWith("sb-") && key.endsWith("-session")) return StorageTier.Secure
        if (key.contains("api_key")) return StorageTier.Secure
        val base = baseKey(key)
        if (base in secureExact) return StorageTier.Secure
        if (base == "mdblist_sync" || base == "nuvio_mdblist_sync") return StorageTier.Cache
        if (base in durableExact) return StorageTier.Durable
        if (durablePrefixes.any { base.startsWith(it) }) return StorageTier.Durable
        return StorageTier.Cache
    }

    /** Whether a durable value of [sizeBytes] fits, given everything else durable. Over → it spills to Caches. */
    fun admitDurable(sizeBytes: Int, otherDurableBytes: Int): Boolean =
        sizeBytes <= DURABLE_KEY_CAP_BYTES && otherDurableBytes + sizeBytes <= DURABLE_BUDGET_BYTES

    /** Profile-scoped keys are "<base>_<profileId>" (ProfileScopedKey). */
    internal fun baseKey(key: String): String {
        val i = key.lastIndexOf('_')
        if (i <= 0 || i == key.length - 1) return key
        return if (key.substring(i + 1).all(Char::isDigit)) key.substring(0, i) else key
    }
}
