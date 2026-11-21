// app/src/main/java/com/domain/app/voice/Day.kt
package com.domain.app.voice

/**
 * The rest of a day, as said (docs/anthemos-2.md, "A day in detail"):
 * meetings, people, medication, symptoms, mood, work, chores, what was
 * watched. Each kind says what detail it wants, read from the words where
 * said, and the questions asked after what's missing, at most two.
 */
object Day {

    /** A question asked after a [kind]'s missing [detail]. */
    data class Ask(val detail: String, val question: String, val only: (Item) -> Boolean = { true })

    private class Kind(
        val kind: String,
        val pattern: Regex,
        /** Read the item from a clause the pattern matched. */
        val read: (clause: String, m: MatchResult) -> Item,
        /** Asked in order, each only when its detail is missing. */
        val asks: List<Ask> = emptyList(),
        /** The clause is this and nothing else (no food or drink in it). */
        val whole: Boolean = true
    )

    /** What the clause holds of a day beyond food, drink and moving. [whole] is true when nothing else should be read from it. */
    fun read(raw: String): Pair<List<Item>, Boolean> {
        val clause = raw.replace("-", " ")
        val found = mutableListOf<Item>()
        var whole = false
        for (k in KINDS) {
            val m = k.pattern.find(clause) ?: continue
            // A reading can turn the match down ("saw Sam" is a person, not something watched).
            val item = k.read(clause, m).takeIf { it.kind != SKIP } ?: continue
            found += item
            whole = whole || k.whole
            if (k.whole) break
        }
        return found to whole
    }

    /** The first question still open for [item], past those already asked. */
    fun nextAsk(item: Item): Ask? {
        if (item.details["moment"] != null) return null
        val kind = KINDS.firstOrNull { it.kind == item.kind && (it.asks.isNotEmpty()) && matchesLabel(it, item) } ?: return null
        return kind.asks.firstOrNull { missing(item, it.detail) && it.only(item) }
    }

    private fun matchesLabel(kind: Kind, item: Item) = when (item.kind) {
        // Two kinds share "activity": sports ask who with; the gym what was worked on.
        "activity" -> (kind.asks.any { it.detail == "who" } && item.group == "sport") ||
            (kind.asks.any { it.detail == "what" } && item.label in setOf("gym", "workout"))
        else -> true
    }

    private fun missing(item: Item, detail: String) = when (detail) {
        "minutes" -> item.minutes == null
        "amount" -> item.amount == null
        else -> item.details[detail].isNullOrBlank()
    }

    /** Reads an answer to [detail]; null when it doesn't hold one. */
    fun fill(item: Item, detail: String, answer: String, reader: RuleReader): Item? {
        val a = answer.lowercase().trim().trimEnd('.', '!', '?')
        if (a.isEmpty()) return null
        return when (detail) {
            "minutes" -> reader.duration(a, bareIsHours = false)?.let { item.copy(minutes = it) }
            "amount" -> reader.money(a)?.let { item.copy(amount = it.amount) }
            "score" -> score(a)?.let { item.with("score", "$it/10") }
            "flow" -> Regex("\\b(light|medium|heavy|normal|spotting)\\b").find(a)?.let { item.with("flow", it.value) }
            "km" -> Regex("(\\d+(?:\\.\\d+)?)|\\b(${Numbers.PATTERN})\\b").find(a)?.let { m ->
                val n = m.groupValues[1].toDoubleOrNull() ?: Numbers.parse(m.groupValues[2])?.toDouble() ?: return null
                item.with("km", if (n % 1.0 == 0.0) n.toInt().toString() else n.toString())
            }
            else -> item.with(detail, a.replace(LEAD, "").trim().ifEmpty { return null })
        }
    }

    private fun Item.with(key: String, value: String) = copy(details = details + (key to value))

    /** "about a six", "7 out of 10", "zero": the first number that isn't "a". */
    private fun score(a: String): Int? = Regex("(\\d+)|\\b(${Numbers.PATTERN})\\b").findAll(a)
        .filter { it.value != "a" && it.value != "an" }
        .firstNotNullOfOrNull { it.groupValues[1].toIntOrNull() ?: Numbers.parse(it.groupValues[2]) }
        ?.takeIf { it in 0..10 }

