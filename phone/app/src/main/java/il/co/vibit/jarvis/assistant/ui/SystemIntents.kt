package il.co.vibit.jarvis.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech

/** Deep links into system settings, each with fallbacks (Samsung One UI moves some pages). */
object SystemIntents {
    private fun Context.tryStart(vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
            } catch (_: Exception) {}
        }
        return false
    }

    /** "Default digital assistant app" page (AOSP: Voice input settings; Samsung: Apps → Default apps). */
    fun openAssistantSettings(c: Context) = c.tryStart(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    @SuppressLint("BatteryLife") // personal sideloaded assistant: wake word must survive Samsung's battery manager
    fun requestUnrestrictedBattery(c: Context) = c.tryStart(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun openAppDetails(c: Context) = c.tryStart(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")),
    )

    fun openTtsSettings(c: Context) = c.tryStart(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun installTtsData(c: Context, engine: String?) = c.tryStart(
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (engine != null) setPackage(engine) },
        Intent("com.android.settings.TTS_SETTINGS"),
    )

    /** Google's offline speech packs page lives inside the Google app / Speech Services; this is the closest public entry. */
    fun openVoiceInputSettings(c: Context) = c.tryStart(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
    )
}
