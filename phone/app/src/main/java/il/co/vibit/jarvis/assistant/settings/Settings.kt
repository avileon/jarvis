package il.co.vibit.jarvis.assistant.settings

import android.content.Context
import il.co.vibit.jarvis.core.dialog.DialogSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class AppSettings(
    /** Master switch for wake word listening ("מתג האזנה"). */
    val wakeWordEnabled: Boolean = false,
    /** 0.3 (sensitive) … 0.8 (strict). */
    val wakeThreshold: Float = 0.5f,
    /** Use a user-imported Hebrew model instead of the bundled English "hey jarvis" one. */
    val useCustomWakeModel: Boolean = false,
    /** Prefer the on-device recogniser (offline). Falls back to the system recogniser when unavailable. */
    val preferOnDeviceStt: Boolean = true,
    /** Allow the system recogniser to use its online service when no offline Hebrew pack is installed. */
    val allowOnlineStt: Boolean = true,
    val sttLanguage: String = "he-IL",
    val ttsEngine: String? = null,
    val ttsVoice: String? = null,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val confirmEveryCall: Boolean = false,
    val confirmSpokenNumbers: Boolean = true,
    /** Place calls directly (CALL_PHONE). When off, the dialer opens with the number filled in. */
    val directCall: Boolean = true,
    val earcon: Boolean = true,
) {
    val dialog: DialogSettings get() = DialogSettings(confirmEveryCall, confirmSpokenNumbers)
}

/** Small SharedPreferences-backed store exposed as a StateFlow. Nothing here is sensitive. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    val customWakeModel: File = File(context.filesDir, "wakeword/custom.onnx")

    private fun read() = AppSettings(
        wakeWordEnabled = prefs.getBoolean("wake", false),
        wakeThreshold = prefs.getFloat("wake_threshold", 0.5f),
        useCustomWakeModel = prefs.getBoolean("wake_custom", false),
        preferOnDeviceStt = prefs.getBoolean("stt_on_device", true),
        allowOnlineStt = prefs.getBoolean("stt_online", true),
        sttLanguage = prefs.getString("stt_lang", "he-IL") ?: "he-IL",
        ttsEngine = prefs.getString("tts_engine", null),
        ttsVoice = prefs.getString("tts_voice", null),
        ttsRate = prefs.getFloat("tts_rate", 1.0f),
        ttsPitch = prefs.getFloat("tts_pitch", 1.0f),
        confirmEveryCall = prefs.getBoolean("confirm_call", false),
        confirmSpokenNumbers = prefs.getBoolean("confirm_number", true),
        directCall = prefs.getBoolean("direct_call", true),
        earcon = prefs.getBoolean("earcon", true),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putBoolean("wake", s.wakeWordEnabled)
            .putFloat("wake_threshold", s.wakeThreshold)
            .putBoolean("wake_custom", s.useCustomWakeModel)
            .putBoolean("stt_on_device", s.preferOnDeviceStt)
            .putBoolean("stt_online", s.allowOnlineStt)
            .putString("stt_lang", s.sttLanguage)
            .putString("tts_engine", s.ttsEngine)
            .putString("tts_voice", s.ttsVoice)
            .putFloat("tts_rate", s.ttsRate)
            .putFloat("tts_pitch", s.ttsPitch)
            .putBoolean("confirm_call", s.confirmEveryCall)
            .putBoolean("confirm_number", s.confirmSpokenNumbers)
            .putBoolean("direct_call", s.directCall)
            .putBoolean("earcon", s.earcon)
            .apply()
        _state.value = s
    }
}
