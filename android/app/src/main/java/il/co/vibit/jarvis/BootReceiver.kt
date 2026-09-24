package il.co.vibit.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the station after boot / app update. On Android 10+ this needs "display over other apps",
 *  or set JARVIS as the Home app (launcher), which always starts on boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }
}
