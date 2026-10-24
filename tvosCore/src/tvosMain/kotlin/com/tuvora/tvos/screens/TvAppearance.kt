package com.tuvora.tvos.screens

import androidx.compose.ui.graphics.toArgb
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.parseHexColor
import com.nuvio.app.core.ui.formatHexColor
import com.nuvio.app.features.settings.ThemeSettingsRepository

data class TvThemePalette(val accent: Long, val variant: Long, val onAccent: Long, val focus: Long,
    val focusBackground: Long, val background: Long, val elevated: Long, val card: Long)

/** Export shared colour/contrast decisions, including the fork's solid-custom-theme rule. */
object TvAppearance {
    fun colorHex(): String {
        ThemeSettingsRepository.ensureLoaded()
        return formatHexColor(ThemeSettingsRepository.customThemeColors.value.second)
    }
    fun palette(): TvThemePalette {
        ThemeSettingsRepository.ensureLoaded()
        val p = ThemeColors.getColorPalette(ThemeSettingsRepository.selectedTheme.value, ThemeSettingsRepository.customThemeColors.value)
        fun argb(v: Int) = v.toLong() and 0xFFFFFFFFL
        return TvThemePalette(argb(p.secondary.toArgb()), argb(p.secondaryVariant.toArgb()), argb(p.onSecondary.toArgb()),
            argb(p.focusRing.toArgb()), argb(p.focusBackground.toArgb()), argb(p.background.toArgb()),
            argb(p.backgroundElevated.toArgb()), argb(p.backgroundCard.toArgb()))
    }
    fun saveSolid(hex: String): String? {
        val color = parseHexColor(hex) ?: return "Enter a six-digit hex color, for example #1E88E5."
        ThemeSettingsRepository.setCustomTheme(CustomThemeColors.solid(color))
        return null
    }
}
