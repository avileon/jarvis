package il.co.vibit.jarvis.assistant.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class VoiceInfo(
    val name: String,
    val locale: String,
    val quality: Int,
    val networkRequired: Boolean,
    val installed: Boolean,
)

data class TtsStatus(
    val ready: Boolean = false,
    val engine: String? = null,
    val engines: List<Pair<String, String>> = emptyList(),
    /** Hebrew can be spoken right now (language data installed). */
    val hebrewReady: Boolean = false,
    val hebrewVoices: List<VoiceInfo> = emptyList(),
    val activeVoice: String? = null,
    val error: String? = null,
)

/**
 * Hebrew text-to-speech through the phone's installed TTS engine (Google Speech Services or Samsung TTS).
 * Speaks on the assistant audio stream with transient ducking focus, so in the car it plays through the
 * car speakers (Android Auto / Bluetooth) and music is lowered rather than stopped.
 */
class JarvisTts(private val context: Context) {
    private val _status = MutableStateFlow(TtsStatus())
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var engineName: String? = null
    private var wantedVoice: String? = null
    private var rate = 1f
    private var pitch = 1f

    fun init(engine: String?, voice: String?, rate: Float, pitch: Float) {
        this.wantedVoice = voice
        this.rate = rate
        this.pitch = pitch
        if (tts != null && engine == engineName) { applyVoice(); return }
        shutdown()
        engineName = engine
        ready = CompletableDeferred()
        val d = ready
        tts = TextToSpeech(context, { st ->
            val ok = st == TextToSpeech.SUCCESS
            if (ok) configure() else _status.value = TtsStatus(error = "מנוע הדיבור לא נטען")
            d.complete(ok)
        }, engine)
    }

    private fun configure() {
        val t = tts ?: return
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.complete(false) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.complete(false) } }
        })
        applyVoice()
    }

    private fun applyVoice() {
        val t = tts ?: return
        val he = Locale.forLanguageTag("he-IL")
        val langResult = runCatching { t.setLanguage(he) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val voices: List<Voice> = runCatching { t.voices?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { it.locale.language == "he" || it.locale.language == "iw" }
        val infos = voices.map {
            VoiceInfo(
                name = it.name,
                locale = it.locale.toLanguageTag(),
                quality = it.quality,
                networkRequired = it.isNetworkConnectionRequired,
                installed = !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
            )
        }
        val chosen = voices.firstOrNull { it.name == wantedVoice }
            ?: voices.filter { !it.isNetworkConnectionRequired && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .maxByOrNull { it.quality }
        if (chosen != null) runCatching { t.voice = chosen }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        _status.value = TtsStatus(
            ready = true,
            engine = engineName ?: runCatching { t.defaultEngine }.getOrNull(),
            engines = runCatching { t.engines.map { it.name to it.label } }.getOrDefault(emptyList()),
            hebrewReady = langResult >= TextToSpeech.LANG_AVAILABLE,
            hebrewVoices = infos,
            activeVoice = runCatching { t.voice?.name }.getOrNull(),
            error = when (langResult) {
                TextToSpeech.LANG_MISSING_DATA -> "חסרים נתוני קול לעברית — יש להוריד אותם בהגדרות הדיבור."
                TextToSpeech.LANG_NOT_SUPPORTED -> "מנוע הדיבור הנוכחי לא תומך בעברית."
                else -> null
            },
        )
    }

    /** Speaks [text] and suspends until it finished (or failed). Returns false if nothing was spoken. */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return true
        if (withTimeoutOrNull(4000) { ready.await() } != true) return false
        val t = tts ?: return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        val focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        try {
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                pending.remove(id)
                return false
            }
            // Generous upper bound: ~12 chars/sec of speech plus engine start-up.
            return withTimeoutOrNull(4000L + text.length * 150L) { done.await() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "speak failed", e)
            return false
        } finally {
            pending.remove(id)
            if (focused) audio.abandonAudioFocusRequest(focus)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object { private const val TAG = "JarvisTts" }
}
