// app/src/main/java/com/domain/app/kronk/SoundsLike.kt
package com.domain.app.kronk

/**
 * Finds the Mate a heard name was meant to be (docs/anthemos-2.md, "Kronk").
 *
 * The speech model only knows ordinary English words, so an unusual name
 * comes back as whatever it sounds closest to: Pargo as "pago" or "par go",
 * Kronk as "cronk" or "crank". Names are compared by sound instead: the
 * consonants as they are said, so "c" and "k" are one sound and an "r" after
 * a vowel, which most English speakers don't sound, is dropped.
 */
object SoundsLike {

    /** How sure a match is: said exactly, sounds the same, or sounds close. */
    enum class How { EXACT, SAME, CLOSE }

    data class Match<T>(val item: T, val how: How)

    /**
     * The [items] [heard] could mean, best first: every exact match if there
     * is one, else every one that sounds the same, else every close one.
     */
    fun <T> find(heard: String, items: List<T>, names: (T) -> List<String>): List<Match<T>> {
        val said = words(heard)
        if (said.isEmpty()) return emptyList()
        val joined = said.joinToString("")
        val found = items.mapNotNull { item ->
            val how = names(item).mapNotNull { how(said, joined, it) }.minOrNull() ?: return@mapNotNull null
            Match(item, how)
        }
        val best = found.minOfOrNull { it.how } ?: return emptyList()
        return found.filter { it.how == best }
    }

    /** How [name] fits what was said, or null when it doesn't. */
    private fun how(said: List<String>, joined: String, name: String): How? {
        val parts = words(name)
        if (parts.isEmpty()) return null
        // The whole name, the first name, or a handle said as words ("tal moss" for talmoss).
        val wholes = listOf(parts.joinToString(""), parts.first())
        if (wholes.any { it == joined }) return How.EXACT
        val heardKey = key(joined)
        if (heardKey.length < 2) return null
        if (wholes.any { key(it) == heardKey }) return How.SAME
        if (heardKey.length >= 3 && wholes.any { distance(key(it), heardKey) <= 1 && distance(it, joined) <= 2 }) return How.CLOSE
        return null
    }

    private fun words(text: String) =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it != "my" }

    /**
     * A word as its sounds: the first letter, then its consonants, with
     * letters that sound alike made one.
     */
    fun key(word: String): String {
        var w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        for ((from, to) in SPELLINGS) w = w.replace(from, to)
        val out = StringBuilder()
        w.forEachIndexed { i, c ->
            val vowel = c in VOWELS
            val r = c == 'r' && i > 0 && w[i - 1] in VOWELS && (i + 1 == w.length || w[i + 1] !in VOWELS)
            val keep = when {
                i == 0 -> true
                vowel || r -> false
                c == 'h' || c == 'w' -> false
                else -> true
            }
            val sound = if (i == 0 && vowel) 'a' else c
            if (keep && out.lastOrNull() != sound) out.append(sound)
        }
        return out.toString()
    }

    /** Edit distance. */
    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            cur[0] = i + 1
            for (j in b.indices) {
                cur[j + 1] = minOf(prev[j + 1] + 1, cur[j] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')

    // Spellings of one sound, longest first.
    private val SPELLINGS = listOf(
        "tch" to "ch", "sch" to "sk", "ck" to "k", "ph" to "f", "gh" to "g", "kn" to "n",
        "wr" to "r", "qu" to "kw", "q" to "k", "x" to "ks", "c" to "k", "z" to "s", "v" to "f",
        "dg" to "j", "d" to "t", "b" to "p", "g" to "k"
    )
}
