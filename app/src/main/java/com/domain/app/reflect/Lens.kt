// app/src/main/java/com/domain/app/reflect/Lens.kt
package com.domain.app.reflect

import com.domain.app.sense.SleepGuess
import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reflections (docs/anthemos-2.md, "Reflections"): what you've recorded,
 * looked at, on the one page under what you've just said. Each [Lens] reads
 * the record and gives back one reflection, or nothing when there is
 * nothing to show, so the page only holds what you've actually recorded.
 * A new reflection is a new lens in [Lenses.ALL].
 */
interface Lens {
    val id: String
    fun look(record: Record): Reflection?

    /** For a lens that gives several (one per habit, one per measure). */
    fun all(record: Record): List<Reflection> = listOfNotNull(look(record))
}

/**
 * One reflection: a [title] ("Sleep score"), a [value] ("82"), a short [note]
 * ("7h 20m last night"), and the last seven days, oldest first, as numbers
 * for a quiet row of bars (null where nothing was recorded).
 */
data class Reflection(
    val id: String,
    val title: String,
    val value: String,
    val note: String? = null,
    val days: List<Float?> = emptyList()
)

/**
 * What the lenses read: the entries from the encrypted store, and what the
 * phone saw (sleep and screen time) when usage access is on. Read on the
 * phone; nothing here leaves it.
 */
class Record(
    entries: List<Entry>,
    val today: LocalDate,
    val zone: ZoneId,
    /** Sleep the phone saw, by the morning it ended. */
    val phoneNights: Map<LocalDate, SleepGuess> = emptyMap(),
    /** Minutes the screen was on, by day. */
    val screen: Map<LocalDate, Int> = emptyMap()
) {
    val entries: List<Entry> = entries.sortedBy { it.time }

    /** The last seven days, oldest first, ending today. */
    val week: List<LocalDate> = (6 downTo 0).map { today.minusDays(it.toLong()) }

    fun dateOf(entry: Entry): LocalDate = entry.time.atZone(zone).toLocalDate()

    fun on(date: LocalDate): List<Entry> = entries.filter { dateOf(it) == date }

    /** Every item found this week that [matches], with its entry. */
    fun thisWeek(matches: (Item) -> Boolean): List<Pair<Entry, Item>> =
        entries.filter { dateOf(it) in week }.flatMap { e -> e.items.filter(matches).map { e to it } }
}

/** "7h 20m", "45m". */
fun hm(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
