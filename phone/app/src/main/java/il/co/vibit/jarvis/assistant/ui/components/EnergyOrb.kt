package il.co.vibit.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import il.co.vibit.jarvis.assistant.engine.Phase
import il.co.vibit.jarvis.assistant.ui.theme.JarvisColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Animated "arc reactor" energy orb. Reacts to the conversation phase and the live microphone level. */
@Composable
fun EnergyOrb(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val speed = when (phase) { Phase.THINKING -> 1200; Phase.LISTENING -> 4000; Phase.IDLE -> 14000; else -> 6000 }
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "rot")
    val rot2 by t.animateFloat(360f, 0f, infiniteRepeatable(tween(speed * 3 / 2, easing = LinearEasing)), label = "rot2")
    val breathe by t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")
    val lvl by animateFloatAsState(level, spring(stiffness = 300f), label = "lvl")

    val core by animateColorAsState(
        when (phase) {
            Phase.IDLE -> JarvisColors.Cyan.copy(alpha = 0.55f)
            Phase.LISTENING -> JarvisColors.Cyan
            Phase.THINKING -> JarvisColors.Blue
            Phase.SPEAKING -> JarvisColors.CyanSoft
            Phase.CALLING -> JarvisColors.Green
        }, tween(500), label = "core",
    )

    Canvas(modifier) {
        val r = min(size.width, size.height) / 2f
        val c = center
        val pulse = breathe + lvl * 0.25f

        // Outer glow
        drawCircle(
            Brush.radialGradient(listOf(core.copy(alpha = 0.35f), Color.Transparent), c, r * 1.0f * pulse),
            radius = r * pulse, center = c,
        )
        // Rotating segmented rings
        rotate(rot, c) {
            val rr = r * 0.82f
            for (i in 0 until 6) {
                drawArc(
                    core.copy(alpha = 0.8f), startAngle = i * 60f, sweepAngle = 38f, useCenter = false,
                    topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                    style = Stroke(width = r * 0.025f, cap = StrokeCap.Round),
                )
            }
        }
        rotate(rot2, c) {
            val rr = r * 0.70f
            for (i in 0 until 3) {
                drawArc(
                    JarvisColors.Blue.copy(alpha = 0.7f), startAngle = i * 120f, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                    style = Stroke(width = r * 0.04f, cap = StrokeCap.Round),
                )
            }
            // tick marks
            val tr = r * 0.92f
            for (i in 0 until 60 step 2) {
                val a = Math.toRadians(i * 6.0)
                val p1 = Offset(c.x + (tr * cos(a)).toFloat(), c.y + (tr * sin(a)).toFloat())
                val p2 = Offset(c.x + (tr * 0.96f * cos(a)).toFloat(), c.y + (tr * 0.96f * sin(a)).toFloat())
                drawLine(core.copy(alpha = 0.35f), p1, p2, strokeWidth = r * 0.008f)
            }
        }
        // Orbiting particles
        for (i in 0 until 8) {
            val a = Math.toRadians((rot * (1 + i % 3) + i * 45).toDouble())
            val pr = r * (0.55f + 0.05f * (i % 3))
            drawCircle(JarvisColors.CyanSoft.copy(alpha = 0.8f), radius = r * 0.012f,
                center = Offset(c.x + (pr * cos(a)).toFloat(), c.y + (pr * sin(a)).toFloat()))
        }
        // Core
        val coreR = r * 0.42f * pulse
        drawCircle(Brush.radialGradient(listOf(Color.White, core, core.copy(alpha = 0.15f), Color.Transparent), c, coreR), coreR, c)
        drawCircle(core, radius = r * 0.47f * pulse, center = c, style = Stroke(width = r * 0.012f))
    }
}
