package il.co.vibit.jarvis.assistant.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import il.co.vibit.jarvis.core.contacts.Contact
import il.co.vibit.jarvis.core.contacts.PhoneNumber
import il.co.vibit.jarvis.core.dialog.ContactSource
import il.co.vibit.jarvis.core.nlu.PhoneType

/**
 * Reads the address book through ContactsContract. Read-only, in memory, never leaves the device
 * (the app has no INTERNET permission). Cached briefly so a clarification dialog sees a stable list.
 */
class AndroidContactSource(private val context: Context) : ContactSource {
    private var cache: List<Contact>? = null
    private var cachedAt = 0L

    override fun load(): List<Contact>? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        cache?.let { if (now - cachedAt < CACHE_MS) return it }
        val loaded = query()
        cache = loaded
        cachedAt = now
        return loaded
    }

    fun invalidate() { cache = null }

    private fun query(): List<Contact> {
        val names = HashMap<Long, String>()
        val phones = HashMap<Long, MutableList<PhoneNumber>>()
        val aliases = HashMap<Long, MutableList<String>>()
        val cr = context.contentResolver

        cr.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY),
            null, null, null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val iName = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY)
            val iNum = c.getColumnIndexOrThrow(Phone.NUMBER)
            val iType = c.getColumnIndexOrThrow(Phone.TYPE)
            val iLabel = c.getColumnIndexOrThrow(Phone.LABEL)
            val iPrimary = c.getColumnIndexOrThrow(Phone.IS_SUPER_PRIMARY)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val number = c.getString(iNum)?.takeIf { it.isNotBlank() } ?: continue
                c.getString(iName)?.let { names[id] = it }
                val type = when (c.getInt(iType)) {
                    Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> PhoneType.MOBILE
                    Phone.TYPE_HOME -> PhoneType.HOME
                    Phone.TYPE_WORK, Phone.TYPE_COMPANY_MAIN -> PhoneType.WORK
                    else -> PhoneType.OTHER
                }
                phones.getOrPut(id) { mutableListOf() } +=
                    PhoneNumber(number, type, c.getString(iLabel), c.getInt(iPrimary) != 0)
            }
        }

        cr.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Nickname.NAME),
            "${Data.MIMETYPE} = ?", arrayOf(Nickname.CONTENT_ITEM_TYPE), null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Data.CONTACT_ID)
            val iNick = c.getColumnIndexOrThrow(Nickname.NAME)
            while (c.moveToNext()) {
                val nick = c.getString(iNick)?.takeIf { it.isNotBlank() } ?: continue
                aliases.getOrPut(c.getLong(iId)) { mutableListOf() } += nick
            }
        }

        return phones.map { (id, list) ->
            Contact(id, names[id] ?: list.first().number, list, aliases[id].orEmpty())
        }
    }

    companion object { private const val CACHE_MS = 60_000L }
}
