// app/src/main/java/com/domain/app/calendar/Event.kt
package com.domain.app.calendar

import com.domain.app.core.data.DataPoint
import java.time.Instant

/**
 * Something in the diary (docs/anthemos-2.md, "Calendar"). Kept in
 * Anthemos's own encrypted store, not Android's shared calendar, which is
 * unencrypted and which Proton Calendar doesn't use anyway. Each event can be
 * handed to Proton with one tap.
 */
data class Event(
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
    val place: String? = null,
    /** A reminder: a notification at [start]. */
    val reminder: Boolean = false,
    /** The words it was made from. */
    val words: String = ""
) {
    fun toDataPoint(): DataPoint = DataPoint(
        id = id,
        pluginId = PLUGIN_ID,
        timestamp = start,
        type = if (reminder) "reminder" else "event",
        value = buildMap {
            put("title", title)
            put("start", start.toEpochMilli())
            put("end", end.toEpochMilli())
            put("allDay", allDay)
            put("reminder", reminder)
            put("words", words)
            place?.let { put("place", it) }
        },
        source = "voice"
    )

    companion object {
        const val PLUGIN_ID = "event"

        fun from(point: DataPoint): Event? {
            if (point.pluginId != PLUGIN_ID) return null
            val v = point.value
            fun millis(key: String) = (v[key] as? Number)?.toLong()?.let(Instant::ofEpochMilli)
            val start = millis("start") ?: return null
            return Event(
                id = point.id,
                title = v["title"]?.toString().orEmpty(),
                start = start,
                end = millis("end") ?: start,
                allDay = v["allDay"] == true,
                place = v["place"]?.toString(),
                reminder = v["reminder"] == true,
                words = v["words"]?.toString().orEmpty()
            )
        }
    }
}
