package il.co.vibit.jarvis.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import il.co.vibit.jarvis.assistant.ui.theme.JarvisColors
import kotlin.math.PI
import kotlin.math.sin

/** Layered sine "voice wave" whose amplitude follows the microphone level. */
@Composable
fun VoiceWave(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "p")
    val amp by animateFloatAsState(if (active) 0.12f + level * 0.88f else 0.04f, spring(stiffness = 200f), label = "amp")
    val colors = listOf(JarvisColors.Cyan, JarvisColors.Blue, JarvisColors.Teal)
    Canvas(modifier) {
        val mid = size.height / 2
        colors.forEachIndexed { k, col ->
            val path = Path()
            val freq = 1.5f + k * 0.7f
            val a = amp * (size.height / 2) * (1f - k * 0.25f)
            var x = 0f
            while (x <= size.width) {
                val nx = x / size.width
                // Taper the ends so the wave looks like a voice envelope.
                val env = sin(nx * PI).toFloat()
                val y = mid + a * env * sin(nx * freq * 2 * PI.toFloat() + phase * (1 + k * 0.4f))
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 4f
            }
            drawPath(path, col.copy(alpha = 0.85f - k * 0.2f), style = Stroke(width = 3f - k * 0.6f))
        }
        drawLine(Color.White.copy(alpha = 0.06f), androidx.compose.ui.geometry.Offset(0f, mid),
            androidx.compose.ui.geometry.Offset(size.width, mid))
    }
}
