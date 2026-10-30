package com.nuvio.app.features.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SubtitleAppearance(
    val fontId: String = "default",
    val backgroundRadius: Int = 0,
    val shadowEnabled: Boolean = false,
    val shadowOpacity: Int = 75,
    val shadowSize: Int = 2,
) {
    fun normalized(): SubtitleAppearance = copy(
        fontId = fontId.takeIf { it in BuiltInSubtitleFonts || it.matches(Regex("custom-[a-f0-9-]{36}")) } ?: "default",
        backgroundRadius = backgroundRadius.coerceIn(0, 24),
        shadowOpacity = shadowOpacity.coerceIn(0, 100),
        shadowSize = shadowSize.coerceIn(1, 8),
    )
}

internal val BuiltInSubtitleFonts = listOf("default", "sans", "serif", "mono")
private val subtitleAppearanceJson = Json { ignoreUnknownKeys = true }

internal fun decodeSubtitleAppearance(value: String?): SubtitleAppearance =
    value?.let { runCatching { subtitleAppearanceJson.decodeFromString<SubtitleAppearance>(it).normalized() }.getOrNull() }
        ?: SubtitleAppearance()

internal fun SubtitleAppearance.encode(): String = subtitleAppearanceJson.encodeToString(normalized())

/**
 * Whether the Compose styled overlay (not the engine's native subtitle renderer) paints the text.
 *
 * The F47 default look (soft translucent box, owner-approved) is rendered natively on purpose: mpv's
 * background-box and Exo's padded cues already draw it, and it must not push every new user onto the
 * overlay path. Anything the user customises beyond that default (font, shadow, corner radius, or a
 * different box colour/opacity) moves to the overlay, exactly as upstream does for a box.
 */
internal val SubtitleStyleState.usesStyledOverlay: Boolean
    get() = appearance.fontId != "default" ||
        appearance.shadowEnabled ||
        appearance.backgroundRadius > 0 ||
        (backgroundColor.alpha > 0f && backgroundColor != SubtitleStyleState.DEFAULT.backgroundColor)

object SubtitleRendering {
    fun supportsTextOverlay(codec: String): Boolean = codec.lowercase() in setOf(
        "subrip", "srt", "webvtt", "text", "mov_text", "microdvd", "mpl2", "subviewer", "subviewer1", "sami", "realtext",
    )
}

internal fun SubtitleStyleState.resetAppearance(): SubtitleStyleState = SubtitleStyleState.DEFAULT.copy(
    stripSdh = stripSdh,
    useForcedSubtitles = useForcedSubtitles,
    showOnlyPreferredLanguages = showOnlyPreferredLanguages,
)
