package il.co.vibit.jarvis.assistant.voice.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface SttEvent {
    data object Ready : SttEvent
    data object Begin : SttEvent
    data class Level(val value: Float) : SttEvent
    data class Partial(val text: String) : SttEvent
    data object End : SttEvent
}

sealed interface SttResult {
    data class Text(val text: String, val backend: Backend) : SttResult
    /** Nothing (intelligible) was said. */
    data object NoSpeech : SttResult
    data class Failed(val code: Int, val spokenMessage: String) : SttResult
}

enum class Backend { ON_DEVICE, SYSTEM_SERVICE }

/** What the device can do for Hebrew speech recognition. Shown on the settings screen. */
data class SttSupport(
    val onDeviceAvailable: Boolean,
    /** null = could not be determined (API < 33 or the service did not answer). */
    val hebrewInstalledOnDevice: Boolean?,
    val hebrewDownloadable: Boolean?,
    val hebrewPending: Boolean?,
    val hebrewOnline: Boolean?,
    val delegate: ComponentName?,
)

/**
 * Hebrew speech-to-text via Android's SpeechRecognizer.
 *
 * Order of preference:
 *  1. createOnDeviceSpeechRecognizer — guaranteed offline, if the device has an on-device service with Hebrew.
 *  2. A specific system RecognitionService (Google's, Samsung's…) chosen explicitly. We never use the
 *     *default* recogniser because, once JARVIS is the digital assistant, the default is JARVIS itself.
 *
 * All methods must be called on the main thread.
 */
class SpeechInput(private val context: Context) {

    var biasingStrings: List<String> = emptyList()

