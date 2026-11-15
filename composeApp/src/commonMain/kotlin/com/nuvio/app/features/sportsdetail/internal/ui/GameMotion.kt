package com.nuvio.app.features.sportsdetail.internal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlin.time.Clock
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.internal.MomentBatch
import com.nuvio.app.features.sportsdetail.internal.MotionSpec
import com.nuvio.app.features.sportsdetail.internal.TeamColor
import com.nuvio.app.features.sportsdetail.internal.platformReduceMotion

/** The moments of the current snapshot, handed down so the smallest composable that shows a value can animate it. */
internal val LocalMoments = staticCompositionLocalOf { MomentBatch(0L, emptyList()) }

internal val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Every animation spec goes through here: reduce motion -> snap(), nothing else decides. */
internal fun <T> motion(ms: Int, reduce: Boolean, enter: Boolean = false): FiniteAnimationSpec<T> =
    if (reduce) snap() else tween(ms, easing = if (enter) EnterEasing else StandardEasing)

/** Which animations already played (see [PlayedLedger]); provided once by the screen so it outlives list items and tab switches. */
internal val LocalPlayed = staticCompositionLocalOf { PlayedLedger() }

/** The OS reduce-motion setting, re-read every time the screen resumes (the user may change it in Settings meanwhile). */
@Composable
internal fun rememberReduceMotion(): Boolean {
    var reduce by remember { mutableStateOf(platformReduceMotion()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reduce = platformReduceMotion() }
    return reduce
}

/** Home / away colours made readable on the dark surface; null falls back to accent (home) and borderStrong (away). */
internal data class TeamPalette(val home: Color, val away: Color)

@Composable
internal fun rememberTeamPalette(header: Header?): TeamPalette {
    val colors = MaterialTheme.nuvio.colors
    val surface = colors.surface
    val accent = colors.accent
    val away = colors.borderStrong
    val teams = header as? TeamsHeader
    return remember(teams?.home?.color, teams?.away?.color, teams?.home?.altColor, teams?.away?.altColor, surface, accent, away) {
        if (teams == null) return@remember TeamPalette(accent, away)
        val (h, a) = TeamColor.pair(teams.home, teams.away, hex(surface))
        TeamPalette(h?.let(::fromHex) ?: accent, a?.let(::fromHex) ?: away)
    }
}

internal fun paletteFor(side: String?, p: TeamPalette): Color = if (side == "home") p.home else p.away

private fun hex(c: Color): String = (c.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
private fun fromHex(h: String): Color = Color(0xFF000000L.toInt() or h.toInt(16))

/**
 * Fades in and rises 8 dp when a real moment ([enabled]) arrives in a batch this [slot] has not played yet; recomposition, scrolling
 * back, tab switches and resume replay nothing.
 */
@Composable
internal fun Modifier.enterOnce(slot: String, enabled: Boolean, reduce: Boolean): Modifier {
    val p = rememberEnter(slot, enabled, reduce)
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 8.dp.toPx()
    }
}

@Composable
private fun rememberEnter(slot: String, enabled: Boolean, reduce: Boolean): Animatable<Float, *> {
    val p = remember { Animatable(1f) }
    val ledger = LocalPlayed.current
    val seq = LocalMoments.current.seq
    LaunchedEffect(slot, seq, enabled) {
        if (enabled && !reduce && ledger.claim(slot, seq)) {
            p.snapTo(0f)
            p.animateTo(1f, motion(MotionSpec.QUICK_MS, reduce = false, enter = true))
        } else {
            p.snapTo(1f)
        }
    }
    return p
}

/**
 * Tab/section switch: content composed during the switch fades in over QUICK_MS. Items that only scroll into view later do not
 * fade; nothing on first load (key 0); instant with reduce motion.
 */
@Composable
internal fun SwitchFade(key: Int, reduce: Boolean, content: @Composable () -> Unit) {
    val ledger = LocalPlayed.current
    val p = remember { Animatable(1f) }
    LaunchedEffect(key) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (key > 0 && !reduce && ledger.inSwitchWindow("tab-switch", key, now)) { p.snapTo(0f); p.animateTo(1f, motion(MotionSpec.QUICK_MS, false)) } else p.snapTo(1f)
    }
    androidx.compose.foundation.layout.Box(Modifier.graphicsLayer { alpha = p.value }) { content() }
}
