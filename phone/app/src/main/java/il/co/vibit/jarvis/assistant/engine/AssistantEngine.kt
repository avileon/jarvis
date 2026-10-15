package il.co.vibit.jarvis.assistant.engine

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import il.co.vibit.jarvis.assistant.BuildConfig
import il.co.vibit.jarvis.assistant.contacts.AndroidContactSource
import il.co.vibit.jarvis.assistant.service.ListeningService
import il.co.vibit.jarvis.assistant.settings.Settings
import il.co.vibit.jarvis.assistant.telephony.CallPlacer
import il.co.vibit.jarvis.assistant.voice.stt.Backend
import il.co.vibit.jarvis.assistant.voice.stt.SpeechInput
import il.co.vibit.jarvis.assistant.voice.stt.SttEvent
import il.co.vibit.jarvis.assistant.voice.stt.SttResult
import il.co.vibit.jarvis.assistant.voice.tts.JarvisTts
import il.co.vibit.jarvis.core.dialog.Action
import il.co.vibit.jarvis.core.dialog.DialogManager
import il.co.vibit.jarvis.core.dialog.Reply
import il.co.vibit.jarvis.core.skills.Skill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, CALLING }

enum class Source { BUTTON, ASSIST, WAKE_WORD, KEYGUARD, TEXT }

data class Exchange(val user: String, val assistant: String)

data class EngineState(
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val level: Float = 0f,
    val lastUser: String? = null,
    val lastReply: String? = null,
    val history: List<Exchange> = emptyList(),
    val backend: Backend? = null,
    val source: Source? = null,
)

sealed interface EngineEvent {
    data class CallStarted(val number: String, val name: String?) : EngineEvent
    data object ConversationEnded : EngineEvent
}

/**
 * Runs a voice conversation: listen → understand (offline DialogManager) → speak → act → maybe listen again.
 * Used identically by the app screen, the assistant overlay (VoiceInteractionSession) and the wake word service.
 * Main thread only.
 */
class AssistantEngine(
    private val context: Context,
    private val settings: Settings,
    private val tts: JarvisTts,
    private val stt: SpeechInput,
    private val contacts: AndroidContactSource,
    private val callPlacer: CallPlacer,
    /** Future skills (Waze, music, Home Assistant, ChatGPT fallback …) plug in here. */
    skills: List<Skill> = emptyList(),
) {
    private val scope = MainScope()
    private val dialog = DialogManager(contacts, { settings.current.dialog }, skills = skills)
    private var job: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EngineEvent> = _events

    val isBusy: Boolean get() = _state.value.phase != Phase.IDLE

    /** Starts a new spoken conversation (button, assist gesture, wake word…). */
    fun start(source: Source) {
        stopInternal()
        dialog.reset()
        _state.update { it.copy(source = source, partial = "", lastUser = null, lastReply = null, backend = null) }
        job = scope.launch { converse() }
    }

    /** Typed command (for testing without voice or in a quiet place). */
    fun submitText(text: String) {
        stopInternal()
        _state.update { it.copy(source = Source.TEXT) }
        job = scope.launch {
            try {
                if (respond(text)) converse()
            } finally { idle() }
        }
    }

    fun cancel() {
        stopInternal()
        dialog.reset()
        idle()
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        tts.stop()
    }

    private suspend fun converse() {
        try {
            repeat(MAX_TURNS) {
                val text = listen() ?: return
                if (!respond(text)) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "conversation failed", e)
        } finally {
            idle()
        }
    }

    /** Returns recognised text ("" if nothing was said), or null if recognition is impossible (already reported). */
    private suspend fun listen(): String? {
        val s = settings.current
        if (s.earcon) beep()
        _state.update { it.copy(phase = Phase.LISTENING, partial = "", level = 0f) }
        stt.biasingStrings = contacts.load()?.map { it.displayName }.orEmpty()
        val result = stt.listen(s.sttLanguage, s.preferOnDeviceStt, s.allowOnlineStt) { ev ->
            when (ev) {
                is SttEvent.Level -> _state.update { it.copy(level = ev.value) }
                is SttEvent.Partial -> _state.update { it.copy(partial = ev.text) }
                else -> {}
            }
        }
        _state.update { it.copy(level = 0f) }
        return when (result) {
            is SttResult.Text -> {
                _state.update { it.copy(partial = result.text, backend = result.backend) }
                result.text
            }
            SttResult.NoSpeech -> ""
            is SttResult.Failed -> {
                say(Reply(result.spokenMessage), userText = null)
                null
            }
        }
    }

    /** Handles one utterance. Returns true when the assistant asked a question and should listen again. */
    private suspend fun respond(text: String): Boolean {
        _state.update { it.copy(phase = Phase.THINKING) }
        val reply = dialog.handle(text)
        say(reply, userText = text)
        reply.action?.let { perform(it) }
        return reply.expectAnswer
    }

    private suspend fun say(reply: Reply, userText: String?) {
        // Utterances are logged in debug builds only (used by the emulator smoke test).
        if (BuildConfig.DEBUG) Log.i(TAG, "turn user='$userText' reply='${reply.display}' action=${reply.action} expectAnswer=${reply.expectAnswer}")
        _state.update {
            it.copy(
                phase = Phase.SPEAKING,
                lastUser = userText ?: it.lastUser,
                lastReply = reply.display,
                history = (it.history + Exchange(userText.orEmpty(), reply.display)).takeLast(12),
            )
        }
        tts.speak(reply.speech)
    }

    private suspend fun perform(action: Action) {
        when (action) {
            is Action.PlaceCall -> {
                _state.update { it.copy(phase = Phase.CALLING) }
                val result = callPlacer.place(action.number, settings.current.directCall)
                if (BuildConfig.DEBUG) Log.i(TAG, "call ${action.number} -> $result")
                when (result) {
                    CallPlacer.Result.PLACED, CallPlacer.Result.DIALER_OPENED ->
                        _events.tryEmit(EngineEvent.CallStarted(action.number, action.displayName))
                    CallPlacer.Result.FAILED -> say(Reply("לא הצלחתי לחייג. כדאי לבדוק את הרשאת החיוג."), null)
                }
                delay(300)
            }
            Action.StopListening -> {
                settings.update { it.copy(wakeWordEnabled = false) }
                ListeningService.stop(context)
            }
            Action.StartListening -> {
                settings.update { it.copy(wakeWordEnabled = true) }
                ListeningService.start(context)
            }
        }
    }

    private fun idle() {
        if (_state.value.phase != Phase.IDLE) _events.tryEmit(EngineEvent.ConversationEnded)
        _state.update { it.copy(phase = Phase.IDLE, level = 0f) }
    }

    private suspend fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            delay(170)
            tg.release()
        }
    }

    companion object {
        private const val TAG = "AssistantEngine"
        private const val MAX_TURNS = 5
    }
}
