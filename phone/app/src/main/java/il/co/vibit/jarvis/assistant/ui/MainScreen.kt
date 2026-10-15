package il.co.vibit.jarvis.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.co.vibit.jarvis.assistant.R
import il.co.vibit.jarvis.assistant.engine.EngineState
import il.co.vibit.jarvis.assistant.engine.Phase
import il.co.vibit.jarvis.assistant.voice.stt.Backend
import il.co.vibit.jarvis.assistant.ui.components.EnergyOrb
import il.co.vibit.jarvis.assistant.ui.components.VoiceWave
import il.co.vibit.jarvis.assistant.ui.theme.JarvisColors

fun phaseLabel(p: Phase) = when (p) {
    Phase.IDLE -> "מוכנה"
    Phase.LISTENING -> "מקשיבה…"
    Phase.THINKING -> "מעבדת…"
    Phase.SPEAKING -> "מדברת…"
    Phase.CALLING -> "מחייגת…"
}

@Composable
fun MainScreen(vm: MainViewModel, onSettings: () -> Unit, onRequestPermissions: () -> Unit) {
    val st by vm.engineState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val system by vm.system.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val detecting by vm.detecting.collectAsStateWithLifecycle()
    val driving by vm.driving.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF041322), JarvisColors.Background, Color(0xFF01040A))))
            .safeDrawingPadding()
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("J.A.R.V.I.S", style = MaterialTheme.typography.headlineMedium, color = JarvisColors.Cyan)
                    Text("העוזרת האישית שלך", color = JarvisColors.TextDim, fontSize = 13.sp)
                }
                IconButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "הגדרות", tint = JarvisColors.Cyan)
                }
            }

            // Status chips
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (system.defaultAssistant) "עוזרת ראשית ✓" else "לא עוזרת ראשית", system.defaultAssistant)
                StatusChip(if (listening) "האזנה פעילה" else "האזנה כבויה", listening)
                if (driving) StatusChip("מצב נהיגה", true, JarvisColors.Amber)
            }

            if (!system.permissions.essentialsGranted) {
                Banner("כדי לחייג ולהבין פקודות צריך הרשאות למיקרופון, לאנשי הקשר ולחיוג.", "אישור הרשאות", onRequestPermissions)
            }
            message?.let { Banner(it, "סגירה") { vm.clearMessage() } }

            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.size(280.dp).clip(CircleShape).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { vm.toggleMic() },
                contentAlignment = Alignment.Center,
            ) {
                EnergyOrb(st.phase, st.level, Modifier.fillMaxSize())
            }
            Text(phaseLabel(st.phase), color = JarvisColors.CyanSoft, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
            VoiceWave(st.level, st.phase == Phase.LISTENING || st.phase == Phase.SPEAKING, Modifier.fillMaxWidth().height(56.dp))

            Conversation(st)

            Spacer(Modifier.height(12.dp))
            MicButton(st.phase != Phase.IDLE) { vm.toggleMic() }
            Text(
                if (st.phase == Phase.IDLE) "לחצו ודברו, או אמרו \"היי ג׳ארביס\"" else "לחיצה נוספת מבטלת",
                color = JarvisColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(18.dp))
            WakeSwitch(settings.wakeWordEnabled, listening, detecting) { vm.setWakeWord(it) }

            Spacer(Modifier.height(12.dp))
            TypedCommand { vm.submitText(it) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Conversation(st: EngineState) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val user = if (st.phase == Phase.LISTENING) st.partial else st.lastUser
        if (!user.isNullOrBlank()) {
            Text("„$user“", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        st.lastReply?.let {
            Text(it, color = JarvisColors.Cyan, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        st.backend?.let {
            Text(
                if (it == Backend.ON_DEVICE) "זיהוי דיבור: מקומי במכשיר" else "זיהוי דיבור: שירות המערכת",
                color = JarvisColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MicButton(active: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "mic")
    val ring by t.animateFloat(1f, 1.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
    Box(contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size((84 * ring).dp).clip(CircleShape).background(JarvisColors.Cyan.copy(alpha = 0.18f)))
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(JarvisColors.Cyan, JarvisColors.Blue)))
                .border(2.dp, JarvisColors.CyanSoft, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(if (active) R.drawable.ic_close else R.drawable.ic_mic),
                contentDescription = if (active) "ביטול" else "דברו עם ג׳ארביס",
                tint = Color(0xFF00141A), modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun WakeSwitch(enabled: Boolean, running: Boolean, detecting: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(JarvisColors.Surface)
            .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("האזנה ל\"היי ג׳ארביס\"", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (detecting) JarvisColors.Red else JarvisColors.TextDim))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        detecting -> "המיקרופון פעיל (זיהוי מקומי בלבד)"
                        running -> "מושהה בזמן שיחה"
                        enabled -> "ממתינה לאישור אנדרואיד"
                        else -> "כבוי — המיקרופון לא בשימוש"
                    },
                    color = JarvisColors.TextDim, fontSize = 13.sp,
                )
            }
        }
        Switch(enabled, onChange, colors = SwitchDefaults.colors(checkedTrackColor = JarvisColors.Cyan))
    }
}

@Composable
private fun TypedCommand(onSubmit: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    TextButton(onClick = { open = !open }) { Text(if (open) "הסתרת הקלדה" else "הקלדת פקודה במקום דיבור") }
    AnimatedVisibility(open) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("לדוגמה: תתקשרי לשלי") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit(text); text = "" }),
        )
    }
}

@Composable
fun StatusChip(text: String, ok: Boolean, color: Color = if (ok) JarvisColors.Cyan else JarvisColors.TextDim) {
    Text(
        text, color = color, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .background(color.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp).alpha(if (ok) 1f else 0.85f),
    )
}

@Composable
fun Banner(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp))
            .background(JarvisColors.Amber.copy(alpha = 0.12f)).border(1.dp, JarvisColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(text, color = Color.White, fontSize = 14.sp)
        Button(
            onClick = onAction, modifier = Modifier.padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Amber, contentColor = Color.Black),
        ) { Text(action) }
    }
}
