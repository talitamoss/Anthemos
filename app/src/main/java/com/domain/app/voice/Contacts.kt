// app/src/main/java/com/domain/app/voice/Contacts.kt
package com.domain.app.voice

import android.content.Context
import android.provider.ContactsContract

/** Finds who to call by the name said. Needs READ_CONTACTS. */
class Contacts(private val context: Context) {

    data class Match(val name: String, val number: String)

    /**
     * The best match for [spoken]: an exact display name first, then one
     * starting with it ("Sam" -> "Sam Smith"), then one containing it.
     */
    fun find(spoken: String): Match? {
        val wanted = spoken.trim().lowercase()
        if (wanted.isEmpty()) return null
        val all = mutableListOf<Match>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                all += Match(name, number)
            }
        }
        return all.firstOrNull { it.name.lowercase() == wanted }
            ?: all.firstOrNull { it.name.lowercase().startsWith("$wanted ") }
            ?: all.firstOrNull { it.name.lowercase().contains(wanted) }
    }
}
