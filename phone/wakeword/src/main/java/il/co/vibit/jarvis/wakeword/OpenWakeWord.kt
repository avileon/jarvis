package il.co.vibit.jarvis.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * openWakeWord streaming pipeline, on-device (ONNX Runtime, CPU). Port of web/src/station/wakeword.ts,
 * which mirrors openwakeword/utils.py AudioFeatures._streaming_features:
 *
 *   16 kHz int16 audio in 1280-sample chunks (80 ms)
 *   → melspectrogram.onnx  (chunk + 480 samples of context → mel frames × 32), spec = spec / 10 + 2
 *   → embedding_model.onnx (last 76 mel frames → 96-d embedding)
 *   → <wake word>.onnx     (last 16 embeddings → score 0..1)
 *
 * Not thread-safe: call [process] from a single audio thread.
 */
class OpenWakeWord(
    melModel: ByteArray,
    embeddingModel: ByteArray,
    wakeWordModel: ByteArray,
    @Volatile var threshold: Float = 0.5f,
    /** Consecutive chunks above threshold required to fire (reduces false wakes). */
    @Volatile var patience: Int = 2,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val mel = env.createSession(melModel, opts)
    private val emb = env.createSession(embeddingModel, opts)
    private val ww = env.createSession(wakeWordModel, opts)

    private val raw = FloatArray(CHUNK + MEL_CONTEXT)
    private var rawFilled = 0
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()
    private var hits = 0
    private var cooldownChunks = 0

    init { reset() }

    fun reset() {
        rawFilled = 0
        melFrames.clear()
        embeddings.clear()
        // Prime with "silence-like" frames so detection works from the first second (same as openWakeWord).
        repeat(EMB_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        hits = 0
        cooldownChunks = COOLDOWN_AFTER_RESET
    }

    /**
     * Feed exactly [CHUNK] samples of 16 kHz mono PCM16. Returns the latest score, or null while buffers fill.
     * [onWake] is invoked (on the calling thread) once per detection.
     */
    fun process(chunk: ShortArray, onWake: (Float) -> Unit): Float? {
        require(chunk.size == CHUNK) { "chunk must be $CHUNK samples" }
        // Slide the raw window: keep the last 480 samples of context + the new chunk (int16-scaled floats).
        if (rawFilled + CHUNK > raw.size) {
            System.arraycopy(raw, rawFilled - MEL_CONTEXT, raw, 0, MEL_CONTEXT)
            rawFilled = MEL_CONTEXT
        }
        for (i in 0 until CHUNK) raw[rawFilled + i] = chunk[i].toFloat()
        rawFilled += CHUNK
        if (rawFilled < raw.size) return null

        // 1) mel spectrogram
        val melOut = run(mel, mel.inputNames.first(), raw, longArrayOf(1, raw.size.toLong()))
        val frames = melOut.size / MEL_BINS
        for (f in 0 until frames) {
            melFrames.addLast(FloatArray(MEL_BINS) { b -> melOut[f * MEL_BINS + b] / 10f + 2f })
        }
        while (melFrames.size > EMB_WINDOW + 20) melFrames.removeFirst()

        // 2) embedding of the last 76 frames
        val win = FloatArray(EMB_WINDOW * MEL_BINS)
        val start = melFrames.size - EMB_WINDOW
        for (f in 0 until EMB_WINDOW) System.arraycopy(melFrames[start + f], 0, win, f * MEL_BINS, MEL_BINS)
        val e = run(emb, emb.inputNames.first(), win, longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1))
        embeddings.addLast(e.copyOf(EMB_DIM))
        while (embeddings.size > MODEL_FRAMES) embeddings.removeFirst()
        if (embeddings.size < MODEL_FRAMES) return null

        // 3) wake word classifier
        val feats = FloatArray(MODEL_FRAMES * EMB_DIM)
        embeddings.forEachIndexed { i, v -> System.arraycopy(v, 0, feats, i * EMB_DIM, EMB_DIM) }
        val score = run(ww, ww.inputNames.first(), feats, longArrayOf(1, MODEL_FRAMES.toLong(), EMB_DIM.toLong()))[0]

        if (cooldownChunks > 0) { cooldownChunks--; return score }
        hits = if (score >= threshold) hits + 1 else 0
        if (hits >= patience) {
            hits = 0
            cooldownChunks = COOLDOWN_AFTER_WAKE
            onWake(score)
        }
        return score
    }

    private fun run(session: OrtSession, input: String, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val buf = out.floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
    }

    override fun close() {
        runCatching { ww.close() }
        runCatching { emb.close() }
        runCatching { mel.close() }
        runCatching { opts.close() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        private const val MEL_CONTEXT = 160 * 3
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76
        private const val EMB_DIM = 96
        private const val MODEL_FRAMES = 16
        private const val COOLDOWN_AFTER_RESET = 12 // ~1 s
        private const val COOLDOWN_AFTER_WAKE = 25  // ~2 s
    }
}
