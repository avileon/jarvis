package il.co.vibit.jarvis.assistant.voice

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import il.co.vibit.jarvis.assistant.voice.stt.SpeechInput

/**
 * The platform requires every digital assistant to declare a RecognitionService, and selecting JARVIS as the
 * assistant also makes this the device's default "voice input" service. So it must keep working for other apps:
 * it simply forwards to the best real recogniser on the phone (Google / Samsung), never to itself.
 */
class JarvisRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val target = SpeechInput(this).delegate()
        if (target == null) { safe { listener.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        delegate?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this, target)
        delegate = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { listener.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { listener.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { listener.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { buffer?.let { listener.bufferReceived(it) } }
            override fun onEndOfSpeech() = safe { listener.endOfSpeech() }
            override fun onError(error: Int) = safe { listener.error(error) }
            override fun onResults(results: Bundle?) = safe { listener.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { listener.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) { delegate?.stopListening() }

    override fun onCancel(listener: Callback) { delegate?.cancel() }

    override fun onDestroy() {
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: RemoteException) { Log.w("JarvisRecognition", "client gone", e) }
    }
}
