package com.tuvora.tvos.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvStorageTierPolicyTest {

    @Test
    fun `sign-in sessions and playlist logins and api keys go to the keychain`() {
        for (key in listOf("sb-qsonncwknzdixurjyqap-supabase-co-session", "xtream_accounts_1", "trakt_auth_payload_2",
            "simkl_access_token", "debrid_real_debrid_api_key_1", "tmdb_api_key", "mdblist_api_key_3", "debrid_torbox_api_key_1")) {
            assertEquals(StorageTier.Secure, TvStorageTierPolicy.tierFor(key), key)
        }
    }

    @Test
    fun `identity and profiles and addons and preferences are the durable core`() {
        for (key in listOf("anonymous_user_id", "client_instance_id", "local_data_owner_user_id", "server_backend_url",
            "nuvio_sync_backend_selection_payload_v1", "profile_payload", "installed_manifest_urls_1",
            "installed_manifest_enabled_states_1", "selected_theme", "preferred_audio_language_1",
            "subtitle_font_size_sp_2", "catalog_settings_payload_1", "tmdb_language", "library_payload_1")) {
            assertEquals(StorageTier.Durable, TvStorageTierPolicy.tierFor(key), key)
        }
    }

    @Test
    fun `rebuildable data and sync cursors go to caches`() {
        // Cursors must live with the data they describe: a purge that kept "up to date" while
        // dropping the data would never re-pull it.
        for (key in listOf("radar_fixtures_1", "watch_progress_payload_1", "radar_catalog_1", "avatar_catalog_payload",
            "xtream_sync_state_1", "xtream_refresh_state_1", "radar_state_1", "watched_payload_1",
            "episode_release_notifications_payload_1", "something_new_upstream_added")) {
            assertEquals(StorageTier.Cache, TvStorageTierPolicy.tierFor(key), key)
        }
    }

    @Test
    fun `a durable value is admitted only within the per-key cap and the total budget`() {
        assertTrue(TvStorageTierPolicy.admitDurable(sizeBytes = 2_000, otherDurableBytes = 100_000))
        assertFalse(TvStorageTierPolicy.admitDurable(sizeBytes = TvStorageTierPolicy.DURABLE_KEY_CAP_BYTES + 1, otherDurableBytes = 0))
        assertFalse(TvStorageTierPolicy.admitDurable(sizeBytes = 20_000, otherDurableBytes = TvStorageTierPolicy.DURABLE_BUDGET_BYTES - 10_000))
    }

    @Test
    fun `the budget stays well under the tvOS warning line`() {
        // tvOS posts a warning at 512 KB of user defaults and terminates the app at 1 MB.
        assertTrue(TvStorageTierPolicy.DURABLE_BUDGET_BYTES <= 400_000)
    }

    @Test
    fun `support code and the reports consent survive a cache purge`() {
        assertEquals(StorageTier.Durable, TvStorageTierPolicy.tierFor("tuvora.support_code"))
        assertEquals(StorageTier.Durable, TvStorageTierPolicy.tierFor("sentry_enabled"))
        assertEquals(StorageTier.Durable, TvStorageTierPolicy.tierFor("tuvora.telemetry.file.pending_crash.json"))
    }
}
