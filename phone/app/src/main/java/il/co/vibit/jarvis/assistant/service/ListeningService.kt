package il.co.vibit.jarvis.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import il.co.vibit.jarvis.assistant.JarvisApp
import il.co.vibit.jarvis.assistant.R
import il.co.vibit.jarvis.assistant.engine.Phase
import il.co.vibit.jarvis.assistant.engine.Source
import il.co.vibit.jarvis.assistant.ui.MainActivity
import il.co.vibit.jarvis.assistant.voice.JarvisVoiceInteractionService
import il.co.vibit.jarvis.wakeword.WakeWordListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service of type "microphone" that runs the on-device wake word detector.
 *
 * Android rules this follows (Android 14–16):
 *  - It can only be *started* while JARVIS is visible, from a notification tap, or by the app that is the
 *    current VoiceInteractionService (exemptions listed in the FGS background-start docs). Otherwise
 *    startForeground() throws and we fall back to a "tap to resume" notification.
 *  - It shows a permanent notification; the system also shows the green microphone privacy indicator.
 *  - The microphone is released while a conversation runs (the speech recogniser needs it) and while
 *    another app records/calls.
 */
class ListeningService : LifecycleService() {
    private var listener: WakeWordListener? = null
    private var listenerModel: WakeWordListener.ModelSource? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as JarvisApp
        if (intent?.action == ACTION_STOP) {
            app.settings.update { it.copy(wakeWordEnabled = false) }
            stopEverything()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) app.settings.update { it.copy(wakeWordEnabled = true) }
        // startForeground() must be called promptly after startForegroundService(), even if we then stop.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, listeningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException (started from background,
            // or RECORD_AUDIO missing).
            Log.w(TAG, "cannot start microphone FGS from here", e)
            if (app.settings.current.wakeWordEnabled) postResumeNotification(this)
            stopEverything()
            return START_NOT_STICKY
        }
        if (!app.settings.current.wakeWordEnabled ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopEverything(); return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(RESUME_NOTIFICATION_ID)
        _running.value = true
        if (!observing) observe(app)
        return START_STICKY
    }

    private var observing = false

    private fun observe(app: JarvisApp) {
        observing = true
        lifecycleScope.launch {
            combine(app.engine.state, app.settings.state) { st, s -> Triple(st.phase == Phase.IDLE, s, s.wakeThreshold) }
                .distinctUntilChanged()
                .collect { (idle, s, threshold) ->
                    if (!s.wakeWordEnabled) { stopEverything(); return@collect }
                    val model = if (s.useCustomWakeModel && app.settings.customWakeModel.exists())
                        WakeWordListener.ModelSource.CustomFile(app.settings.customWakeModel)
                    else WakeWordListener.ModelSource.BundledHeyJarvis
                    if (model != listenerModel) { listener?.release(); listener = null }
                    val l = listener ?: createListener(model).also { listener = it; listenerModel = model }
                    l.threshold = threshold
                    if (idle) l.start() else l.stop()
                    _detecting.value = idle
                }
        }
    }

    private fun createListener(model: WakeWordListener.ModelSource) = WakeWordListener(
        context = this,
        modelSource = model,
        onWake = { score -> lifecycleScope.launch { onWake(score) } },
        onScore = { score, level -> _score.value = score; _level.value = level },
        onError = { t -> lifecycleScope.launch { Log.e(TAG, "listener error", t); _detecting.value = false } },
    )

    private fun onWake(score: Float) {
        Log.i(TAG, "wake word detected, score=$score")
        val app = application as JarvisApp
        if (app.engine.isBusy) return
        listener?.stop() // free the microphone for the recogniser right away
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
        app.engine.start(Source.WAKE_WORD)
        // When JARVIS is the default assistant, also show the overlay (works over the lock screen).
        JarvisVoiceInteractionService.instance?.showFromWakeWord()
    }

    private fun listeningNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("ג׳ארביס מקשיבה")
            .setContentText("המיקרופון פעיל לזיהוי \"היי ג׳ארביס\" בלבד. שום הקלטה לא נשמרת.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_mic_off, "הפסקת האזנה", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopEverything() {
        listener?.release()
        listener = null
        listenerModel = null
        _running.value = false
        _detecting.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listener?.release()
        listener = null
        _running.value = false
        _detecting.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ListeningService"
        private const val NOTIFICATION_ID = 7
        private const val RESUME_NOTIFICATION_ID = 8
        const val ACTION_STOP = "il.co.vibit.jarvis.assistant.STOP_LISTENING"
        const val ACTION_RESUME = "il.co.vibit.jarvis.assistant.RESUME_LISTENING"

        private val _running = MutableStateFlow(false)
        /** Service is in the foreground (microphone indicator on). */
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _detecting = MutableStateFlow(false)
        /** The detector is actually reading the microphone right now. */
        val detecting: StateFlow<Boolean> = _detecting.asStateFlow()
        private val _score = MutableStateFlow(0f)
        val score: StateFlow<Float> = _score.asStateFlow()
        private val _level = MutableStateFlow(0f)
        val level: StateFlow<Float> = _level.asStateFlow()

        /** Returns false when Android refused to start the service from the current context. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ListeningService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService refused", e)
            postResumeNotification(context)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningService::class.java))
        }

        /**
         * Android does not allow starting the microphone from the background in this situation. A tap on a
         * notification is an allowed trigger, so we offer one.
         */
        fun postResumeNotification(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val resume = PendingIntent.getForegroundService(
                context, 2, Intent(context, ListeningService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, JarvisApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle("האזנה ל\"היי ג׳ארביס\" מושהית")
                .setContentText("הקישו כדי להפעיל שוב את ההאזנה.")
                .setContentIntent(resume)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, n)
        }
    }
}
