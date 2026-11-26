// app/src/main/java/com/domain/app/reflect/Ahead.kt
package com.domain.app.reflect

import com.domain.app.calendar.Event
import com.domain.app.weather.Forecast
import com.domain.app.weather.Weather
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What's coming, at the top of the page (docs/anthemos-2.md, "Coming up"):
 * the weather where you are, and your next events, Anthemos's own and the
 * linked calendar's.
 */
data class Ahead(
    val sky: Sky? = null,
    val events: List<Coming> = emptyList(),
    /** Why there's no weather, said on the tile: no location, no forecast. */
    val skyWhy: String? = null,
    /** Why there are no events: no calendar linked, it didn't load, nothing on. */
    val eventsWhy: String? = null
)

/** The weather on its tile: "18°", "Partly cloudy", "today 12 to 21° · 40% rain". */
data class Sky(val temp: String?, val now: String?, val day: String?)

/** One event coming up: when ("14:00", "Tomorrow 9:30", "Fri, all day"), what, and where. */
data class Coming(val id: String, val time: String, val title: String, val place: String? = null)

object Upcoming {
    private val CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

    /**
     * "18°, partly cloudy · today 12 to 21°, 40% rain". From six in the
     * evening it gives tomorrow instead of what's left of today.
     */
    fun weather(f: Forecast, now: ZonedDateTime): String? {
        val sky = sky(f, now)
        val current = sky.temp?.let { t -> t + (sky.now?.let { ", " + it.lowercase() } ?: "") }
        return listOfNotNull(current, sky.day).joinToString(" · ").ifEmpty { null }
    }

    fun sky(f: Forecast, now: ZonedDateTime): Sky {
        val evening = now.hour >= 18
        val day = f.days.firstOrNull { it.date == if (evening) now.toLocalDate().plusDays(1) else now.toLocalDate() }
        val ahead = day?.let {
            (if (evening) "tomorrow " else "today ") + "${it.min.roundToInt()} to ${it.max.roundToInt()}°" +
                (if (evening) ", " + Weather.describe(it.code).lowercase() else "") +
                (if (it.rainChance >= 30) ", ${it.rainChance}% rain" else "")
        }
        return Sky(f.nowTemp?.let { "${it.roundToInt()}°" }, f.nowCode?.let { Weather.describe(it) }, ahead)
    }

    /**
     * What's on for the rest of today and tomorrow, at most [limit]; when
     * that's nothing, the next thing in the week. One in both calendars is
     * told once.
     */
    fun events(all: List<Event>, now: ZonedDateTime, limit: Int = 4): List<Coming> {
        val zone = now.zone
        val seen = mutableSetOf<String>()
        val ahead = all.filter { it.end.isAfter(now.toInstant()) && !it.reminder }
            .sortedBy { it.start }
            .filter { seen.add(it.start.toString() + "|" + it.title.lowercase()) }
        val untilTomorrow = now.toLocalDate().plusDays(2).atStartOfDay(zone).toInstant()
        val soon = ahead.filter { it.start.isBefore(untilTomorrow) }
        val week = now.toLocalDate().plusDays(8).atStartOfDay(zone).toInstant()
        val shown = soon.ifEmpty { ahead.filter { it.start.isBefore(week) }.take(1) }
        return shown.take(limit).map { e ->
            val start = e.start.atZone(zone)
            val day = when (start.toLocalDate()) {
                now.toLocalDate() -> if (e.allDay) "Today" else null
                now.toLocalDate().plusDays(1) -> "Tomorrow"
                else -> WEEKDAY.format(start)
            }
            val time = when {
                e.allDay -> "${day ?: "Today"}, all day"
                start.isBefore(now) -> "Now"
                day == null -> CLOCK.format(start)
                else -> "$day ${CLOCK.format(start)}"
            }
            Coming(e.id, time, e.title, e.place?.takeIf { it.isNotBlank() })
        }
    }
}
