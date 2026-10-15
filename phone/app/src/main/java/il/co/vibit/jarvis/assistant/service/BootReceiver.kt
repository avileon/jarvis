package il.co.vibit.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import il.co.vibit.jarvis.assistant.JarvisApp

/**
 * After a reboot or an app update, try to resume wake word listening.
 * Android 15+ forbids starting a microphone foreground service from BOOT_COMPLETED, so this usually ends in the
 * "tap to resume" notification — unless JARVIS is the default assistant, in which case the system binds
 * JarvisVoiceInteractionService and its onReady() resumes listening.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as JarvisApp
        if (!app.settings.current.wakeWordEnabled) return
        ListeningService.postResumeNotification(context)
    }
}
