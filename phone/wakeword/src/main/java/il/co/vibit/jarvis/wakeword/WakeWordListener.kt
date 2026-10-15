package il.co.vibit.jarvis.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * Reads the microphone on a background thread and runs [OpenWakeWord] on it.
 * Audio stays in memory (a few hundred ms ring buffer) and is never written to disk or sent anywhere.
 *
 * Must only be started from a foreground service of type "microphone" (or while an activity is visible).
 */
class WakeWordListener(
    private val context: Context,
    private val modelSource: ModelSource,
    private val onWake: (score: Float) -> Unit,
    private val onScore: (score: Float, level: Float) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
) {
    /** Where the wake word classifier comes from. Feature models are always the bundled openWakeWord ones. */
    sealed interface ModelSource {
        /** Bundled `hey_jarvis_v0.1.onnx` — trained on English speech (see README: not validated for Hebrew). */
        data object BundledHeyJarvis : ModelSource
        /** A model the user trained for the Hebrew pronunciation and imported into app storage. */
        data class CustomFile(val file: File) : ModelSource
    }

    @Volatile var threshold = 0.5f
        set(v) { field = v; detector?.threshold = v }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var detector: OpenWakeWord? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "jarvis-wakeword").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(1500) }
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var record: AudioRecord? = null
        try {
            val det = detector ?: createDetector().also { detector = it }
            det.threshold = threshold
            det.reset()
            val minBuf = AudioRecord.getMinBufferSize(OpenWakeWord.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                OpenWakeWord.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, OpenWakeWord.CHUNK * 2 * 4),
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            record.startRecording()
            val chunk = ShortArray(OpenWakeWord.CHUNK)
            while (running) {
                var off = 0
                while (off < chunk.size && running) {
                    val n = record.read(chunk, off, chunk.size - off)
                    if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                    off += n
                }
                if (!running) break
                var sum = 0.0
                for (s in chunk) sum += s * s.toDouble()
                val level = (Math.sqrt(sum / chunk.size) / 6000.0).toFloat().coerceIn(0f, 1f)
                val score = det.process(chunk) { s -> onWake(s) }
                onScore(score ?: 0f, level)
            }
        } catch (t: Throwable) {
            if (running) {
                Log.e(TAG, "wake word loop failed", t)
                onError(t)
            }
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            running = false
        }
    }

    private fun createDetector(): OpenWakeWord {
        val assets = context.assets
        fun asset(name: String) = assets.open("wakeword/$name").use { it.readBytes() }
        val ww = when (val m = modelSource) {
            ModelSource.BundledHeyJarvis -> asset("hey_jarvis_v0.1.onnx")
            is ModelSource.CustomFile -> m.file.readBytes()
        }
        return OpenWakeWord(asset("melspectrogram.onnx"), asset("embedding_model.onnx"), ww, threshold)
    }

    fun release() {
        stop()
        detector?.close()
        detector = null
    }

    companion object { private const val TAG = "WakeWord" }
}
