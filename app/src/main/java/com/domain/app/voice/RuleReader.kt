// app/src/main/java/com/domain/app/voice/RuleReader.kt
package com.domain.app.voice

import java.time.LocalTime

/**
 * The first reader: finds the things in a sentence by rules, several per
 * sentence ("a chicken sandwich for lunch, a salad and a coffee" is three
 * items). Anything it can't place is simply not an item; the words are kept
 * in the entry either way, so a better reader (on the person's own server,
 * later) can find more.
 */
class RuleReader(private val now: () -> LocalTime = { LocalTime.now() }) {

    fun read(words: String): List<Item> {
        val text = clean(words)
        val meal = MEALS.entries.firstOrNull { (pattern, _) -> Regex("\\b($pattern)\\b").containsMatchIn(text) }?.value
        // A sentence about a meal, or eating, makes plain nouns food.
        var food = meal != null || EAT.containsMatchIn(text)

        val items = mutableListOf<Item>()
        for (clause in clauses(text)) {
            val activity = activity(clause)
            if (activity != null) {
                items += activity
                continue
            }
            val drinks = drinks(clause)
            if (drinks.isNotEmpty()) {
                items += drinks
                continue
            }
            val label = foodLabel(clause)
            val isFood = label != null && (food || HAD.containsMatchIn(clause) && FOODS.containsMatchIn(clause) ||
                FOODS.containsMatchIn(clause) && !VERB_START.containsMatchIn(clause))
            if (isFood && label != null) {
                items += Item("food", label, meal = meal ?: mealForTime(now()))
                food = true
            }
        }
        // "I just had lunch": a meal with nothing else said.
        if (meal != null && items.none { it.kind == "food" }) {
            items.add(0, Item("food", meal, meal = meal))
        }
        return items
    }

    /** "Logged chicken sandwich, salad and coffee", or "Noted" when nothing was found. */
    fun reply(items: List<Item>): String {
        if (items.isEmpty()) return "Noted"
        val names = items.map { item ->
            when {
                item.kind == "activity" && item.minutes != null -> "${item.label}, ${item.minutes} minutes"
                item.ml != null && item.label == "water" -> "${item.ml} ml of water"
                item.count > 1 -> "${item.count} ${item.label}"
                else -> item.label
            }
        }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "Logged $list"
    }

