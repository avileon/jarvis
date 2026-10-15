package il.co.vibit.jarvis.assistant.telephony

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log

/**
 * Places the call. The assistant only dials — the user talks to the other person themselves.
 *
 * 1. [TelecomManager.placeCall] with CALL_PHONE: works from the background and over the lock screen,
 *    and routes through the car when Android Auto / Bluetooth hands-free is connected.
 * 2. Fallback: ACTION_DIAL opens the dialer with the number filled in (user presses call).
 */
class CallPlacer(private val context: Context) {
    enum class Result { PLACED, DIALER_OPENED, FAILED }

    fun hasCallPermission() =
        context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun place(number: String, direct: Boolean): Result {
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (clean.isEmpty()) return Result.FAILED
        val uri = Uri.fromParts("tel", clean, null)
        if (direct && hasCallPermission()) {
            try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                telecom.placeCall(uri, null)
                return Result.PLACED
            } catch (e: SecurityException) {
                Log.w(TAG, "placeCall denied, falling back to dialer", e)
            } catch (e: Exception) {
                Log.w(TAG, "placeCall failed, falling back to dialer", e)
            }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.DIALER_OPENED
        } catch (e: ActivityNotFoundException) {
            Result.FAILED
        } catch (e: Exception) {
            // Background activity start can be blocked when no JARVIS UI is visible.
            Log.w(TAG, "dialer start failed", e)
            Result.FAILED
        }
    }

    companion object { private const val TAG = "CallPlacer" }
}
