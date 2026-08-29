package com.vamshi.aiassistant.overlay.composer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min

/**
 * Scrolling amplitude bars, ported from the Chrome extension's dictation
 * waveform (worklogs/chrome-extension `Composer.tsx`).
 *
 * One bar is sampled per [SAMPLE_MS]; the whole strip drifts left by a
 * fraction of a bar-step every frame rather than jumping a slot at a time, and
 * each bar eases up from zero as it appears. Leading space is filled with dim
 * idle dots so the strip always spans the full width.
 *
 * @param frozen stops sampling and scrolling but keeps the last frame drawn,
 *   which is what the transcribing state shows.
 */
@Composable
fun Waveform(
    levels: () -> Float,
    frozen: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    barWidth: Dp = 3.5.dp,
    barGap: Dp = 3.dp,
) {
    val bars = remember { mutableStateListOf<Bar>() }
    var lastSampleNanos by remember { mutableLongStateOf(0L) }
    var nowNanos by remember { mutableLongStateOf(0L) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    // Bar capacity is derived here rather than inside the draw scope: writing
    // state during draw can retrigger layout and spin.
    val density = LocalDensity.current
    val stepPx = with(density) { (barWidth + barGap).toPx() }
    val maxBars = remember(widthPx, stepPx) {
        if (stepPx <= 0f) 2 else ceil(widthPx / stepPx).toInt() + 2
    }

    LaunchedEffect(frozen) {
        if (frozen) return@LaunchedEffect
        while (true) {
            withFrameNanos { frame ->
                nowNanos = frame
                if (lastSampleNanos == 0L) lastSampleNanos = frame
                if (frame - lastSampleNanos >= SAMPLE_MS * 1_000_000L) {
                    lastSampleNanos = frame
                    bars.add(Bar(level = levels().coerceIn(0f, 1f), startedAtNanos = frame))
                    while (bars.size > maxBars) bars.removeAt(0)
                }
            }
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { widthPx = it.width.toFloat() }
    ) {
        val barPx = barWidth.toPx()
        val halfCap = barPx / 2f
        val centerY = size.height / 2f
        val maxHalf = size.height / 2f - halfCap
        val minHalf = MIN_BAR_HEIGHT.toPx() / 2f

        // Continuous drift: by the time the next sample lands the newest bar
        // has travelled exactly one step, so there is no per-slot jump.
        val scrollFraction = if (frozen || lastSampleNanos == 0L) {
            0f
        } else {
            min(1f, (nowNanos - lastSampleNanos) / (SAMPLE_MS * 1_000_000f))
        }
        var x = size.width - halfCap - scrollFraction * stepPx

        fun strokeBar(cx: Float, halfHeight: Float, alpha: Float) {
            val clamped = halfHeight.coerceIn(halfCap, maxHalf.coerceAtLeast(halfCap))
            drawLine(
                color = color,
                start = Offset(cx, centerY - clamped + halfCap),
                end = Offset(cx, centerY + clamped - halfCap),
                strokeWidth = barPx,
                cap = StrokeCap.Round,
                alpha = alpha
            )
        }

        for (i in bars.indices.reversed()) {
            if (x <= -barPx) break
            val bar = bars[i]
            val progress = if (frozen) {
                1f
            } else {
                min(1f, (nowNanos - bar.startedAtNanos) / (EASE_MS * 1_000_000f))
            }
            val level = bar.level * smoothStep(progress)
            strokeBar(x, maxOf(minHalf, level * size.height / 2f), edgeAlpha(x, size.width))
            x -= stepPx
        }

        // Idle dots ahead of the recorded bars, so the strip never looks cut off.
        while (x > -barPx) {
            strokeBar(x, minHalf, edgeAlpha(x, size.width) * IDLE_ALPHA)
            x -= stepPx
        }
    }
}

private data class Bar(val level: Float, val startedAtNanos: Long)

/** Smoothstep, so a new bar rises into place instead of popping in. */
private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

/**
 * Bars dissolve at both edges rather than clipping hard. Canvas has no
 * destination-in compositing here, so the fade is applied per-bar as alpha
 * based on horizontal position - visually equivalent for thin bars.
 */
private fun edgeAlpha(x: Float, width: Float): Float {
    if (width <= 0f) return 1f
    val fade = width * EDGE_FADE
    if (fade <= 0f) return 1f
    val fromLeft = x / fade
    val fromRight = (width - x) / fade
    return min(1f, min(fromLeft, fromRight)).coerceIn(0f, 1f)
}

private const val SAMPLE_MS = 200L
private const val EASE_MS = 240L
private const val EDGE_FADE = 0.06f
private const val IDLE_ALPHA = 0.35f
private val MIN_BAR_HEIGHT = 3.dp