    private fun clean(words: String): String =
        words.lowercase()
            .replace(Regex("[.!?]"), " ")
            .replace(Regex("\\b(um+|uh+|er+|like|you know)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun clauses(text: String): List<String> =
        text.split(Regex(",|;|\\band then\\b|\\band\\b|\\balso\\b|\\bthen\\b|\\bplus\\b|\\bas well as\\b|\\bafter that\\b|\\bfollowed by\\b"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun activity(clause: String): Item? {
        val label = ACTIVITIES.entries.firstOrNull { (pattern, _) -> Regex("\\b($pattern)\\b").containsMatchIn(clause) }?.value
            ?: return null
        return Item("activity", label, minutes = minutes(clause))
    }

    private fun minutes(clause: String): Int? {
        Regex("\\b(${Numbers.PATTERN})\\s+(minutes?|mins?)\\b").find(clause)?.let { return Numbers.parse(it.groupValues[1]) }
        if (clause.contains("half an hour")) return 30
        if (clause.contains("hour and a half")) return 90
        Regex("\\b(${Numbers.PATTERN})\\s+hours?\\b").find(clause)?.let { m -> Numbers.parse(m.groupValues[1])?.let { return it * 60 } }
        return null
    }

    private fun drinks(clause: String): List<Item> {
        val found = mutableListOf<Item>()
        // "pasta for tea" is a meal, not a drink.
        var rest = clause.replace(Regex("\\bfor tea\\b"), " ")
        for ((label, group) in DRINKS) {
            val m = Regex("\\b(?:(${Numbers.PATTERN})\\s+(?:\\w+\\s+)?)?(?:(?:cups?|glass(?:es)?|bottles?|mugs?|cans?|pints?|shots?)\\s+of\\s+)?${Regex.escape(label)}(?:e?s)?\\b").find(rest)
                ?: continue
            val count = m.groupValues[1].takeIf { it.isNotEmpty() }?.let(Numbers::parse) ?: 1
            val ml = if (label == "water") waterMl(m.value, count) else null
            found += Item("drink", label, count = count, group = group, ml = ml)
            // So "green tea" isn't also read as "tea".
            rest = rest.replaceRange(m.range, " ")
        }
        return found
    }

    private fun waterMl(phrase: String, count: Int): Int {
        Regex("(\\d+)\\s*(ml|millilit)").find(phrase)?.let { return it.groupValues[1].toInt() }
        Regex("(\\d+(?:\\.\\d+)?)\\s*lit").find(phrase)?.let { return (it.groupValues[1].toDouble() * 1000).toInt() }
        return count * if (phrase.contains("bottle")) 500 else 250
    }

    /** "i had a chicken sandwich for lunch" -> "chicken sandwich". */
    private fun foodLabel(clause: String): String? {
        val label = clause
            .replace(Regex("\\bfor (breakfast|brunch|lunch|dinner|tea|supper|a snack|snack|dessert)\\b"), " ")
            .replace(Regex("\\b(today|tonight|this morning|this afternoon|this evening|just now|earlier)\\b"), " ")
            .replace(Regex("^(?:(?:and|so|then|i|i've|ive|we|also|just|have|has|had|ate|eaten|eat|eating|having|got|grabbed|made|cooked|was|some|a|an|the|my|for|with|bit of|piece of|bowl of|plate of)\\s+)+"), "")
            .replace(Regex("\\s+(too|as well)$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (label.length < 2 || MEALS.values.any { it == label } || label in NOT_FOOD) return null
        return label
    }

    private fun mealForTime(time: LocalTime): String = when {
        time.isBefore(LocalTime.of(10, 30)) -> "breakfast"
        time.isBefore(LocalTime.of(15, 0)) -> "lunch"
        time.isBefore(LocalTime.of(17, 0)) -> "snack"
        else -> "dinner"
    }

    companion object {
        const val NAME = "rules-1"

        private val EAT = Regex("\\b(ate|eaten|eat|eating|snacked)\\b")
        private val HAD = Regex("\\b(had|have had|having|got|grabbed)\\b")
        private val VERB_START = Regex("^(i|we|it|went|was|is|did|made|met|saw|called)\\b")
        private val NOT_FOOD = setOf("i", "we", "me", "you", "too", "as well", "it", "that", "this", "something", "nothing", "a good day", "a nap", "a meeting", "a shower")

        // Pattern to meal name.
        private val MEALS = linkedMapOf(
            "breakfast" to "breakfast", "brunch" to "brunch", "lunch" to "lunch", "dinner" to "dinner",
            "supper" to "dinner", "for tea" to "dinner", "snack|snacked" to "snack", "dessert" to "dessert"
        )

        // Label to group; longer names first so "flat white" wins over "white".
        private val DRINKS = linkedMapOf(
            "flat white" to "coffee", "long black" to "coffee", "short black" to "coffee", "iced coffee" to "coffee",
            "espresso" to "coffee", "latte" to "coffee", "cappuccino" to "coffee", "mocha" to "coffee",
            "macchiato" to "coffee", "americano" to "coffee", "coffee" to "coffee",
            "green tea" to "tea", "herbal tea" to "tea", "chai" to "tea", "matcha" to "tea", "tea" to "tea",
            "water" to "water", "sparkling water" to "water",
            "beer" to "alcohol", "wine" to "alcohol", "red wine" to "alcohol", "white wine" to "alcohol",
            "cider" to "alcohol", "cocktail" to "alcohol", "whisky" to "alcohol", "whiskey" to "alcohol",
            "vodka" to "alcohol", "gin" to "alcohol", "rum" to "alcohol", "champagne" to "alcohol",
            "juice" to "juice", "orange juice" to "juice", "smoothie" to "smoothie", "milk" to "milk",
            "kombucha" to "kombucha", "hot chocolate" to "hot chocolate", "energy drink" to "energy drink",
            "soft drink" to "soft drink", "coke" to "soft drink"
        ).entries.sortedByDescending { it.key.length }.associate { it.key to it.value }

        // Pattern to activity label.
        private val ACTIVITIES = linkedMapOf(
            "walk|walked|walking" to "walk",
            "run|ran|running|jog|jogged|jogging" to "run",
            "swim|swam|swimming" to "swim",
            "cycle|cycled|cycling|bike ride|rode my bike" to "ride",
            "yoga" to "yoga",
            "gym" to "gym",
            "workout|worked out|work out" to "workout",
            "hike|hiked|hiking" to "hike",
            "dance|danced|dancing" to "dance",
            "surf|surfed|surfing" to "surf",
            "meditate|meditated|meditation|meditating" to "meditation",
            "nap|napped" to "nap",
            "slept|sleep" to "sleep"
        )

        // Common foods, so "I had a salad" counts without other context.
        private val FOODS = Regex(
            "\\b(salad|sandwich|toast|eggs?|rice|pasta|soup|chicken|fish|fruit|apple|banana|orange|cake|biscuits?|" +
                "chocolate|pizza|burger|noodles|curry|cereal|porridge|oats|yoghurt|yogurt|steak|beef|pork|lamb|tofu|" +
                "bread|cheese|crisps|chips|nuts|muffin|pie|sushi|wrap|taco|burrito|stew|veggies|vegetables|" +
                "avocado|bacon|pancakes?|croissant|bagel|dumplings|ramen|lasagne|risotto|salmon|tuna|beans|potato(es)?)\\b"
        )
    }
}
