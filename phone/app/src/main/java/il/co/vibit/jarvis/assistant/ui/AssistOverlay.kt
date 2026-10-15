package il.co.vibit.jarvis.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.co.vibit.jarvis.assistant.R
import il.co.vibit.jarvis.assistant.engine.AssistantEngine
import il.co.vibit.jarvis.assistant.engine.Phase
import il.co.vibit.jarvis.assistant.ui.components.EnergyOrb
import il.co.vibit.jarvis.assistant.ui.components.VoiceWave
import il.co.vibit.jarvis.assistant.ui.theme.JarvisColors

/** Compact bottom panel shown by the system assistant session (also over the lock screen). */
@Composable
fun AssistOverlay(engine: AssistantEngine, onMic: () -> Unit, onClose: () -> Unit) {
    val st by engine.state.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF2071A2A), Color(0xFA02060D))))
                .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {} // swallow taps
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("J.A.R.V.I.S", color = JarvisColors.Cyan, fontSize = 16.sp, letterSpacing = 4.sp, modifier = Modifier.weight(1f))
                Text(phaseLabel(st.phase), color = JarvisColors.CyanSoft, fontSize = 14.sp)
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), "סגירה", tint = JarvisColors.TextDim)
                }
            }
            Box(
                Modifier.size(150.dp).clip(CircleShape).clickable(onClick = onMic),
                contentAlignment = Alignment.Center,
            ) { EnergyOrb(st.phase, st.level, Modifier.fillMaxSize()) }
            VoiceWave(st.level, st.phase == Phase.LISTENING || st.phase == Phase.SPEAKING, Modifier.fillMaxWidth().height(40.dp))
            val user = if (st.phase == Phase.LISTENING) st.partial else st.lastUser
            if (!user.isNullOrBlank()) Text("„$user“", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            st.lastReply?.takeIf { st.phase != Phase.LISTENING || st.partial.isEmpty() }?.let {
                Text(it, color = JarvisColors.Cyan, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
