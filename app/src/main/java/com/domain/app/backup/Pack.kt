// app/src/main/java/com/domain/app/backup/Pack.kt
package com.domain.app.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/**
 * How the phone's own records are bundled for the pod (docs/anthemos-2.md,
 * "Phone backup"): texts and calls in one file a month, contacts in one
 * file. Bundles only grow: what is on the phone is added to what the pod
 * already holds, so a wiped or new phone never shrinks the backup.
 */
object Pack {
    const val CONTACTS = "contacts"
    const val TEXTS = "texts"
    const val CALLS = "calls"
    val KINDS = listOf(CONTACTS, TEXTS, CALLS)

    /** Items by bundle: "contacts", or "texts:2026-11" by the month they happened (UTC). */
    fun group(kind: String, items: List<JsonObject>): Map<String, List<JsonObject>> =
        if (kind == CONTACTS) {
            if (items.isEmpty()) emptyMap() else mapOf(CONTACTS to items)
        } else {
            items.groupBy { "$kind:" + month(it.long("date")) }
        }

    fun kindOf(bundle: String) = bundle.substringBefore(':')

    /** What makes two records the same one, here and on another phone. */
    fun key(kind: String, item: JsonObject): String = when (kind) {
        CONTACTS -> "c|" + item.text("name").trim().lowercase()
        TEXTS -> "t|${item.text("address")}|${item.long("date")}|${item.text("type")}|${item.text("body")}"
        else -> "k|${item.text("number")}|${item.long("date")}|${item.text("type")}"
    }

    /** The pod's bundle with this phone's added; this phone's wins where both have one. */
    fun merge(kind: String, phone: List<JsonObject>, pod: List<JsonObject>): List<JsonObject> {
        val all = LinkedHashMap<String, JsonObject>()
        for (item in pod) all[key(kind, item)] = item
        for (item in phone) all[key(kind, item)] = item
        return if (kind == CONTACTS) all.values.sortedBy { it.text("name").lowercase() }
        else all.values.sortedBy { it.long("date") }
    }

    /** Changes whenever the bundle's records do, whatever order they came in. */
    fun hash(kind: String, items: List<JsonObject>): String {
        val md = MessageDigest.getInstance("SHA-256")
        items.map { it.toString() to key(kind, it) }.sortedBy { it.second }.forEach { md.update(it.first.toByteArray()) }
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    fun encode(items: List<JsonObject>): ByteArray = JsonArray(items).toString().toByteArray()

    fun decode(bytes: ByteArray): List<JsonObject> = Json.parseToJsonElement(String(bytes)).jsonArray.map { it.jsonObject }

    /** The list of bundles kept on the pod, with how many records each holds. */
    data class Index(val counts: Map<String, Int>, val made: Long, val phone: String) {
        fun total(kind: String) = counts.filterKeys { kindOf(it) == kind }.values.sum()

        fun encode(): ByteArray = buildJsonObject {
            put("made", made)
            put("phone", phone)
            put("counts", buildJsonObject { counts.toSortedMap().forEach { (k, v) -> put(k, v) } })
        }.toString().toByteArray()

        companion object {
            fun decode(bytes: ByteArray): Index {
                val o = Json.parseToJsonElement(String(bytes)).jsonObject
                return Index(
                    counts = o["counts"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content.toInt() }.orEmpty(),
                    made = o["made"]?.jsonPrimitive?.longOrNull ?: 0,
                    phone = o["phone"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        }
    }

    private fun month(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    fun JsonObject.text(k: String): String = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    fun JsonObject.long(k: String): Long = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0
}