    fun onDeviceAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Recognition services installed on the phone, excluding JARVIS' own forwarding service. */
    fun recognitionServices(): List<ComponentName> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
    }

    /** Best non-JARVIS recogniser: Google first (best Hebrew), then whatever else exists (e.g. Samsung). */
    fun delegate(): ComponentName? {
        val all = recognitionServices()
        val order = listOf("com.google.android.tts", "com.google.android.as", "com.google.android.googlequicksearchbox")
        for (pkg in order) all.firstOrNull { it.packageName == pkg }?.let { return it }
        return all.firstOrNull()
    }

    private fun recognizerIntent(language: String, preferOffline: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            if (Build.VERSION.SDK_INT >= 33 && biasingStrings.isNotEmpty()) {
                // Hints contact names to recognisers that support biasing (improves Hebrew names).
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasingStrings.take(200)))
            }
        }

    /**
     * Listens for one utterance. Tries on-device first (if preferred and available), and falls back to the
     * system service when the on-device engine reports that Hebrew is missing — only if [allowOnline].
     */
    suspend fun listen(
        language: String,
        preferOnDevice: Boolean,
        allowOnline: Boolean,
        onEvent: (SttEvent) -> Unit,
    ): SttResult {
        if (preferOnDevice && onDeviceAvailable()) {
            val r = listenWith(Backend.ON_DEVICE, null, language, true, onEvent)
            val languageMissing = r is SttResult.Failed && r.code in LANGUAGE_ERRORS
            if (!languageMissing) return r
            Log.i(TAG, "on-device recogniser has no $language model, falling back")
        }
        val delegate = delegate() ?: return SttResult.Failed(-1, "לא נמצא מנוע זיהוי דיבור במכשיר.")
        // With EXTRA_PREFER_OFFLINE the service uses its offline language pack when one is installed;
        // whether it then still goes online is up to that service, which is why the UI shows the backend used.
        return listenWith(Backend.SYSTEM_SERVICE, delegate, language, !allowOnline || preferOnDevice, onEvent)
    }

    private suspend fun listenWith(
        backend: Backend,
        component: ComponentName?,
        language: String,
        preferOffline: Boolean,
        onEvent: (SttEvent) -> Unit,
    ): SttResult {
        val result = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                val recognizer = try {
                    if (backend == Backend.ON_DEVICE) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    else SpeechRecognizer.createSpeechRecognizer(context, component)
                } catch (e: Exception) {
                    cont.resume(SttResult.Failed(-2, "מנוע זיהוי הדיבור לא זמין."))
                    return@suspendCancellableCoroutine
                }
                var lastPartial = ""
                fun finish(r: SttResult) {
                    if (cont.isActive) cont.resume(r)
                    Handler(Looper.getMainLooper()).post { runCatching { recognizer.destroy() } }
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = onEvent(SttEvent.Ready)
                    override fun onBeginningOfSpeech() = onEvent(SttEvent.Begin)
                    override fun onRmsChanged(rmsdB: Float) = onEvent(SttEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = onEvent(SttEvent.End)
                    override fun onPartialResults(partialResults: Bundle?) {
                        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!t.isNullOrBlank()) { lastPartial = t; onEvent(SttEvent.Partial(t)) }
                    }
                    override fun onResults(results: Bundle?) {
                        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        finish(if (t.isNullOrBlank()) SttResult.NoSpeech else SttResult.Text(t, backend))
                    }
                    override fun onError(error: Int) {
                        Log.w(TAG, "recogniser error $error ($backend)")
                        finish(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    if (lastPartial.isNotBlank()) SttResult.Text(lastPartial, backend) else SttResult.NoSpeech
                                else -> SttResult.Failed(error, messageFor(error))
                            }
                        )
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { recognizer.cancel() }
                        runCatching { recognizer.destroy() }
                    }
                }
                recognizer.startListening(recognizerIntent(language, preferOffline))
            }
        }
        return result ?: SttResult.NoSpeech
    }

    /** Asks the on-device service whether Hebrew is installed / downloadable (Android 13+). */
    suspend fun checkSupport(language: String): SttSupport {
        val onDevice = onDeviceAvailable()
        var installed: Boolean? = null
        var downloadable: Boolean? = null
        var pending: Boolean? = null
        var online: Boolean? = null
        if (onDevice && Build.VERSION.SDK_INT >= 33) {
            val answer = CompletableDeferred<RecognitionSupport?>()
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                recognizer.checkRecognitionSupport(
                    recognizerIntent(language, true), context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) { answer.complete(recognitionSupport) }
                        override fun onError(error: Int) { answer.complete(null) }
                    },
                )
                withTimeoutOrNull(5000) { answer.await() }?.let { s ->
                    fun List<String>.has() = any { it.equals(language, true) || it.startsWith("he", true) || it.startsWith("iw", true) }
                    installed = s.installedOnDeviceLanguages.has()
                    pending = s.pendingOnDeviceLanguages.has()
                    downloadable = s.supportedOnDeviceLanguages.has()
                    online = s.onlineLanguages.has()
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkRecognitionSupport failed", e)
            } finally {
                recognizer.destroy()
            }
        }
        return SttSupport(onDevice, installed, downloadable, pending, online, delegate())
    }

    /** Requests the on-device Hebrew model download (Android 13+). The system shows its own UI/notification. */
    fun downloadHebrewModel(language: String): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !onDeviceAvailable()) return false
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try {
            recognizer.triggerModelDownload(recognizerIntent(language, true))
            true
        } catch (e: Exception) {
            Log.w(TAG, "triggerModelDownload failed", e)
            false
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { recognizer.destroy() } }, 3000)
        }
    }

    companion object {
        private const val TAG = "SpeechInput"
        private val LANGUAGE_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
        )

        fun messageFor(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "אין לי הרשאה למיקרופון."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "זיהוי הדיבור דורש אינטרנט כרגע. כדאי להוריד את חבילת העברית לזיהוי לא מקוון."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "זיהוי דיבור בעברית לא זמין במכשיר."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "המיקרופון תפוס כרגע."
            SpeechRecognizer.ERROR_AUDIO -> "יש בעיה בקליטת השמע."
            else -> "זיהוי הדיבור נכשל."
        }
    }
}