    // ---- reading helpers

    private fun minutes(clause: String): Int? {
        Regex("\\b(${Numbers.PATTERN}) (?:minutes?|mins?)\\b").find(clause)?.let { return Numbers.parse(it.groupValues[1]) }
        if (clause.contains("half an hour")) return 30
        if (Regex("\\b(?:an |one )?hour and a half\\b").containsMatchIn(clause)) return 90
        Regex("\\b(${Numbers.PATTERN}|an|a) hours?\\b").find(clause)?.let { m ->
            val n = if (m.groupValues[1] == "a" || m.groupValues[1] == "an") 1 else Numbers.parse(m.groupValues[1]) ?: return null
            return n * 60
        }
        return null
    }

    /** "with Sam and Jo", up to where the rest of the sentence starts. */
    private fun after(word: String, clause: String): String? =
        Regex("\\b$word (.+?)(?= (?:about|for|at|on|in|today|tonight|this|yesterday|earlier|this morning|for an?|from)\\b|$)").find(clause)
            ?.groupValues?.get(1)?.trim()?.replace(Regex("^(?:the|my|a|an)\\s+"), "")?.takeIf { it.isNotEmpty() && it !in VAGUE }

    private fun about(clause: String) = Regex("\\babout (.+)$").find(clause)?.groupValues?.get(1)?.trim()

    private fun scoreIn(clause: String) = Regex("\\b(\\d+|${Numbers.PATTERN}) out of (?:10|ten)\\b").find(clause)
        ?.let { it.groupValues[1].toIntOrNull() ?: Numbers.parse(it.groupValues[1]) }?.takeIf { it in 0..10 }?.let { "$it/10" }

    private fun details(vararg pairs: Pair<String, String?>) = pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()

    private const val SKIP = "skip"
    private val MEALS = setOf("breakfast", "brunch", "lunch", "dinner", "tea", "supper", "dessert", "a meal", "my meal", "my lunch", "my dinner", "my breakfast")
    private val VAGUE = setOf("someone", "somebody", "a friend", "friend", "friends", "some friends", "mates", "a mate", "people", "them", "him", "her", "it", "that", "this")
    private val LEAD = Regex("^(?:(?:it was|it's|its|with|about|on|just|i took|we talked about|i think|um|uh|yeah|yes|so|a|the)\\s+)+")

    private const val SPORTS = "tennis|football|soccer|basketball|netball|golf|squash|cricket|volleyball|badminton|frisbee|footy|rugby|hockey|pickleball|padel|table tennis|ping pong|bowls|climbing|bouldering"
    private const val SYMPTOMS = "headache|migraine|back pain|stomach ache|stomachache|tummy ache|toothache|cramps|nausea|nauseous|a cold|the flu|flu|fever|dizzy|sore throat|sore back|sore neck|sore knee|sore head|feeling sick|feel sick|felt sick|vomited|threw up|period pain|pain|ache|aching|sore"
    private const val MOODS = "good|great|happy|sad|flat|low|down|anxious|stressed|angry|calm|content|lonely|excited|grumpy|irritable|overwhelmed|okay|ok|fine|meh|off|peaceful|frustrated|upset|hopeful|grateful|blue|depressed|joyful|chill|relaxed"
    private const val ENERGY = "tired|exhausted|knackered|wrecked|drained|wired|energetic|energised|energized|sleepy|fatigued|no energy|low energy|lots of energy|full of energy|buzzing"

