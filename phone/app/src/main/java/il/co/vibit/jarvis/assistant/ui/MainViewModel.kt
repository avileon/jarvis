package il.co.vibit.jarvis.assistant.ui

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.onnxruntime.OrtEnvironment
import il.co.vibit.jarvis.assistant.JarvisApp
import il.co.vibit.jarvis.assistant.engine.Source
import il.co.vibit.jarvis.assistant.service.ListeningService
import il.co.vibit.jarvis.assistant.settings.AppSettings
import il.co.vibit.jarvis.assistant.voice.JarvisVoiceInteractionService
import il.co.vibit.jarvis.assistant.voice.stt.SttSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Permissions(
    val mic: Boolean = false,
    val contacts: Boolean = false,
    val call: Boolean = false,
    val notifications: Boolean = false,
) {
    val essentialsGranted get() = mic && contacts && call
}

data class SystemStatus(
    val defaultAssistant: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val permissions: Permissions = Permissions(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JarvisApp

    val engineState = app.engine.state
    val settings = app.settings.state
    val ttsStatus = app.tts.status
    val driving = app.driving
    val listening = ListeningService.running
    val detecting = ListeningService.detecting
    val wakeScore = ListeningService.score
    val vis = JarvisVoiceInteractionService.active

    private val _system = MutableStateFlow(SystemStatus())
    val system: StateFlow<SystemStatus> = _system.asStateFlow()

    private val _stt = MutableStateFlow<SttSupport?>(null)
    val stt: StateFlow<SttSupport?> = _stt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val roles = app.getSystemService(RoleManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        _system.value = SystemStatus(
            defaultAssistant = runCatching { roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false) ||
                JarvisVoiceInteractionService.active.value,
            batteryUnrestricted = power.isIgnoringBatteryOptimizations(app.packageName),
            permissions = Permissions(
                mic = granted(Manifest.permission.RECORD_AUDIO),
                contacts = granted(Manifest.permission.READ_CONTACTS),
                call = granted(Manifest.permission.CALL_PHONE),
                notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
        app.contacts.invalidate()
    }

    fun checkStt() {
        viewModelScope.launch { _stt.value = app.stt.checkSupport(app.settings.current.sttLanguage) }
    }

    fun downloadHebrewStt() {
        val ok = app.stt.downloadHebrewModel(app.settings.current.sttLanguage)
        _message.value = if (ok) "הבקשה להורדת מודל העברית נשלחה למערכת." else "המכשיר לא מאפשר הורדת מודל מקומי דרך האפליקציה."
    }

    fun toggleMic() {
        if (app.engine.isBusy) app.engine.cancel() else app.engine.start(Source.BUTTON)
    }

    fun startListening() = app.engine.start(Source.BUTTON)

    fun submitText(text: String) { if (text.isNotBlank()) app.engine.submitText(text) }

    fun setWakeWord(enabled: Boolean) {
        app.settings.update { it.copy(wakeWordEnabled = enabled) }
        if (enabled) {
            if (!ListeningService.start(app)) _message.value = "אנדרואיד לא אישר להפעיל את המיקרופון ברקע."
        } else ListeningService.stop(app)
    }

    fun update(block: (AppSettings) -> AppSettings) = app.settings.update(block)

    fun previewVoice() {
        viewModelScope.launch { app.tts.speak("שלום, אני ג׳ארביס. השעה שתים עשרה וחצי. בשמחה, מחייגת לשלי.") }
    }

    fun importWakeModel(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    require(bytes.size in 10_000..50_000_000) { "גודל קובץ לא סביר" }
                    // Validate that it is a loadable ONNX model before using it.
                    OrtEnvironment.getEnvironment().createSession(bytes).use { s ->
                        require(s.inputNames.isNotEmpty()) { "מודל ללא קלט" }
                    }
                    val f = app.settings.customWakeModel
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                }
            }
            result.onSuccess {
                app.settings.update { it.copy(useCustomWakeModel = true) }
                _message.value = "המודל יובא. הוא ישמש לזיהוי מילת ההפעלה."
            }.onFailure { _message.value = "ייבוא המודל נכשל: ${it.message}" }
        }
    }

    fun deleteWakeModel() {
        app.settings.customWakeModel.delete()
        app.settings.update { it.copy(useCustomWakeModel = false) }
    }

    fun hasCustomModel() = app.settings.customWakeModel.exists()

    fun clearMessage() { _message.value = null }

    fun cancel() = app.engine.cancel()
}
