// app/src/main/java/com/domain/app/backup/PhoneData.kt
package com.domain.app.backup

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.Telephony
import android.util.Base64
import com.domain.app.backup.Pack.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Reads the phone's contacts, texts and call log, and puts records back.
 * Each record is the provider's own columns, kept as they were, minus the
 * ones that only mean something on one phone (ids, threads, SIM slots).
 */
class PhoneData(private val context: Context) {
    private val cr: ContentResolver = context.contentResolver

    fun read(kind: String): List<JsonObject> = when (kind) {
        Pack.CONTACTS -> contacts()
        Pack.TEXTS -> rows(Telephony.Sms.CONTENT_URI, TEXT_COLUMNS)
        else -> rows(CallLog.Calls.CONTENT_URI, CALL_COLUMNS)
    }

    /** Adds the records this phone doesn't have. How many were added. */
    fun write(kind: String, items: List<JsonObject>): Int {
        val have = read(kind).map { Pack.key(kind, it) }.toHashSet()
        val missing = items.filter { Pack.key(kind, it) !in have }.distinctBy { Pack.key(kind, it) }
        if (missing.isEmpty()) return 0
        when (kind) {
            Pack.CONTACTS -> addContacts(missing)
            Pack.TEXTS -> missing.chunked(500).forEach { cr.bulkInsert(Telephony.Sms.CONTENT_URI, it.map(::values).toTypedArray()) }
            else -> missing.chunked(500).forEach { cr.bulkInsert(CallLog.Calls.CONTENT_URI, it.map(::values).toTypedArray()) }
        }
        return missing.size
    }

    private fun rows(uri: Uri, columns: Array<String>): List<JsonObject> =
        cr.query(uri, columns, null, null, null)?.use { c -> buildList { while (c.moveToNext()) add(row(c)) } }.orEmpty()

    /** One contact: its name and its details (numbers, emails, photo…), standard kinds only. */
    private fun contacts(): List<JsonObject> {
        val columns = arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Data.MIMETYPE) + DATA_COLUMNS
        val byContact = LinkedHashMap<Long, Pair<String, LinkedHashSet<JsonObject>>>()
        cr.query(ContactsContract.Data.CONTENT_URI, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(2) ?: continue
                if (type !in KEPT_KINDS) continue
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val entry = byContact.getOrPut(c.getLong(0)) { name to LinkedHashSet() }
                var detail = row(c, skip = 2)
                // A photo's file id only means something on this phone; the photo itself is data15.
                if (type == CommonDataKinds.Photo.CONTENT_ITEM_TYPE) detail = JsonObject(detail - "data14")
                // A contact joined from several accounts repeats the same number; keep one.
                entry.second += detail
            }
        }
        return byContact.values.map { (name, details) ->
            buildJsonObject {
                put("name", name)
                put("details", JsonArray(details.toList()))
            }
        }
    }

    /** As contacts kept on the phone itself, not in any account. */
    private fun addContacts(items: List<JsonObject>) {
        val ops = ArrayList<ContentProviderOperation>()
        for (item in items) {
            val first = ops.size
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
            val details = (item["details"] as? JsonArray).orEmpty().map { it.jsonObject }
            for (d in details) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, first)
                    .withValues(values(d))
                    .build()
            }
            if (details.none { it.text(ContactsContract.Data.MIMETYPE) == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, first)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, item.text("name"))
                    .build()
            }
            // Back references count from the start of a batch, so a batch ends between contacts.
            if (ops.size >= 300) {
                cr.applyBatch(ContactsContract.AUTHORITY, ops)
                ops.clear()
            }
        }
        if (ops.isNotEmpty()) cr.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    companion object {
        val TEXT_COLUMNS = arrayOf("address", "body", "date", "date_sent", "type", "read", "seen", "status", "subject", "locked", "error_code")
        val CALL_COLUMNS = arrayOf("number", "date", "duration", "type", "new", "name", "is_read", "presentation")
        private val DATA_COLUMNS = (1..15).map { "data$it" }.toTypedArray() + arrayOf(ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.IS_SUPER_PRIMARY)

        /** Not group memberships or other apps' entries: those only mean something where they were made. */
        private val KEPT_KINDS = setOf(
            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            CommonDataKinds.Email.CONTENT_ITEM_TYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
            CommonDataKinds.Organization.CONTENT_ITEM_TYPE, CommonDataKinds.Im.CONTENT_ITEM_TYPE,
            CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            CommonDataKinds.Event.CONTENT_ITEM_TYPE, CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
            CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE
        )

        /** The row's columns, from [skip] on; a blob (a contact's photo) as base64 under "<column>@b64". */
        private fun row(c: Cursor, skip: Int = 0) = buildJsonObject {
            for (i in skip until c.columnCount) {
                val name = c.getColumnName(i)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> put(name, c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> put(name, c.getDouble(i))
                    Cursor.FIELD_TYPE_STRING -> put(name, c.getString(i))
                    Cursor.FIELD_TYPE_BLOB -> put("$name@b64", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                }
            }
        }

        private fun values(o: JsonObject) = ContentValues().apply {
            for ((k, v) in o) {
                val p = v as? JsonPrimitive ?: continue
                when {
                    k.endsWith("@b64") -> put(k.removeSuffix("@b64"), Base64.decode(p.content, Base64.NO_WRAP))
                    p.isString -> put(k, p.content)
                    p.longOrNull != null -> put(k, p.longOrNull)
                    else -> put(k, p.content)
                }
            }
        }
    }
}
