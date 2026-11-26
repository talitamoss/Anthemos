// app/src/main/java/com/domain/app/voice/Numbers.kt
package com.domain.app.voice

/**
 * The speech model writes numbers as words ("forty five minutes"); this
 * turns them back into digits so the rest of the reading can count.
 */
object Numbers {
    private val UNITS = mapOf(
        "zero" to 0, "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "couple" to 2, "few" to 3
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    /** "forty five" -> 45, "two" -> 2, "12" -> 12; null if not a number. */
    fun parse(words: String): Int? {
        val parts = words.trim().lowercase().split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        parts.singleOrNull()?.toIntOrNull()?.let { return it }
        var total = 0
        for (part in parts) {
            when {
                part == "hundred" -> total = maxOf(total, 1) * 100
                part in TENS -> total += TENS.getValue(part)
                part in UNITS -> total += UNITS.getValue(part)
                else -> return null
            }
        }
        return total
    }

    /** A pattern for a spoken or written number, for use inside other patterns. */
    val PATTERN: String = run {
        val unit = UNITS.keys.joinToString("|")
        val tens = TENS.keys.joinToString("|")
        "\\d+|(?:(?:$tens)(?:[\\s-](?:$unit))?)|(?:$unit)"
    }
}
