package il.co.vibit.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Native radio playback (USAGE_MEDIA) so music follows the system media route — including
 * Bluetooth speakers — and the hardware/system media volume. Survives WebView reloads.
 */
class RadioPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var url: String? = null
    private var ducked = false

    fun play(streamUrl: String) {
        url = streamUrl
        start()
    }

    private fun start() {
        val u = url ?: return
        release()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(u)
            mp.setOnPreparedListener { applyVolume(); it.start() }
            mp.setOnErrorListener { _, _, _ -> retry(); true }
            mp.setOnCompletionListener { retry() }
            mp.prepareAsync()
        } catch (e: Exception) {
            retry()
        }
    }

    private fun retry() {
        if (url == null) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (url != null) start() }, 5000)
    }

    fun stop() {
        url = null
        handler.removeCallbacksAndMessages(null)
        release()
    }

    fun duck(on: Boolean) {
        ducked = on
        applyVolume()
    }

    private fun applyVolume() {
        val v = if (ducked) 0.2f else 1f
        runCatching { player?.setVolume(v, v) }
    }

    /** Steps the system media volume (this is what a Bluetooth speaker follows). Returns percent. */
    fun volumeStep(delta: Int): Int {
        val dir = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(delta)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        return volumePercent()
    }

    fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun release() {
        runCatching { player?.reset(); player?.release() }
        player = null
    }
}
