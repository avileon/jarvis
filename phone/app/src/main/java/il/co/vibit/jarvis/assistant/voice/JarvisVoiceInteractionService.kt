package il.co.vibit.jarvis.assistant.voice

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import il.co.vibit.jarvis.assistant.JarvisApp
import il.co.vibit.jarvis.assistant.service.ListeningService
import il.co.vibit.jarvis.assistant.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bound by the system while JARVIS is the "Default digital assistant app".
 * - Long-press side key / swipe from a bottom corner / assist gesture → [JarvisSession] (via the session service).
 * - Keyguard voice affordance → [onLaunchVoiceAssistFromKeyguard].
 *
 * Note: the official always-on hotword APIs (AlwaysOnHotwordDetector with enrolled keyphrases,
 * HotwordDetectionService / createHotwordDetector) are @SystemApi or need privileged permissions
 * (MANAGE_HOTWORD_DETECTION, MANAGE_VOICE_KEYPHRASES), so a sideloaded app cannot use them. Being the active
 * VoiceInteractionService does, however, exempt our microphone foreground service from the
 * background-start restriction, which is what [onReady] uses to resume wake word listening.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
        _active.value = true
        val app = application as JarvisApp
        if (app.settings.current.wakeWordEnabled) {
            val ok = ListeningService.start(this)
            Log.i(TAG, "onReady: resume wake word listening -> $ok")
        }
    }

    override fun onShutdown() {
        instance = null
        _active.value = false
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        // Must start an activity that shows over the keyguard.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_START_LISTENING, true)
                .putExtra(MainActivity.EXTRA_FROM_KEYGUARD, true)
        )
    }

    /** Shows the overlay for a conversation that the wake word already started. */
    fun showFromWakeWord() {
        runCatching { showSession(Bundle().apply { putString(JarvisSession.ARG_SOURCE, JarvisSession.SOURCE_WAKE) }, 0) }
            .onFailure { Log.w(TAG, "showSession failed", it) }
    }

    companion object {
        private const val TAG = "JarvisVIS"
        @Volatile var instance: JarvisVoiceInteractionService? = null
            private set
        private val _active = MutableStateFlow(false)
        /** True while the system has JARVIS bound as the digital assistant. */
        val active: StateFlow<Boolean> = _active.asStateFlow()
    }
}
