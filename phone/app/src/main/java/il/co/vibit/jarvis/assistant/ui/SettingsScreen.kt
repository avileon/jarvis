package il.co.vibit.jarvis.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.co.vibit.jarvis.assistant.R
import il.co.vibit.jarvis.assistant.ui.theme.JarvisColors

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
    onImportModel: () -> Unit,
) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val system by vm.system.collectAsStateWithLifecycle()
    val tts by vm.ttsStatus.collectAsStateWithLifecycle()
    val stt by vm.stt.collectAsStateWithLifecycle()
    val score by vm.wakeScore.collectAsStateWithLifecycle()
    val detecting by vm.detecting.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.checkStt() }

    Column(Modifier.fillMaxSize().background(JarvisColors.Background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "חזרה", tint = JarvisColors.Cyan) }
            Text("הגדרות", style = MaterialTheme.typography.titleMedium, color = JarvisColors.Cyan)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            message?.let { Banner(it, "סגירה") { vm.clearMessage() } }

            Section("הרשאות") {
                val p = system.permissions
                StatusLine("מיקרופון", p.mic)
                StatusLine("אנשי קשר (נקראים במכשיר בלבד)", p.contacts)
                StatusLine("חיוג ישיר", p.call)
                StatusLine("התראות (חיווי האזנה)", p.notifications)
                if (!(p.essentialsGranted && p.notifications)) Btn("אישור הרשאות", onRequestPermissions)
                Btn("פרטי האפליקציה בהגדרות המערכת") { SystemIntents.openAppDetails(ctx) }
            }

            Section("עוזרת דיגיטלית ראשית") {
                StatusLine(if (system.defaultAssistant) "ג׳ארביס מוגדרת כעוזרת הראשית" else "ג׳ארביס עדיין לא העוזרת הראשית", system.defaultAssistant)
                Hint("בסמסונג: הגדרות ← אפליקציות ← בחירת אפליקציות ברירת מחדל ← אפליקציית עוזר דיגיטלי ← JARVIS. " +
                    "אחר כך: הגדרות ← תכונות מתקדמות ← מקש צד ← לחיצה ארוכה: עוזר דיגיטלי.")
                Btn("פתיחת הגדרות העוזר הדיגיטלי") { SystemIntents.openAssistantSettings(ctx) }
            }

            Section("מילת הפעלה \"היי ג׳ארביס\"") {
                Toggle("האזנה פעילה", s.wakeWordEnabled) { vm.setWakeWord(it) }
                StatusLine("ללא הגבלת סוללה (נדרש בסמסונג)", system.batteryUnrestricted)
                if (!system.batteryUnrestricted) Btn("ביטול אופטימיזציית סוללה") { SystemIntents.requestUnrestrictedBattery(ctx) }
                Text("רגישות: ${"%.2f".format(s.wakeThreshold)} (נמוך = רגיש יותר)", color = JarvisColors.TextDim, fontSize = 13.sp)
                Slider(
                    value = s.wakeThreshold, onValueChange = { v -> vm.update { it.copy(wakeThreshold = v) } },
                    valueRange = 0.2f..0.85f,
                )
                if (detecting) {
                    Text("ציון זיהוי חי: ${"%.2f".format(score)}", color = JarvisColors.TextDim, fontSize = 13.sp)
                    LinearProgressIndicator(progress = { score }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text("מודל:", color = Color.White, modifier = Modifier.padding(top = 6.dp))
                Choice("מודל מובנה \"hey jarvis\" (אנגלית, ניסיוני לעברית)", !s.useCustomWakeModel) { vm.update { it.copy(useCustomWakeModel = false) } }
                Choice("מודל מותאם אישית (\"ג׳ארביס\" בעברית)" + if (vm.hasCustomModel()) "" else " — לא יובא", s.useCustomWakeModel && vm.hasCustomModel()) {
                    if (vm.hasCustomModel()) vm.update { it.copy(useCustomWakeModel = true) } else onImportModel()
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("ייבוא מודל ‎.onnx", onImportModel)
                    if (vm.hasCustomModel()) Btn("מחיקת המודל") { vm.deleteWakeModel() }
                }
                Hint("המודל המובנה אומן על הגייה אנגלית של \"hey jarvis\" ולא אומת על הגייה עברית. " +
                    "לזיהוי אמין של \"ג׳ארביס\" יש לאמן מודל מותאם (ראו README) ולייבא אותו כאן.")
            }

            Section("זיהוי דיבור בעברית") {
                val sup = stt
                if (sup == null) Text("בודקת…", color = JarvisColors.TextDim) else {
                    StatusLine("מנוע זיהוי מקומי (on-device) קיים במכשיר", sup.onDeviceAvailable)
                    StatusLine(
                        when (sup.hebrewInstalledOnDevice) {
                            true -> "מודל עברית מקומי מותקן — עובד בלי אינטרנט"
                            false -> if (sup.hebrewPending == true) "מודל עברית בהורדה…" else "מודל עברית מקומי לא מותקן"
                            null -> "לא ניתן לבדוק התקנת עברית מקומית"
                        },
                        sup.hebrewInstalledOnDevice == true,
                    )
                    if (sup.hebrewDownloadable == true && sup.hebrewInstalledOnDevice != true) Btn("הורדת מודל עברית מקומי") { vm.downloadHebrewStt() }
                    Text("מנוע גיבוי: ${sup.delegate?.packageName ?: "לא נמצא"}", color = JarvisColors.TextDim, fontSize = 13.sp)
                }
                Toggle("להעדיף זיהוי מקומי במכשיר", s.preferOnDeviceStt) { v -> vm.update { it.copy(preferOnDeviceStt = v) } }
                Toggle("לאפשר זיהוי מקוון כשאין מודל מקומי", s.allowOnlineStt) { v -> vm.update { it.copy(allowOnlineStt = v) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("בדיקה מחדש") { vm.checkStt() }
                    Btn("הגדרות קלט קולי") { SystemIntents.openVoiceInputSettings(ctx) }
                }
                Hint("ב-Google: אפליקציית Google ← הגדרות ← קול ← זיהוי דיבור אופליין ← עברית. ללא מודל מקומי, הזיהוי עשוי לעבור דרך שרתי Google.")
            }

            Section("קול") {
                StatusLine(if (tts.hebrewReady) "קול עברי זמין" else (tts.error ?: "מנוע הדיבור נטען…"), tts.hebrewReady)
                if (tts.engines.isNotEmpty()) {
                    Text("מנוע דיבור:", color = Color.White)
                    tts.engines.forEach { (pkg, label) ->
                        Choice(label, (s.ttsEngine ?: tts.engine) == pkg) { vm.update { it.copy(ttsEngine = pkg, ttsVoice = null) } }
                    }
                }
                if (tts.hebrewVoices.isNotEmpty()) {
                    Text("קול עברי:", color = Color.White, modifier = Modifier.padding(top = 6.dp))
                    tts.hebrewVoices.forEach { v ->
                        val tags = buildList {
                            if (v.networkRequired) add("דורש רשת")
                            if (!v.installed) add("לא מותקן")
                            add("איכות ${v.quality}")
                        }.joinToString(" · ")
                        Choice("${v.name}  ($tags)", tts.activeVoice == v.name) { vm.update { it.copy(ttsVoice = v.name) } }
                    }
                }
                Text("קצב דיבור: ${"%.1f".format(s.ttsRate)}", color = JarvisColors.TextDim, fontSize = 13.sp)
                Slider(s.ttsRate, { v -> vm.update { it.copy(ttsRate = v) } }, valueRange = 0.6f..1.6f)
                Text("גובה קול: ${"%.1f".format(s.ttsPitch)}", color = JarvisColors.TextDim, fontSize = 13.sp)
                Slider(s.ttsPitch, { v -> vm.update { it.copy(ttsPitch = v) } }, valueRange = 0.7f..1.4f)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("השמעת דוגמה") { vm.previewVoice() }
                    Btn("הורדת קולות") { SystemIntents.installTtsData(ctx, s.ttsEngine ?: tts.engine) }
                    Btn("הגדרות דיבור") { SystemIntents.openTtsSettings(ctx) }
                }
                Hint("אנדרואיד לא מסמן מגדר של קולות. האזינו לדוגמה ובחרו קול נשי. ב-Google יש כמה קולות עבריים מקומיים.")
            }

            Section("חיוג ובטיחות") {
                Toggle("חיוג ישיר (בלי לפתוח את החייגן)", s.directCall) { v -> vm.update { it.copy(directCall = v) } }
                Toggle("לשאול \"להתקשר?\" לפני כל שיחה", s.confirmEveryCall) { v -> vm.update { it.copy(confirmEveryCall = v) } }
                Toggle("להקריא מספר שהוכתב לפני חיוג", s.confirmSpokenNumbers) { v -> vm.update { it.copy(confirmSpokenNumbers = v) } }
                Toggle("צליל לפני האזנה", s.earcon) { v -> vm.update { it.copy(earcon = v) } }
                Hint("ג׳ארביס לעולם לא מחייגת לפי התאמה חלקית: אם השם לא זוהה בוודאות, היא שואלת קודם.")
            }

            Section("פרטיות") {
                Hint("• לאפליקציה אין הרשאת אינטרנט — אנשי קשר וקול לא יוצאים מהמכשיר דרכה.\n" +
                    "• מילת ההפעלה מזוהה במכשיר; השמע לא נשמר ולא מוקלט לקובץ.\n" +
                    "• כשהמיקרופון פעיל מוצגת התראה קבועה ונקודה ירוקה של המערכת.\n" +
                    "• אפשר לומר \"ג׳ארביס, תפסיקי להקשיב\" או לכבות את המתג.")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(18.dp)).background(JarvisColors.Surface)
            .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.2f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = JarvisColors.Cyan, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) JarvisColors.Green else JarvisColors.Amber))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = JarvisColors.Cyan))
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Btn(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(text, color = JarvisColors.Cyan) }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = JarvisColors.TextDim, fontSize = 13.sp, lineHeight = 19.sp)
}
