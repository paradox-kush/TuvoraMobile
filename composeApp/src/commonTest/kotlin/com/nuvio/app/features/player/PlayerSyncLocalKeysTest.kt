package com.nuvio.app.features.player

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSyncLocalKeysTest {
    @Test
    fun `device-specific engine decoder renderer keys are local`() {
        for (k in listOf(
            "android_playback_engine", "android_libmpv_video_output",
            "android_libmpv_hardware_decoding_enabled", "decoder_priority", "tunneling_enabled",
            "map_dv7_to_hevc", "ios_hardware_decoder_mode", "ios_tone_mapping_mode",
            "ios_video_output_preset", "nvidia_rtx_super_resolution_enabled",
        )) {
            assertTrue(k in PlayerSyncLocalKeys.keys, "$k must be device-local")
        }
    }

    @Test
    fun `cosmetic and account preferences stay synced`() {
        for (k in listOf(
            "resize_mode", "preferred_audio_language", "subtitle_text_color", "subtitle_font_size_sp",
            "ios_brightness", "ios_contrast", "ios_saturation", "ios_gamma",
            "external_player_enabled", "stream_auto_play_mode",
        )) {
            assertFalse(k in PlayerSyncLocalKeys.keys, "$k should remain synced (not hardware-derived)")
        }
    }

    @Test
    fun `stripLocal removes local keys and preserves the rest`() {
        val payload = JsonObject(
            mapOf(
                "android_playback_engine" to JsonPrimitive("libmpv"),
                "decoder_priority" to JsonPrimitive(2),
                "preferred_audio_language" to JsonPrimitive("en"),
                "resize_mode" to JsonPrimitive("fill"),
            ),
        )
        val out = PlayerSyncLocalKeys.stripLocal(payload)
        assertFalse("android_playback_engine" in out)
        assertFalse("decoder_priority" in out)
        assertEquals(JsonPrimitive("en"), out["preferred_audio_language"])
        assertEquals(JsonPrimitive("fill"), out["resize_mode"])
    }

    @Test
    fun `clearableOnImport excludes local keys`() {
        val all = listOf("android_playback_engine", "decoder_priority", "preferred_audio_language", "resize_mode")
        assertEquals(listOf("preferred_audio_language", "resize_mode"), PlayerSyncLocalKeys.clearableOnImport(all))
    }
}
