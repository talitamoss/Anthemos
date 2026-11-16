// app/src/main/java/com/domain/app/voice/Entry.kt
package com.domain.app.voice

import com.domain.app.core.data.DataPoint
import java.time.Instant

/**
 * One thing said, kept as said (docs/anthemos-2.md, "Words first").
 *
 * The words are the record. The [items] are a reading of them: what a
 * reader found in the words, with open labels rather than fixed kinds. A
 * reading can be thrown away and redone by a better reader; the words stay.
 *
 * Stored as a DataPoint with pluginId [PLUGIN_ID], so it lives in the same
 * encrypted store as everything else.
 */
data class Entry(
    val id: String,
    val time: Instant,
    val words: String,
    val items: List<Item>,
    /** Which reader produced [items], so old readings can be redone. */
    val reader: String,
    /** The prompt this answered ("How did you sleep?"), if any. */
    val prompt: String? = null
) {
    fun toDataPoint(): DataPoint = DataPoint(
        id = id,
        pluginId = PLUGIN_ID,
        timestamp = time,
        type = "said",
        value = buildMap {
            put("words", words)
            put("items", items.map { it.toMap() })
            put("reader", reader)
            prompt?.let { put("prompt", it) }
        },
        metadata = mapOf("inputType" to "voice"),
        source = "voice"
    )

    companion object {
        const val PLUGIN_ID = "entry"

        fun from(point: DataPoint): Entry? {
            if (point.pluginId != PLUGIN_ID) return null
            val items = (point.value["items"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.let(Item::fromMap) }
            return Entry(
                id = point.id,
                time = point.timestamp,
                words = point.value["words"]?.toString().orEmpty(),
                items = items,
                reader = point.value["reader"]?.toString().orEmpty(),
                prompt = point.value["prompt"]?.toString()
            )
        }
    }
}

/**
 * Something found in an entry. [kind] is a loose grouping (food, drink,
 * activity, call), never a closed list; [label] is what it was, in the
 * person's own words ("chicken sandwich"); [group] is a wider name for
 * matching questions ("flat white" is a coffee).
 */
data class Item(
    val kind: String,
    val label: String,
    val count: Int = 1,
    val group: String? = null,
    val meal: String? = null,
    val minutes: Int? = null,
    val ml: Int? = null,
    /** For a call: the name as it was said ("dad"). */
    val said: String? = null,
    /** Money spent, in dollars. */
    val amount: Double? = null
) {
    fun toMap(): Map<String, Any> = buildMap {
        put("kind", kind)
        put("label", label)
        put("count", count)
        group?.let { put("group", it) }
        meal?.let { put("meal", it) }
        minutes?.let { put("minutes", it) }
        ml?.let { put("ml", it) }
        said?.let { put("said", it) }
        amount?.let { put("amount", it) }
    }

    companion object {
        fun fromMap(map: Map<*, *>): Item? {
            val kind = map["kind"]?.toString() ?: return null
            fun int(key: String) = (map[key] as? Number)?.toInt()
            return Item(
                kind = kind,
                label = map["label"]?.toString().orEmpty(),
                count = int("count") ?: 1,
                group = map["group"]?.toString(),
                meal = map["meal"]?.toString(),
                minutes = int("minutes"),
                ml = int("ml"),
                said = map["said"]?.toString(),
                amount = (map["amount"] as? Number)?.toDouble()
            )
        }
    }
}
