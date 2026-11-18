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

    /**
     * [context] is what the words answer, from a prompt: "sleep", a meal
     * ("breakfast"), "water", or null for something said unprompted.
     */
    fun read(words: String, context: String? = null): List<Item> {
        val text = clean(words)
        if (context != null && NEGATIVE.containsMatchIn(text)) return emptyList()
        val spent = spend(text)
        // Money is read once, on its own, so it isn't also a food.
        val items = readText(text.replace(MONEY_WORDS, " ").replace(MONEY_SIGN, " "), context)
        spent?.let { items += it }
        when {
            context == "sleep" && items.none { it.kind == "sleep" } ->
                items.add(0, Item("sleep", sleepLabel(text), minutes = minutes(text)))
            context == "water" && items.none { it.group == "water" } -> {
                val count = Regex("\\b(${Numbers.PATTERN})\\s+(glass|glasses|bottles?|cups?)\\b").find(text)
                    ?.let { Numbers.parse(it.groupValues[1]) } ?: 1
                items += Item("drink", "water", count = count, group = "water", ml = waterMl(text, count))
            }
        }
        return items
    }

    private fun readText(text: String, context: String?): MutableList<Item> {
        val meal = context?.takeIf { it in MEALS.values }
            ?: MEALS.entries.firstOrNull { (pattern, _) -> Regex("\\b($pattern)\\b").containsMatchIn(text) }?.value
        // A sentence about a meal, or eating, makes plain nouns food.
        var food = meal != null || EAT.containsMatchIn(text)

        val items = mutableListOf<Item>()
        for (clause in clauses(text)) {
            // Getting up and going to bed: moments, not things to time.
            if (WAKE.containsMatchIn(clause)) {
                items += Item("wake", "woke up")
                continue
            }
            if (BED.containsMatchIn(clause)) {
                items += Item("bed", "went to bed")
                continue
            }
            // The toilet: a moment, said plainly or politely.
            if (POO.containsMatchIn(clause)) {
                items += Item("toilet", "poo")
                continue
            }
            if (WEE.containsMatchIn(clause)) {
                items += Item("toilet", "wee")
                continue
            }
            // Meetings, people, medication, mood, chores... (Day). Some are the whole clause.
            val (day, whole) = Day.read(clause)
            items += day
            if (whole) continue
            val activity = activity(clause)
            if (activity != null) {
                // The gym's own reading (Day) is only for its questions.
                if (items.none { it.kind == "activity" && it.label == activity.label }) items += activity
                continue
            }
            val drinks = drinks(clause)
            if (drinks.isNotEmpty()) {
                items += drinks
                continue
            }
            // With someone, or cooking: food only when a food is named ("met Sam for lunch" isn't a food).
            if (day.isNotEmpty() && !FOODS.containsMatchIn(clause)) continue
            val label = foodLabel(clause)
            val isFood = label != null && (food || HAD.containsMatchIn(clause) && FOODS.containsMatchIn(clause) ||
                FOODS.containsMatchIn(clause) && !VERB_START.containsMatchIn(clause))
            if (isFood && label != null) {
                items += Item("food", label, meal = meal ?: mealForTime(now()))
                food = true
            }
        }
        // "I just had lunch": a meal with nothing else said. Not "a coffee with lunch".
        val mealIsWhen = meal != null && items.isNotEmpty() && Regex("\\b(?:for|with|at|during|after|before)\\s+$meal\\b").containsMatchIn(text)
        // "Cooked dinner" asks what was made, not what was had.
        if (meal != null && context == null && items.none { it.kind == "food" || it.kind == "cooking" } && !mealIsWhen) {
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
                item.kind == "weight" -> "weight, ${item.details["value"]}"
                item.kind == "call" -> "call with ${item.label}" + (item.minutes?.let { ", $it minutes" } ?: "")
                item.kind != "sleep" && item.minutes != null -> "${item.label}, ${item.minutes} minutes"
                item.ml != null && item.label == "water" -> "${item.ml} ml of water"
                item.kind == "spend" -> item.label
                item.kind == "sleep" && item.minutes != null -> "sleep, ${hours(item.minutes)}"
                item.count > 1 -> "${item.count} ${item.label}"
                else -> item.label
            }
        }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "Logged $list"
    }

    /** How long, for a follow-up answer: "forty minutes", "six hours", or a bare "six". */
    fun duration(words: String, bareIsHours: Boolean): Int? {
        val text = clean(words)
        minutes(text)?.let { return it }
        val n = Regex("\\b(${Numbers.PATTERN})\\b").find(text)?.let { Numbers.parse(it.groupValues[1]) } ?: return null
        return if (bareIsHours) n * 60 else n
    }

    /** How much, for a follow-up answer: "twelve dollars", "$12.50", or a bare "twelve". */
    fun money(words: String): Item? {
        val text = clean(words)
        spend(text)?.let { return it }
        val n = Regex("\\b(${Numbers.PATTERN})\\b").find(text)?.let { Numbers.parse(it.groupValues[1]) } ?: return null
        return Item("spend", "$$n", amount = n.toDouble())
    }

    private fun hours(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60} hours" else "%.1f hours".format(minutes / 60.0)

    /** "twelve dollars fifty", "$12.50", "20 bucks" -> a spend item. */
    private fun spend(text: String): Item? {
        val m = MONEY_SIGN.find(text)
        val amount = m?.groupValues?.get(1)?.toDoubleOrNull() ?: run {
            val w = MONEY_WORDS.find(text) ?: return null
            val dollars = Numbers.parse(w.groupValues[1]) ?: return null
            dollars + (w.groupValues[2].takeIf { it.isNotEmpty() }?.let(Numbers::parse) ?: 0) / 100.0
        }
        val label = if (amount % 1.0 == 0.0) "$${amount.toInt()}" else "$" + "%.2f".format(amount)
        return Item("spend", label, amount = amount)
    }

    private fun sleepLabel(text: String): String =
        text.replace(Regex("\\b(?:for |about |around )?(${Numbers.PATTERN}|half an?) (?:and a half )?hours?\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(?:(?:i|it|was|slept|sleep|yeah|yes|um|pretty|really|quite|about|around|and)\\s+)*"), "")
            .split(" ").take(4).joinToString(" ").ifBlank { "sleep" }

    private fun clean(words: String): String =
        words.lowercase()
            .replace(Regex("(?<!\\d)\\.|\\.(?!\\d)|[!?]"), " ")
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
        val kind = if (label == "sleep" || label == "nap") "sleep" else "activity"
        return Item(kind, label, minutes = minutes(clause))
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
            .replace(Regex("^(?:(?:yes|yeah|yep|and|so|then|i|i've|ive|we|also|just|have|has|had|ate|eaten|eat|eating|having|got|grabbed|bought|made|cooked|was|some|a|an|the|my|for|with|bit of|piece of|bowl of|plate of)\\s+)+"), "")
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
        const val NAME = "rules-2"

        private val MONEY_SIGN = Regex("\\$\\s?(\\d+(?:\\.\\d{1,2})?)")
        private val MONEY_WORDS = Regex(
            "\\b(?:for\\s+)?(${Numbers.PATTERN})\\s+(?:dollars?|bucks)(?:\\s+(?:and\\s+)?(${Numbers.PATTERN})(?:\\s+cents)?)?\\b"
        )
        private val NEGATIVE = Regex("^(?:no|nope|nah|not yet|not really|nothing|skipped(?: it)?|didn't|haven't)\\b")

        private val WAKE = Regex("\\b(?:woke|woken|wake|waking|got|getting) up\\b|\\b(?:i'm|im|just) up\\b")
        private val BED = Regex("\\b(?:went|go|going|gone|got) to (?:bed|sleep)\\b|\\bfell asleep\\b|\\bhit the sack\\b")
        private val POO = Regex(
            "\\b(?:poo|poos|pooed|pooing|poop|pooped|pooping|number two|number 2|bowel movement|dump|shit|crap)\\b"
        )
        private val WEE = Regex("\\b(?:a wee|wee'd|weeing|pee|peed|peeing|a piss|pissed|number one|number 1|urinated)\\b")
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
