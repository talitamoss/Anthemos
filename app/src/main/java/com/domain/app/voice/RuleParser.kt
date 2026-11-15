// app/src/main/java/com/domain/app/voice/RuleParser.kt
package com.domain.app.voice

import java.time.LocalTime

/**
 * The fast path: turns common sentences into a [VoiceRequest] without a
 * language model (docs/anthemos-2.md, "Mind"). Anything it doesn't
 * recognise becomes a Journal entry with the exact words, so nothing said
 * is lost.
 *
 * The values use the same keys as each kind's manual entry, so voice
 * entries read like any other in Collect and Reflect.
 */
class RuleParser(private val now: () -> LocalTime = { LocalTime.now() }) {

    fun parse(raw: String): VoiceRequest {
        val transcript = raw.trim()
        val text = transcript.lowercase().replace(Regex("[.!?]+$"), "")

        return undo(transcript, text)
            ?: call(transcript, text)
            ?: note(transcript, text)
            ?: water(transcript, text)
            ?: caffeine(transcript, text)
            ?: alcohol(transcript, text)
            ?: movement(transcript, text)
            ?: food(transcript, text)
            ?: journal(transcript, transcript)
    }

    private fun undo(transcript: String, text: String): VoiceRequest? =
        if (text in UNDO) VoiceRequest.Undo(transcript) else null

    private fun call(transcript: String, text: String): VoiceRequest? {
        val match = CALL.matchEntire(text) ?: return null
        // `text` is `transcript` lowercased, so positions line up: keep the
        // name as it was said.
        val range = match.groups[1]?.range ?: return null
        val name = transcript.substring(range.first, range.last + 1).trim()
        return if (name.isEmpty()) null else VoiceRequest.Call(transcript, name)
    }

    private fun note(transcript: String, text: String): VoiceRequest? {
        val match = NOTE.find(text) ?: return null
        val body = transcript.substring(match.range.last + 1).trim().ifEmpty { return null }
        return journal(transcript, body)
    }

    private fun journal(transcript: String, entry: String): VoiceRequest.Record {
        val words = entry.split(Regex("\\s+")).filter { it.isNotBlank() }
        return VoiceRequest.Record(
            transcript = transcript,
            pluginId = "journal",
            type = "journal_entry",
            value = mapOf("entry" to entry, "wordCount" to words.size, "charCount" to entry.length),
            reply = "Noted"
        )
    }

