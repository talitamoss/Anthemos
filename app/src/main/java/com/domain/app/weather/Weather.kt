// app/src/main/java/com/domain/app/weather/Weather.kt
package com.domain.app.weather

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** What a weather question asks: which day, where, and whether it's about rain or warmth. */
data class WeatherAsk(val day: LocalDate, val dayPhrase: String, val place: String?, val about: About) {
    enum class About { ALL, RAIN, WARMTH }
}

/** One day's forecast, and now, from Open-Meteo. */
data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val min: Double,
    val max: Double,
    /** Highest chance of rain in the day, percent. */
    val rainChance: Int,
    val rainMm: Double
)

data class Forecast(val place: String?, val nowTemp: Double?, val nowFeels: Double?, val nowCode: Int?, val days: List<DayForecast>)

/**
 * Weather, said and answered (docs/anthemos-2.md, "Weather"). The forecast
 * is Open-Meteo's ([Forecasts]); this reads the question and words the answer.
 */
object Weather {

    fun parse(command: String, now: ZonedDateTime): WeatherAsk? {
        val c = command.trim()
        // Inside is the house's (Home Assistant).
        if (Regex("\\b(?:inside|in here|at home|in the house)\\b").containsMatchIn(c)) return null
        val about = when {
            RAIN.containsMatchIn(c) -> WeatherAsk.About.RAIN
            WARMTH.containsMatchIn(c) -> WeatherAsk.About.WARMTH
            GENERAL.containsMatchIn(c) -> WeatherAsk.About.ALL
            else -> return null
        }
        val today = now.toLocalDate()
        val (day, phrase) = when {
            Regex("\\btomorrow\\b").containsMatchIn(c) -> today.plusDays(1) to "tomorrow"
            Regex("\\b(?:this )?weekend\\b").containsMatchIn(c) -> {
                val sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                sat to "on Saturday"
            }
            else -> DayOfWeek.values().firstOrNull { Regex("\\b(?:on )?${it.name.lowercase()}\\b").containsMatchIn(c) }?.let { d ->
                val date = today.with(TemporalAdjusters.next(d))
                date to "on ${d.name.lowercase().replaceFirstChar { it.uppercase() }}"
            } ?: (today to "today")
        }
        val place = PLACE.find(c)?.groupValues?.get(1)?.trim()
            ?.replace(Regex("\\b(?:today|tomorrow|this weekend|weekend|on \\w+day|\\w+day|right now|now|later|tonight)\\b"), "")
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() && it !in setOf("here", "home", "the morning", "the afternoon") }
        return WeatherAsk(day, phrase, place, about)
    }

    fun answer(ask: WeatherAsk, forecast: Forecast, today: LocalDate): String {
        val day = forecast.days.firstOrNull { it.date == ask.day }
            ?: return "I don't have the forecast that far ahead."
        val where = forecast.place?.let { " in $it" }.orEmpty()
        val sky = describe(day.code)
        val range = "${day.min.roundToInt()} to ${day.max.roundToInt()} degrees"
        val rain = if (day.rainChance >= 20) ", ${day.rainChance}% chance of rain" else ""
        return when (ask.about) {
            WeatherAsk.About.RAIN -> when {
                day.rainChance >= 60 -> "Yes, likely ${ask.dayPhrase}$where: ${day.rainChance}% chance" + mm(day.rainMm) + "."
                day.rainChance >= 30 -> "Maybe ${ask.dayPhrase}$where: ${day.rainChance}% chance" + mm(day.rainMm) + "."
                else -> "Probably not ${ask.dayPhrase}$where: ${day.rainChance}% chance."
            }
            WeatherAsk.About.WARMTH -> {
                val now = if (ask.day == today && forecast.nowTemp != null) "Now ${forecast.nowTemp.roundToInt()} degrees" + feels(forecast) + ". " else ""
                "$now${ask.dayPhrase.replaceFirstChar { it.uppercase() }}$where: $range."
            }
            WeatherAsk.About.ALL -> {
                val now = if (ask.day == today && forecast.nowTemp != null) " Now ${forecast.nowTemp.roundToInt()} degrees" +
                    (forecast.nowCode?.let { " and ${describe(it).lowercase()}" } ?: "") + "." else ""
                "${ask.dayPhrase.replaceFirstChar { it.uppercase() }}$where: ${sky.lowercase()}, $range$rain.$now"
            }
        }
    }

    private fun mm(mm: Double) = if (mm >= 1) ", about ${mm.roundToInt()} mm" else ""

    private fun feels(f: Forecast): String {
        val feels = f.nowFeels ?: return ""
        val t = f.nowTemp ?: return ""
        return if (kotlin.math.abs(feels - t) >= 3) ", feels like ${feels.roundToInt()}" else ""
    }

    /** WMO weather codes, as Open-Meteo gives them. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 66 -> "Rain"
        65, 67 -> "Heavy rain"
        71, 73, 75, 77 -> "Snow"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> "Mixed"
    }

    private val GENERAL = Regex("\\bweather\\b|\\bforecast\\b|^what(?:'s| is) it (?:like|doing) outside\\b")
    private val RAIN = Regex(
        "\\b(?:will|is) it (?:going to )?(?:rain|be raining|shower|storm)\\b|\\bchance of rain\\b|\\bneed an? (?:umbrella|raincoat|rain jacket)\\b|" +
            "\\bis rain (?:forecast|expected|coming)\\b|\\bany rain\\b"
    )
    private val WARMTH = Regex(
        "\\bhow (?:hot|cold|warm|chilly) (?:is it|will it be|is it going to be)\\b|\\btemperature (?:outside|out there|today|tomorrow)\\b|" +
            "\\bwhat(?:'s| is) the temperature\\b(?! (?:inside|in the|in here|at home))|\\bdo i need a (?:jacket|jumper|coat)\\b"
    )
    private val PLACE = Regex("\\b(?:in|at|for) ((?!the (?:morning|afternoon|evening))[a-z][a-z ]+?)$")
}
