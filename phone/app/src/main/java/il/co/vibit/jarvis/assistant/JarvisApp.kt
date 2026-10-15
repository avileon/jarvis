package il.co.vibit.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.car.app.connection.CarConnection
import il.co.vibit.jarvis.assistant.contacts.AndroidContactSource
import il.co.vibit.jarvis.assistant.engine.AssistantEngine
import il.co.vibit.jarvis.assistant.settings.Settings
import il.co.vibit.jarvis.assistant.telephony.CallPlacer
import il.co.vibit.jarvis.assistant.voice.stt.SpeechInput
import il.co.vibit.jarvis.assistant.voice.tts.JarvisTts
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual dependency container (small app — no DI framework). */
class JarvisApp : Application() {
    lateinit var settings: Settings private set
    lateinit var tts: JarvisTts private set
    lateinit var stt: SpeechInput private set
    lateinit var contacts: AndroidContactSource private set
    lateinit var callPlacer: CallPlacer private set
    lateinit var engine: AssistantEngine private set

    private val _driving = MutableStateFlow(false)
    /** True while the phone is projecting to Android Auto. */
    val driving: StateFlow<Boolean> = _driving.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        tts = JarvisTts(this)
        stt = SpeechInput(this)
        contacts = AndroidContactSource(this)
        callPlacer = CallPlacer(this)
        engine = AssistantEngine(this, settings, tts, stt, contacts, callPlacer)
        createChannels()

        val scope = MainScope()
        scope.launch {
            settings.state
                .map { Triple(it.ttsEngine, it.ttsVoice, it.ttsRate to it.ttsPitch) }
                .distinctUntilChanged()
                .collect { (engine, voice, rp) -> tts.init(engine, voice, rp.first, rp.second) }
        }
        runCatching {
            CarConnection(this).type.observeForever { type ->
                _driving.value = type == CarConnection.CONNECTION_TYPE_PROJECTION
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_ALERTS = "alerts"
        lateinit var instance: JarvisApp private set
    }
}
