package com.arnav.island.settings.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.storage.IslandSettings
import kotlin.math.abs
import kotlin.math.max

/** One spring's step response, sampled with the same closed-form spring the island uses. */
private class SpringCurve(spec: SpringSpec, val seconds: Float, samples: Int = 150) {
    val values = FloatArray(samples)
    val settleSeconds: Float
    val overshoot: Float

    init {
        val spring = Spring(0f, spec, restThreshold = 0.0005f)
        spring.animateTo(1f)
        val dt = seconds / (samples - 1)
        for (i in 1 until samples) {
            spring.step(dt)
            values[i] = spring.value
        }
        var settle = samples - 1
        while (settle > 0 && abs(values[settle - 1] - 1f) < 0.01f) settle--
        settleSeconds = settle * dt
        overshoot = max(0f, values.max() - 1f)
    }

    fun at(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f) * (values.size - 1)
        val i = x.toInt().coerceAtMost(values.size - 2)
        val f = x - i
        return values[i] + (values[i + 1] - values[i]) * f
    }
}

/**
 * Motion lab: the island's expand and collapse springs drawn as curves, with a playhead and a
 * small island that moves exactly as the real one will. Updates live as the sliders move.
 */
@Composable
fun MotionLab(s: IslandSettings) {
    val context = LocalContext.current
    val scale = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).takeIf { it > 0f } ?: 1f }
    val profile = remember(s.motionPreset, s.animationSpeed, s.animationIntensity, s.customResponse, s.customDamping, s.followSystemAnimationSpeed) {
        s.motionProfile(systemReduceMotion = false, systemSpeed = 1f / scale.coerceIn(0.25f, 4f))
    }
    val window = 1.4f
    val expand = remember(profile) { SpringCurve(profile.widthExpand, window) }
    val collapse = remember(profile) { SpringCurve(profile.widthCollapse, window) }

    val transition = rememberInfiniteTransition(label = "motion-lab")
    // Play for the curve window, then rest briefly before looping.
    val clock by transition.animateFloat(0f, window + 0.7f, infiniteRepeatable(tween(((window + 0.7f) * 1000).toInt(), easing = LinearEasing)), label = "t")
    val t = (clock / window).coerceIn(0f, 1f)

    val primary = MaterialTheme.colorScheme.primary
    val secondary = Color(0xFFFF8A5C)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val text = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Motion lab", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Legend(primary, "Expand")
            Spacer(Modifier.width(12.dp))
            Legend(secondary, "Collapse")
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val top = size.height * 0.16f
            val base = size.height * 0.92f
            fun y(v: Float) = base - (base - top) * v
            // Target and start lines.
            drawLine(grid, Offset(0f, y(1f)), Offset(size.width, y(1f)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), 1.dp.toPx())
            curve(collapse, secondary, ::y)
            curve(expand, primary, ::y)
            // Playhead.
            val x = size.width * t
            drawLine(text.copy(alpha = 0.35f), Offset(x, top - 8.dp.toPx()), Offset(x, base), 1.dp.toPx())
            drawCircle(secondary, 4.dp.toPx(), Offset(x, y(collapse.at(t))))
            drawCircle(primary, 5.dp.toPx(), Offset(x, y(expand.at(t))))
        }
        Spacer(Modifier.height(10.dp))
        // A small island driven by the expand curve.
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val v = expand.at(t)
                val w = 36.dp.toPx() + (size.width * 0.72f - 36.dp.toPx()) * v
                val h = 26.dp.toPx() + 10.dp.toPx() * v
                drawRoundRect(Color.Black, Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h), CornerRadius(h / 2f))
                drawCircle(Color(0xFF1C2033), 5.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Settles", "${(expand.settleSeconds * 1000).toInt()} ms")
            Stat("Overshoot", "${(expand.overshoot * 100).toInt()}%")
            Stat("Collapse", "${(collapse.settleSeconds * 1000).toInt()} ms")
        }
    }
}

private fun DrawScope.curve(c: SpringCurve, color: Color, y: (Float) -> Float) {
    val path = Path()
    val n = c.values.size
    for (i in 0 until n) {
        val x = size.width * i / (n - 1)
        if (i == 0) path.moveTo(x, y(c.values[i])) else path.lineTo(x, y(c.values[i]))
    }
    drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
