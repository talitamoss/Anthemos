// app/src/main/java/com/domain/app/voice/Contacts.kt
package com.domain.app.voice

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.Data

/**
 * Finds who to call by the name said. Needs READ_CONTACTS.
 *
 * "Mum" is rarely a contact's name, so it looks, in order, at: names taught
 * by voice ("Mum is Jane Moss"), contact names, nicknames, and relations
 * set on a contact card ("mother: Jane Moss"), then names one letter off,
 * for mishearing.
 */
class Contacts(private val context: Context) {

    data class Match(val name: String, val number: String)

    private val taught = context.getSharedPreferences("anthemos_names", Context.MODE_PRIVATE)

    /** The contact name taught for [word] ("dad"), if any. */
    fun alias(word: String): String? = taught.getString(word.trim().lowercase(), null)

    fun teach(word: String, contactName: String) {
        taught.edit().putString(word.trim().lowercase(), contactName).apply()
    }

    fun find(spoken: String): Match? {
        val wanted = spoken.trim().lowercase().removePrefix("my ").trim()
        if (wanted.isEmpty()) return null
        val all = phones()

        alias(wanted)?.let { name -> byName(all, name.lowercase())?.let { return it } }
        byName(all, wanted)?.let { return it }
        nickname(wanted)?.let { id -> all.firstOrNull { it.contactId == id }?.let { return it.match } }
        relation(wanted)?.let { name -> byName(all, name.lowercase())?.let { return it } }
        // One letter off ("dan" for "dad" won't do, but "jayne" for "jane" will).
        if (wanted.length >= 4) {
            all.firstOrNull { p -> p.name.lowercase().split(" ").any { distance(it, wanted) <= 1 } }?.let { return it.match }
        }
        return null
    }

    private data class Entry(val contactId: Long, val name: String, val number: String) {
        val match get() = Match(name, number)
    }

    private fun byName(all: List<Entry>, wanted: String): Match? =
        (all.firstOrNull { it.name.lowercase() == wanted }
            ?: all.firstOrNull { it.name.lowercase().startsWith("$wanted ") }
            ?: all.firstOrNull { it.name.lowercase().contains(wanted) })?.match

    private fun phones(): List<Entry> {
        val all = mutableListOf<Entry>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER),
            null, null,
            "${Phone.IS_SUPER_PRIMARY} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                all += Entry(c.getLong(0), c.getString(1) ?: continue, c.getString(2) ?: continue)
            }
        }
        return all
    }

    private fun nickname(wanted: String): Long? = context.contentResolver.query(
        Data.CONTENT_URI,
        arrayOf(Data.CONTACT_ID),
        "${Data.MIMETYPE} = ? AND LOWER(${Nickname.NAME}) = ?",
        arrayOf(Nickname.CONTENT_ITEM_TYPE, wanted),
        null
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** A relation on any card, e.g. your own card says "mother: Jane Moss". */
    private fun relation(wanted: String): String? {
        val type = RELATION_TYPES[wanted] ?: return null
        return context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Relation.NAME),
            "${Data.MIMETYPE} = ? AND ${Relation.TYPE} = ?",
            arrayOf(Relation.CONTENT_ITEM_TYPE, type.toString()),
            null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    companion object {
        private val RELATION_TYPES = mapOf(
            "mum" to Relation.TYPE_MOTHER, "mom" to Relation.TYPE_MOTHER, "mother" to Relation.TYPE_MOTHER,
            "mam" to Relation.TYPE_MOTHER, "mama" to Relation.TYPE_MOTHER,
            "dad" to Relation.TYPE_FATHER, "father" to Relation.TYPE_FATHER, "papa" to Relation.TYPE_FATHER,
            "brother" to Relation.TYPE_BROTHER, "sister" to Relation.TYPE_SISTER,
            "partner" to Relation.TYPE_PARTNER, "wife" to Relation.TYPE_SPOUSE, "husband" to Relation.TYPE_SPOUSE,
            "boss" to Relation.TYPE_MANAGER
        )
    }
}