    private fun water(transcript: String, text: String): VoiceRequest? {
        if (!text.contains("water") || !CONSUMED.containsMatchIn(text)) return null
        val ml = Regex("(\\d+)\\s*(ml|millilit)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*lit").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toInt() }
            ?: (countBefore(text, Regex("glass|cup|bottle")) * if (text.contains("bottle")) 500 else 250)
        return VoiceRequest.Record(
            transcript, "water", "water_intake",
            mapOf("amount" to ml, "unit" to "ml"),
            "$ml ml of water logged"
        )
    }

    private fun caffeine(transcript: String, text: String): VoiceRequest? {
        if (!CONSUMED.containsMatchIn(text)) return null
        val type = CAFFEINE.firstOrNull { Regex("\\b$it\\b").containsMatchIn(text) } ?: return null
        return VoiceRequest.Record(
            transcript, "caffeine", "caffeine_intake",
            mapOf("type" to type.replaceFirstChar { it.uppercase() }),
            "${type.replaceFirstChar { it.uppercase() }} logged"
        )
    }

    private fun alcohol(transcript: String, text: String): VoiceRequest? {
        if (!CONSUMED.containsMatchIn(text)) return null
        val type = ALCOHOL.firstOrNull { Regex("\\b$it\\b").containsMatchIn(text) } ?: return null
        return VoiceRequest.Record(
            transcript, "alcohol", "alcohol_intake",
            mapOf("type" to type.replaceFirstChar { it.uppercase() }),
            "${type.replaceFirstChar { it.uppercase() }} logged"
        )
    }

    private fun movement(transcript: String, text: String): VoiceRequest? {
        val kind = MOVEMENT.entries.firstOrNull { (pattern, _) -> Regex("\\b($pattern)\\b").containsMatchIn(text) }?.value
            ?: return null
        val minutes = Regex("(\\d+)\\s*(min|minute)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*hour").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 60).toInt() }
            ?: when {
                text.contains("half an hour") -> 30
                Regex("\\b(an|one) hour").containsMatchIn(text) -> 60
                else -> null
            }
        val value = buildMap<String, Any> {
            put("type", kind)
            minutes?.let { put("duration", it) }
            put("notes", "")
        }
        val reply = if (minutes != null) "$kind, $minutes minutes, logged" else "$kind logged"
        return VoiceRequest.Record(transcript, "movement", "movement_entry", value, reply)
    }

    private fun food(transcript: String, text: String): VoiceRequest? {
        val meal = MEALS.firstOrNull { Regex("\\b$it\\b").containsMatchIn(text) }
        val ate = Regex("\\b(ate|eaten|eating|had|having)\\b").containsMatchIn(text)
        if (meal == null && !Regex("\\b(ate|eaten|eating)\\b").containsMatchIn(text)) return null
        if (meal != null && !ate && !text.contains("for $meal")) return null

        val mealType = meal ?: mealForTime(now())
        val items = foodItems(transcript, meal)
        val value = mapOf(
            "mealType" to mealType.replaceFirstChar { it.uppercase() },
            "foodCategory" to "",
            "portion" to 1.0f,
            "notes" to items
        )
        val label = mealType.replaceFirstChar { it.uppercase() }
        return VoiceRequest.Record(transcript, "food", "food_entry", value, "$label logged")
    }

    /** "I just had lunch, chicken and a salad" -> "chicken and a salad". */
    private fun foodItems(transcript: String, meal: String?): String {
        var rest = transcript.trim().trimEnd('.', '!', '?')
        val lower = rest.lowercase()
        if (meal != null) {
            Regex("\\b$meal\\b[,:]?").find(lower)?.let { m ->
                val after = rest.substring(m.range.last + 1).trim().trimStart(',', ':', '-').trim()
                val before = rest.substring(0, m.range.first)
                rest = if (after.isNotEmpty() && !after.startsWith("for", ignoreCase = true)) after
                else before.replace(Regex("(?i)\\bfor\\s*$"), "")
            }
        }
        return rest.replace(Regex("(?i)^(i\\s+)?(just\\s+)?(ate|had|have eaten|was eating|eating|having)\\s+"), "")
            .replace(Regex("(?i)^(i\\s+)?(just\\s+)?(ate|had)\\s+"), "")
            .trim().trimEnd(',', '.')
    }

    private fun countBefore(text: String, unit: Regex): Int {
        val m = Regex("\\b(a|an|one|two|three|four|five|\\d+)\\s+(\\w+\\s+)?(${unit.pattern})").find(text)
            ?: return 1
        return NUMBERS[m.groupValues[1]] ?: m.groupValues[1].toIntOrNull() ?: 1
    }

    private fun mealForTime(time: LocalTime): String = when {
        time.isBefore(LocalTime.of(10, 30)) -> "breakfast"
        time.isBefore(LocalTime.of(15, 0)) -> "lunch"
        time.isBefore(LocalTime.of(17, 0)) -> "snack"
        else -> "dinner"
    }

    companion object {
        private val UNDO = setOf("undo", "undo that", "take that back", "delete that", "scratch that", "cancel that")
        private val CALL = Regex("^(?:please\\s+)?(?:call|ring|phone|dial)\\s+(.+)$")
        private val NOTE = Regex("^(?:please\\s+)?(?:take a note|make a note|note that|note|remember that|remember)[,:]?\\s")
        private val CONSUMED = Regex("\\b(had|have had|drank|drunk|drink|drinking|having|just had)\\b")
        private val CAFFEINE = listOf("espresso", "latte", "cappuccino", "flat white", "long black", "coffee", "matcha", "green tea", "tea", "energy drink")
        private val ALCOHOL = listOf("beer", "wine", "cider", "cocktail", "whisky", "whiskey", "vodka", "gin", "rum", "spirit")
        // Patterns matched as whole words.
        private val MOVEMENT = linkedMapOf(
            "walk|walked|walking" to "Walk",
            "run|ran|running|jog|jogged|jogging" to "Run",
            "swim|swam|swimming" to "Swim",
            "cycle|cycled|cycling|bike ride|rode my bike" to "Cycle",
            "yoga" to "Yoga",
            "gym" to "Gym",
            "workout|worked out" to "Workout",
            "hike|hiked|hiking" to "Hike",
            "dance|danced|dancing" to "Dance"
        )
        private val MEALS = listOf("breakfast", "brunch", "lunch", "dinner", "tea time", "supper", "snack")
        private val NUMBERS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5)
    }
}