    private val KINDS: List<Kind> = listOf(
        Kind("intimacy", Regex("\\b(?:had sex|made love|sex)\\b"), { _, _ -> Item("intimacy", "sex") }),
        Kind("dream", Regex("\\b(?:dreamt|dreamed|had a dream|a dream about|weird dream|nightmare)\\b"), { c, _ ->
            Item("dream", if (c.contains("nightmare")) "nightmare" else "dream", details = details("about" to about(c)))
        }),
        Kind("gratitude", Regex("\\b(?:grateful|thankful) for (.+)$"), { _, m -> Item("gratitude", m.groupValues[1].trim()) }),
        Kind("period", Regex("\\b(?:period|my period|got my period|on my period)\\b(?! pain)"), { c, _ ->
            val label = when {
                Regex("\\b(?:ended|finished|stopped|over|done)\\b").containsMatchIn(c) -> "period ended"
                Regex("\\b(?:started|got|came|began|day one|first day)\\b").containsMatchIn(c) -> "period started"
                else -> "period"
            }
            Item("period", label, details = details("flow" to Regex("\\b(light|medium|heavy|spotting)\\b").find(c)?.value))
        }, asks = listOf(Ask("flow", "Light, medium or heavy?"))),
        Kind("medication", Regex("\\b(?:took|taken|take|had|taking|popped) (?:my |a |an |some |two |2 |one )?((?:meds|medication|medicine|pills?|tablets?|vitamins?|paracetamol|panadol|ibuprofen|nurofen|aspirin|antihistamines?|antibiotics?|melatonin|magnesium|iron|zinc|vitamin [a-z0-9]+|fish oil|codeine|inhaler|puffer|insulin)|\\w+ \\d+ ?mg)\\b"), { c, m ->
            val what = m.groupValues[1]
            val generic = what in setOf("meds", "medication", "medicine", "pill", "pills", "tablet", "tablets")
            Item("medication", if (generic) "medication" else what, group = "meds", details = details(
                "what" to if (generic) null else what,
                "dose" to Regex("\\b(\\d+ ?(?:mg|ml|mcg|g|iu)|(?:${Numbers.PATTERN}) (?:pills?|tablets?|capsules?))\\b").find(c)?.value
            ))
        }, asks = listOf(Ask("what", "What did you take?"), Ask("dose", "How much?"))),
        Kind("symptom", Regex("\\b(?:$SYMPTOMS)\\b"), { c, m ->
            val where = Regex("\\b(?:pain|ache|aching|sore) (?:in )?(?:my )?(back|neck|knee|head|stomach|tummy|shoulder|hip|foot|feet|leg|arm|wrist|hand|chest|throat|jaw|ankle)\\b").find(c)?.groupValues?.get(1)
                ?: Regex("\\bmy (back|neck|knee|head|stomach|tummy|shoulder|hip|foot|leg|arm|wrist|hand|chest|throat|jaw|ankle) (?:hurts|is sore|aches|is aching)\\b").find(c)?.groupValues?.get(1)
            val label = when (m.value) {
                "pain", "ache", "aching", "sore" -> where?.let { "sore $it" } ?: "pain"
                "feeling sick", "feel sick", "felt sick", "nauseous" -> "nausea"
                "a cold" -> "cold"
                "the flu" -> "flu"
                else -> m.value
            }
            Item("symptom", label, details = details("score" to scoreIn(c), "where" to where))
        }, asks = listOf(Ask("score", "How bad, out of ten?"), Ask("where", "Where is it?") { it.label == "pain" })),
        Kind("weight", Regex("\\b(?:weigh|weighed|weight|weighing)\\b.*?\\b(\\d+(?:\\.\\d+)?) ?(kg|kilos?|kilograms?|pounds|lbs?|stone)?\\b"), { _, m ->
            Item("weight", "weight", details = details("value" to m.groupValues[1] + " " + m.groupValues[2].ifEmpty { "kg" }.let { if (it.startsWith("kilo")) "kg" else it }))
        }),
        Kind("energy", Regex("\\b(?:feeling |felt |feel |i'm |im |so |really |pretty |very |quite )?($ENERGY)\\b"), { c, m ->
            Item("energy", m.groupValues[1], details = details("score" to scoreIn(c)))
        }, asks = listOf(Ask("score", "Energy out of ten?"))),
        Kind("mood", Regex("\\b(?:feeling|felt|feel|i'm feeling|in a) ($MOODS)(?: mood)?\\b|\\b(good|great|bad|rough|tough|hard|shit|lovely|beautiful|amazing|awful|terrible|horrible|long|productive|lazy) day\\b|\\b(stressed|anxious|overwhelmed|depressed)\\b"), { c, m ->
            val label = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.let { if (m.groupValues[2].isNotEmpty()) "$it day" else it } ?: "mood"
            Item("mood", label, details = details("score" to scoreIn(c), "why" to Regex("\\b(?:because|cos|cause|since) (.+)$").find(c)?.groupValues?.get(1)))
        }, asks = listOf(Ask("score", "Out of ten?"), Ask("why", "Anything behind it?"))),
        Kind("meeting", Regex("\\b(?:meeting|meetings|standup|stand up|one on one|1 on 1|zoom call|team call)\\b"), { c, _ ->
            Item("meeting", "meeting", minutes = minutes(c), details = details("who" to after("with", c), "about" to about(c)))
        }, asks = listOf(Ask("who", "Who was it with?"), Ask("about", "What was it about?"))),
        Kind("call", Regex("\\b(?:(?:talked|spoke|chatted|caught up) (?:to|with) (.+?) on the phone|(?:a |had a )?(?:phone )?call with (.+?)(?= for| about| today|$)|(?:called|rang|phoned|facetimed) (.+?)(?= for| about| today| this| earlier|$)|on the phone (?:to|with) (.+?)(?= for| about| today|$)|(?:a |had a )phone call)"), { c, m ->
            val who = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim()?.replace(Regex("^(?:my|the)\\s+"), "")?.takeIf { it !in VAGUE }
            Item("call", who ?: "someone", minutes = minutes(c), said = who, details = details("who" to who, "about" to about(c)))
        }, asks = listOf(Ask("who", "Who was it with?"), Ask("about", "Anything to remember?"))),
        Kind("media", Regex("\\b(?:watched|watching|saw|listened to|listening to|binged|streamed) (?:a |an |the |some |another )?(?:(movie|film|show|episode|episodes|series|documentary|doco|tv|telly|netflix|youtube|podcast|album|audiobook|the footy|the game|a play|play)|([a-z][a-z ]+?))(?= for| with| tonight| today| this| last| earlier|$)"), { c, m ->
            val generic = m.groupValues[1]
            val named = m.groupValues[2].trim().takeIf { it.isNotEmpty() }
            // "saw Sam" is a person, read below.
            if (named != null && Regex("^(?:saw|met)\\b").containsMatchIn(m.value)) Item(SKIP, "")
            else Item("media", named ?: generic, minutes = minutes(c), details = details("what" to named))
        }, asks = listOf(Ask("what", "What was it?"))),
        Kind("work", Regex("\\b(?:worked|working|work) (?:on|for|at)\\b|\\b(?:started|finished|start|finish|knocked off|clocked (?:on|off)) work\\b|\\bworked (?:from home|today|late|all day)\\b|\\bat work\\b|\\bworked\\b(?! out)"), { c, _ ->
            val moment = Regex("\\b(started|finished|start|finish|knocked off|clocked on|clocked off) work\\b").find(c)?.value
            Item("work", moment ?: "work", minutes = minutes(c), details = details("on" to after("on", c), "moment" to moment?.let { "yes" }))
        }, asks = listOf(Ask("minutes", "How long for?"), Ask("on", "On what?"))),
        Kind("task", Regex("\\b(?:finished|completed|submitted|sent off|wrapped up|ticked off|got done|knocked over|lodged|paid) (?:the |my |our )?(report|essay|assignment|task|project|application|tax|taxes|tax return|invoice|invoices|emails|proposal|draft|bills?|thesis|chapter|presentation|slides|paperwork|forms?)\\b"), { _, m ->
            Item("task", m.value.replace(Regex("^(\\w+) (?:the |my |our )?"), "$1 "))
        }),
        Kind("study", Regex("\\b(?:studied|studying|revised|revising|read|reading|finished reading|practised|practiced|practicing|practising|learned|learnt|lesson)\\b(?! the news)"), { c, _ ->
            val what = Regex("\\b(?:studied|studying|revised|revising|read|reading|finished reading|practised|practiced|practicing|practising|learned|learnt|a lesson in|lesson) (?:a |an |the |some |my )?(.+?)(?= for| with| today| tonight| this| earlier|$)").find(c)?.groupValues?.get(1)?.trim()
            val reading = Regex("\\b(?:read|reading|finished reading)\\b").containsMatchIn(c)
            Item("study", if (reading) "reading" else "study", minutes = minutes(c), details = details("what" to what?.takeIf { it !in setOf("book", "bit", "while", "little") }))
        }, asks = listOf(Ask("what", "What was it?"), Ask("minutes", "How long for?"))),
        Kind("cooking", Regex("\\b(?:cooked|baked|made (?:dinner|lunch|breakfast|a meal|tea|brunch|a cake|bread)|meal prepped|meal prep)\\b"), { c, _ ->
            val what = Regex("\\b(?:cooked|baked|made) (?:a |an |some |the )?(.+?)(?= for| with| today| tonight|$)").find(c)?.groupValues?.get(1)?.trim()
                ?.takeIf { it !in setOf("dinner", "lunch", "breakfast", "a meal", "meal", "tea", "brunch") }
            Item("cooking", "cooked", details = details("what" to what))
        }, asks = listOf(Ask("what", "What did you make?")), whole = false),
        Kind("shopping", Regex("\\b(?:groceries|grocery shop|grocery shopping|food shop|food shopping|did the shopping|did the shop|supermarket|went to (?:coles|woolies|woolworths|aldi|the market|the shops))\\b"), { _, _ ->
            Item("shopping", "groceries")
        }, asks = listOf(Ask("amount", "How much did it come to?")), whole = false),
        Kind("chore", Regex("\\b(?:cleaned|cleaning|vacuumed|vacuuming|hoovered|mopped|tidied|tidying|dusted|did the (?:laundry|washing|dishes|ironing|vacuuming|cleaning)|washing up|did the washing up|hung out the washing|folded (?:the )?(?:washing|laundry)|took (?:the )?bins out|put (?:the )?bins out|mowed|weeded|watered (?:the )?(?:plants|garden)|fed (?:the )?(?:cat|dog|fish|chooks|chickens|pets?)|changed the sheets|made the bed|gardening|did some gardening)\\b"), { _, m ->
            Item("chore", m.value)
        }, whole = false),
        Kind("outside", Regex("\\b(?:time outside|went outside|sat outside|been outside|in the sun|sunbathed|sat in the garden|in the garden|got some sun|fresh air)\\b"), { c, _ ->
            Item("outside", "outside", minutes = minutes(c))
        }, asks = listOf(Ask("minutes", "How long for?")), whole = false),
        Kind("activity", Regex("\\b(?:played|playing|had a game of|a game of|a round of) (?:some )?($SPORTS)\\b"), { c, m ->
            Item("activity", m.groupValues[1], group = "sport", minutes = minutes(c), details = details("who" to after("with", c)))
        }, asks = listOf(Ask("minutes", "How long for?"), Ask("who", "Who with?"))),
        Kind("social", Regex("\\b(?:saw|met|met up with|caught up with|catch up with|hung out with|visited|spent time with|had (?:coffee|lunch|dinner|drinks|brunch|breakfast|a drink|a coffee|a beer|a wine) with|went out with) (?:my |a |the |some )?([a-z][a-z ]*?)(?= for| at| over| today| tonight| this| earlier| yesterday| and| about|$)"), { c, m ->
            val who = m.groupValues[1].trim().takeIf { it !in VAGUE && it.isNotEmpty() }
            val vague = m.groupValues[1].trim()
            // "A coffee with lunch" is a meal, not a person.
            if (vague in MEALS) Item(SKIP, "")
            else Item("social", "saw ${who ?: vague.ifEmpty { "someone" }.let { if (it == "friend" || it == "mate") "a $it" else it }}", details = details("who" to who))
        }, asks = listOf(Ask("who", "Who was it?"), Ask("how", "How was it?")), whole = false),
        // Read as an activity already; here only for its questions.
        Kind("activity", Regex("\\b(?:gym|workout|worked out)\\b"), { _, _ -> Item(SKIP, "") },
            asks = listOf(Ask("minutes", "How long for?"), Ask("what", "What did you work on?")), whole = false)
    )
}
