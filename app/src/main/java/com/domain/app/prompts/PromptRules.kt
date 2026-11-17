// app/src/main/java/com/domain/app/prompts/PromptRules.kt
package com.domain.app.prompts

import com.domain.app.calendar.Event
import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** A question Anthemos asks. [context] tells the reader what the answer is about. */
data class Prompt(
    val id: String,
    val text: String,
    val context: String? = null,
    /** For a follow-up: the entry the answer adds to. */
    val entryId: String? = null,
    /** For a follow-up: what the answer fills in, "minutes" or "amount". */
    val field: String? = null,
    /** Minutes the phone already knows (estimated sleep), used if the answer gives none. */
    val minutes: Int? = null
)

/** One time a prompt was asked, and whether it was answered. */
data class Ask(val time: Instant, val answered: Boolean)

interface PromptHistory {
    fun asks(id: String): List<Ask>
    fun stopped(id: String): Boolean
}

/**
 * Asks about gaps, not a script (docs/anthemos-2.md, "Prompts"): a prompt
 * fires only when something usual is missing from today. Meals, sleep and
 * the evening are asked about from the start; water and moving only once
 * they show up on three of the last fourteen days. Prompts that go
 * unanswered three times in a row rest for a week; "Stop asking" ends one.
 * At most three a day, none in quiet hours.
 */
object PromptRules {
    const val MAX_PER_DAY = 3
    const val FOLLOW_UP = "follow-up"
    /** All prompts after events share this id for counting and stopping. */
    const val EVENT = "event"
    val QUIET_FROM: LocalTime = LocalTime.of(21, 0)
    val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)

    private class Seed(
        val prompt: Prompt,
        val from: LocalTime,
        val until: LocalTime,
        /** Only once it is part of this person's days. */
        val learned: ((Item) -> Boolean)? = null,
        /** True when today still lacks it. */
        val missing: (List<Entry>) -> Boolean
    )

    private fun List<Entry>.items() = flatMap { it.items }
    private fun List<Entry>.answered(id: String) = any { it.prompt != null && it.prompt == SEEDS_BY_ID[id] }
    private fun hasMeal(today: List<Entry>, vararg meals: String) =
        today.items().any { it.kind == "food" && it.meal in meals }

    private val isWater: (Item) -> Boolean = { it.group == "water" }
    private val isMoving: (Item) -> Boolean = { it.kind == "activity" }

    private val SEEDS = listOf(
        Seed(Prompt("sleep", "How did you sleep?", "sleep"), LocalTime.of(7, 0), LocalTime.of(11, 0)) { today ->
            today.items().none { it.kind == "sleep" }
        },
        Seed(Prompt("breakfast", "Had breakfast yet?", "breakfast"), LocalTime.of(8, 30), LocalTime.of(10, 30)) { today ->
            !hasMeal(today, "breakfast", "brunch")
        },
        Seed(Prompt("lunch", "What did you have for lunch?", "lunch"), LocalTime.of(13, 0), LocalTime.of(14, 30)) { today ->
            !hasMeal(today, "lunch", "brunch")
        },
        Seed(Prompt("water", "Had any water today?", "water"), LocalTime.of(14, 0), LocalTime.of(16, 0), isWater) { today ->
            today.items().none(isWater)
        },
        Seed(Prompt("moving", "Get outside or move today?", "activity"), LocalTime.of(16, 30), LocalTime.of(18, 0), isMoving) { today ->
            today.items().none(isMoving)
        },
        Seed(Prompt("dinner", "What did you have for dinner?", "dinner"), LocalTime.of(19, 0), LocalTime.of(20, 30)) { today ->
            !hasMeal(today, "dinner")
        },
        Seed(Prompt("evening", "Anything from today you want to keep?", "day"), LocalTime.of(20, 30), LocalTime.of(21, 0)) { true }
    )
    private val SEEDS_BY_ID = SEEDS.associate { it.prompt.id to it.prompt.text }

    /** The prompt to ask now, if any. [entries] covers at least the last fourteen days. */
    fun due(now: ZonedDateTime, entries: List<Entry>, history: PromptHistory, events: List<Event> = emptyList()): Prompt? {
        val time = now.toLocalTime()
        if (quiet(time)) return null
        val midnight = now.truncatedTo(ChronoUnit.DAYS).toInstant()
        val askedToday = (SEEDS.map { it.prompt.id } + EVENT).sumOf { id -> history.asks(id).count { it.time >= midnight } }
        if (askedToday >= MAX_PER_DAY) return null

        // "How was the dentist?" within three hours of it ending.
        if (!history.stopped(EVENT)) {
            events.filter { e ->
                !e.reminder && !e.allDay && e.end <= now.toInstant() && e.end > now.toInstant().minus(3, ChronoUnit.HOURS) &&
                    history.asks("$EVENT:${e.id}").isEmpty()
            }.minByOrNull { it.end }?.let { e ->
                return Prompt("$EVENT:${e.id}", "How was ${e.title.replaceFirstChar { it.lowercase() }}?", "event")
            }
        }

        val today = entries.filter { it.time >= midnight }
        val fortnight = entries.filter { it.time >= midnight.minus(14, ChronoUnit.DAYS) && it.time < midnight }

        return SEEDS.firstOrNull { seed ->
            val id = seed.prompt.id
            val asks = history.asks(id)
            time >= seed.from && time < seed.until &&
                !history.stopped(id) &&
                asks.none { it.time >= midnight } &&
                !resting(asks, now) &&
                !today.answered(id) &&
                seed.learned?.let { usual(fortnight, it) } != false &&
                seed.missing(today)
        }?.prompt
    }

    fun quiet(time: LocalTime) = time >= QUIET_FROM || time < QUIET_UNTIL

    /** Three unanswered in a row, the last within a week. */
    private fun resting(asks: List<Ask>, now: ZonedDateTime): Boolean {
        val last3 = asks.sortedByDescending { it.time }.take(3)
        return last3.size == 3 && last3.none { it.answered } &&
            last3.first().time > now.minusDays(7).toInstant()
    }

    private fun usual(days: List<Entry>, test: (Item) -> Boolean): Boolean =
        days.filter { e -> e.items.any(test) }
            .map { it.time.truncatedTo(ChronoUnit.DAYS) }
            .distinct().size >= 3

    /**
     * One follow-up for a new entry, when an obvious detail is missing:
     * how long a walk was, how much something bought cost, how many hours
     * of sleep.
     */
    fun followUp(entry: Entry): Prompt? {
        val words = entry.words.lowercase()
        val activity = entry.items.firstOrNull { it.kind == "activity" && it.minutes == null }
        val sleep = entry.items.firstOrNull { it.kind == "sleep" && it.minutes == null }
        val bought = Regex("\\b(bought|ordered|takeaway|paid|spent|got .+ from)\\b").containsMatchIn(words) &&
            entry.items.none { it.kind == "spend" }
        return when {
            activity != null -> Prompt(FOLLOW_UP, "How long was the ${activity.label}?", entryId = entry.id, field = "minutes")
            sleep != null -> Prompt(FOLLOW_UP, "How many hours did you get?", entryId = entry.id, field = "minutes")
            bought -> Prompt(FOLLOW_UP, "How much was it?", entryId = entry.id, field = "amount")
            else -> null
        }
    }
}
