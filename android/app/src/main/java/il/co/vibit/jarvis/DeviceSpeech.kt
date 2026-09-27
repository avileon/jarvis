package il.co.vibit.jarvis

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.WebView
import org.json.JSONObject

/**
 * Hebrew speech-to-text using the tablet's built-in Google recognizer (free — no server STT cost).
 * Results are pushed to the page via window.jarvisSpeech({type, ...}).
 * Must be used on the main thread.
 */
class DeviceSpeech(private val activity: MainActivity, private val web: () -> WebView) {
    private var recognizer: SpeechRecognizer? = null
    private var lastLevelAt = 0L

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    fun listen(language: String) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = emit(JSONObject().put("type", "ready"))
            override fun onBeginningOfSpeech() = emit(JSONObject().put("type", "start"))
            override fun onRmsChanged(rmsdB: Float) {
                val now = System.currentTimeMillis()
                if (now - lastLevelAt < 80) return
                lastLevelAt = now
                // rmsdB roughly -2..10 → 0..1
                emit(JSONObject().put("type", "level").put("v", ((rmsdB + 2f) / 12f).coerceIn(0f, 1f).toDouble()))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = emit(JSONObject().put("type", "end"))
            override fun onError(error: Int) {
                emit(JSONObject().put("type", "error").put("code", error))
                destroy()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                emit(JSONObject().put("type", "result").put("text", text))
                destroy()
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                emit(JSONObject().put("type", "partial").put("text", text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        r.startListening(intent)
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        destroy()
    }

    private fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun emit(obj: JSONObject) {
        activity.runOnUiThread {
            runCatching { web().evaluateJavascript("window.jarvisSpeech&&window.jarvisSpeech($obj)", null) }
        }
    }
}
