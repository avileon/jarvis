package il.co.vibit.jarvis.assistant.debug

import android.content.BroadcastReceiver
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.util.Log
import il.co.vibit.jarvis.assistant.JarvisApp
import il.co.vibit.jarvis.assistant.service.ListeningService
import il.co.vibit.jarvis.assistant.voice.JarvisVoiceInteractionService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** adb-only test hooks (debug build). See phone/tools/emulator-smoke.sh. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JarvisApp
        when (intent.action?.substringAfterLast('.')) {
            "SEED_CONTACTS" -> {
                seed(context)
                app.contacts.invalidate()
                Log.i(TAG, "seeded contacts: ${app.contacts.load()?.map { it.displayName to it.phones.map { p -> p.number } }}")
            }
            "COMMAND" -> {
                val text = intent.getStringExtra("text").orEmpty()
                Log.i(TAG, "command: $text")
                app.engine.submitText(text)
            }
            "START_LISTENING" -> {
                app.settings.update { it.copy(wakeWordEnabled = true) }
                val ok = ListeningService.start(context)
                Log.i(TAG, "start listening from background broadcast -> startForegroundService ok=$ok vis=${JarvisVoiceInteractionService.active.value}")
            }
            "STATUS" -> {
                val pending = goAsync()
                MainScope().launch {
                    try {
                        Log.i(TAG, "tts=${app.tts.status.value}")
                        Log.i(TAG, "stt=${app.stt.checkSupport("he-IL")}")
                        Log.i(TAG, "recognizers=${app.stt.recognitionServices()}")
                        Log.i(TAG, "vis.active=${JarvisVoiceInteractionService.active.value} listening=${ListeningService.running.value} detecting=${ListeningService.detecting.value}")
                    } finally { pending.finish() }
                }
            }
        }
    }

    private fun seed(context: Context) {
        val people = listOf(
            "שלי כהן" to listOf("0501111111" to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE),
            "בר לוי" to listOf("0522222222" to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE),
            "גל אברהם" to listOf(
                "0543333333" to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                "036333333" to ContactsContract.CommonDataKinds.Phone.TYPE_HOME,
            ),
            "גל מזרחי" to listOf("0544444444" to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE),
        )
        val existing = (context.applicationContext as JarvisApp).contacts.load().orEmpty().map { it.displayName }.toSet()
        val ops = ArrayList<ContentProviderOperation>()
        for ((name, numbers) in people) {
            if (name in existing) continue
            val back = ops.size
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build()
            for ((num, type) in numbers) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, type).build()
            }
        }
        if (ops.isNotEmpty()) context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    companion object { private const val TAG = "JarvisDebug" }
}
