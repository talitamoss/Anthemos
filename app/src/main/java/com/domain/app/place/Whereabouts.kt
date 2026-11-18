// app/src/main/java/com/domain/app/place/Whereabouts.kt
package com.domain.app.place

import com.domain.app.calendar.When
import com.domain.app.voice.RuleReader
import java.time.LocalDate
import java.time.ZonedDateTime

/** Something said about where you are (docs/anthemos-2.md, "Places"). */
sealed class Whereabouts {
    /** "This is home", "call this place the gym". */
    data class Name(val name: String) : Whereabouts()
    object WhereAmI : Whereabouts()
    /** "Where was I yesterday?" */
    data class WhereWas(val day: LocalDate, val phrase: String) : Whereabouts()
    /** "Start tracking" or "stop tracking": the trail of stays. */
    data class Trail(val on: Boolean) : Whereabouts()
    /** "Share my location with Ana for an hour". */
    data class Share(val names: List<String>, val until: ZonedDateTime) : Whereabouts()
    /** "Stop sharing", with everyone or [name]. */
    data class StopSharing(val name: String?) : Whereabouts()
    /** "Connect to Kronk". */
    object LinkKronk : Whereabouts()

    companion object {
        /** Live sharing runs an hour unless said otherwise, and a day at most (Kronk's limit). */
        const val DEFAULT_MINUTES = 60L
        const val LONGEST_MINUTES = 24 * 60L

        fun parse(command: String, now: ZonedDateTime): Whereabouts? {
            val text = command.trim()
            if (LINK.matches(text)) return LinkKronk
            if (WHERE_AM_I.matches(text)) return WhereAmI
            WHERE_WAS.matchEntire(text)?.let { m -> return whereWas(m.groupValues[1].trim(), now) }
            if (TRAIL_ON.matches(text)) return Trail(true)
            if (TRAIL_OFF.matches(text)) return Trail(false)
            STOP.matchEntire(text)?.let { m -> return StopSharing(m.groupValues[1].trim().ifEmpty { null }) }
            SHARE.firstNotNullOfOrNull { it.matchEntire(text) }?.let { m -> return share(m.groupValues[1], m.groupValues[2], now) }
            NAME.firstNotNullOfOrNull { it.matchEntire(text) }?.let { m ->
                val name = m.groupValues[1].trim().removePrefix("called ").trim()
                if (name.isNotEmpty() && name.split(" ").size <= 4) return Name(name)
            }
            // "This is my mum's place" names it; "this is my third coffee" doesn't.
            THIS_IS.matchEntire(text)?.let { m ->
                val name = m.groupValues[1].trim()
                if (PLACE_LIKE.matches(name) && name.split(" ").size <= 4) return Name(name)
            }
            return null
        }

        private fun whereWas(phrase: String, now: ZonedDateTime): WhereWas {
            val today = now.toLocalDate()
            // Asked about the past, so a weekday is the last one, not the next.
            val weekday = java.time.DayOfWeek.entries.firstOrNull { phrase.contains(it.name.lowercase()) }
            val day = when {
                phrase.contains("day before yesterday") -> today.minusDays(2)
                phrase.contains("yesterday") || phrase.contains("last night") -> today.minusDays(1)
                weekday != null -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(weekday))
                else -> today
            }
            val said = when (day) {
                now.toLocalDate() -> "today"
                now.toLocalDate().minusDays(1) -> "yesterday"
                else -> "on " + day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            return WhereWas(day, said)
        }

        private fun share(who: String, howLong: String, now: ZonedDateTime): Share? {
            val names = who.split(Regex("\\s*(?:,|\\band\\b|&)\\s*")).map { it.trim().removePrefix("my ").trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) return null
            val rest = howLong.trim()
            val until = when {
                rest.startsWith("until ") || rest.startsWith("till ") || rest.startsWith("til ") -> {
                    val found = When.find("at " + rest.substringAfter(' ').removePrefix("at "), now)
                    found.exact ?: found.time?.let { t ->
                        val today = now.toLocalDate().atTime(t).atZone(now.zone)
                        // "until five" said at 3 pm is 5 pm; said at 6 pm, the next morning's five is too far, so this evening's isn't meant either.
                        if (today.isAfter(now)) today else if (t.hour < 12 && today.plusHours(12).isAfter(now)) today.plusHours(12) else today.plusDays(1)
                    }
                }
                rest.startsWith("for ") -> RuleReader().duration(rest, bareIsHours = true)?.let { now.plusMinutes(it.toLong()) }
                else -> null
            } ?: now.plusMinutes(DEFAULT_MINUTES)
            return Share(names, minOf(until, now.plusMinutes(LONGEST_MINUTES)))
        }

        private val LINK = Regex("^(?:connect|link|sign in|log in|hook up)(?: (?:to|with|up to|up with|into))? kronk$|^(?:connect|link) (?:me|anthemos) (?:to|with) kronk$")
        private val WHERE_AM_I = Regex("^(?:where am i|where are we|where am i now|what(?:'s| is) this place|what place is this)(?: now| right now)?$")
        private val WHERE_WAS = Regex("^where (?:was i|have i been|did i go)(.*)$")
        private val TRAIL_ON = Regex("^(?:start|turn on|switch on|keep) (?:tracking|my trail|the trail|location|my location|tracking me|tracking my location)$|^track (?:me|my location|where i go)$")
        private val TRAIL_OFF = Regex("^(?:stop|turn off|switch off|pause) (?:tracking|my trail|the trail|location|tracking me|tracking my location)$|^don't track (?:me|my location)$")
        private val STOP = Regex("^stop sharing(?: my (?:live )?location)?(?: with (.+))?$")
        // The name is always group 1 and how long group 2.
        private val SHARE = listOf(
            Regex("^(?:share|send) (?:my )?(?:live )?location (?:with|to) (.+?)( (?:for|until|till|til) .+)?$"),
            Regex("^let (.+?) (?:see|know) where i am( (?:for|until|till|til) .+)?$")
        )
        private val NAME = listOf(
            Regex("^this place is (.+)$"),
            Regex("^(?:call|name|save|remember|mark) this (?:place|spot)(?: as)? (.+)$")
        )
        private val THIS_IS = Regex("^(?:this is|we're at|we are at) (?:my |the |our )?(.+)$")
        private val PLACE_LIKE = Regex(
            "^(?:home|work|.+'s(?: place| house| flat)?|.*\\b(?:house|place|home|office|work|gym|school|uni|university|studio|cafe|café|shop|" +
                "flat|apartment|park|beach|pool|church|library|pub|bar|club|garden|farm|hotel|hostel|station|clinic|hospital)\\b.*)$"
        )
    }
}
