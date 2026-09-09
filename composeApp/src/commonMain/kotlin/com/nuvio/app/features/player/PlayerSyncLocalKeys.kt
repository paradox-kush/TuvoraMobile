package com.nuvio.app.features.player

import kotlinx.serialization.json.JsonObject

/**
 * Player-settings keys that are DEVICE-LOCAL and must never travel in the profile-settings sync blob.
 *
 * These are engine / decoder / renderer / hardware-decode / device-specific enhancement choices whose
 * right value is a function of the *hardware in hand*, not the user's account: a powerful phone's
 * libmpv+decoder pick, or an RTX desktop's super-resolution, has no business overwriting a weak
 * device's working configuration. Two things must hold, and BOTH are enforced here (excluding future
 * uploads alone is not enough — an OLD server payload written before this fix still carries them):
 *  - export never puts them into the blob ([stripLocal] over the built payload), and
 *  - import never applies OR removes them ([stripLocal] over the incoming payload, and the local keys
 *    are excluded from the import's clear-set) — so a stale remote value cannot overwrite the local
 *    choice, and a remote payload that simply omits a key cannot wipe the local value to its default.
 *
 * This mirrors NuvioTV's already-correct `localOnlyPlayerProfileSettingsKeys` decision, restricted to
 * the keys that ACTUALLY exist in the KMP stores (verified against each actual's key constants) — it
 * is deliberately NOT "every hardware-ish key": cosmetic, non-hardware-derived preferences that are
 * safe and useful to carry across a user's devices stay synced (subtitle style/size/colour and
 * language, the iOS brightness/contrast/saturation/gamma colour trims, resize mode, autoplay/next-
 * episode behaviour, external-player choice). Adjusting this set is a shared-behaviour change and
 * lands on every KMP repo (Mobile + Desktop) per the parity rule.
 */
internal object PlayerSyncLocalKeys {
    val keys: Set<String> = setOf(
        // Engine / decoder / renderer / hardware decode (Android + desktop-native decoder_priority).
        "android_playback_engine",
        "android_libmpv_video_output",
        "android_libmpv_hardware_decoding_enabled",
        "android_libmpv_yuv420p_enabled",
        "decoder_priority",
        "map_dv7_to_hevc",
        "tunneling_enabled",
        // Subtitle render engine / path (device-specific; mirrors TV's use_libass + libass_render_type).
        "use_libass",
        "libass_render_type",
        // iOS libmpv render pipeline: output preset, HDR/tone-map, colour targets, hw decoder, audio
        // route, and the GPU-cost enhancements (deband, motion interpolation). Capability-tied.
        "ios_video_output_preset",
        "ios_tone_mapping_mode",
        "ios_target_primaries",
        "ios_target_transfer",
        "ios_hardware_decoder_mode",
        "ios_audio_output_mode",
        "ios_extended_dynamic_range_enabled",
        "ios_target_colorspace_hint_enabled",
        "ios_hdr_compute_peak_enabled",
        "ios_deband_enabled",
        "ios_interpolation_enabled",
        // Desktop GPU enhancement — present only in the desktop store.
        "nvidia_rtx_super_resolution_enabled",
    )

    /** The payload with every device-local key removed — used on BOTH sides: over the freshly built
     *  export (so local keys never upload) and over an incoming payload (so a stale remote value is
     *  never applied). Non-local entries pass through untouched. */
    fun stripLocal(payload: JsonObject): JsonObject =
        JsonObject(payload.filterKeys { it !in keys })

    /** The subset of [allSyncKeys] the import may clear before re-applying: everything EXCEPT the
     *  device-local keys, so an import never wipes a local engine/decoder/renderer value. */
    fun clearableOnImport(allSyncKeys: List<String>): List<String> =
        allSyncKeys.filter { it !in keys }
}
