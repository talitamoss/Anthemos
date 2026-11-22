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

    // ---- More of a life (docs/anthemos-2.md, "Kinds"): read before the kinds below, most particular first.

    /** "58", "seventy two", "one twenty", "a hundred", "36.8", "thirty six point eight", "8k", "ten thousand". */
    internal fun amount(said: String): Double? {
        val s = said.trim().replace(",", "")
        s.toDoubleOrNull()?.let { return it }
        Regex("^(.+?) ?(?:k|thousand)$").find(s)?.let { m -> return amount(m.groupValues[1])?.times(1000) }
        Regex("^(.+?) point (\\w+)$").find(s)?.let { m ->
            val whole = amount(m.groupValues[1]) ?: return null
            val tenth = Numbers.parse(m.groupValues[2]) ?: return null
            return whole + tenth / 10.0
        }
        Regex("^(?:one|a) hundred(?: and)?(?: (.+))?$").find(s)?.let { m ->
            return 100.0 + (m.groupValues[1].takeIf { it.isNotEmpty() }?.let { Numbers.parse(it) } ?: 0)
        }
        Regex("^one ((?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?: \\w+)?)$").find(s)?.let { m ->
            return Numbers.parse(m.groupValues[1])?.let { 100.0 + it }
        }
        return Numbers.parse(s)?.toDouble()
    }

    private fun show(n: Double) = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    private val NUM = "\\d+(?:\\.\\d+)?|(?:one|a) hundred(?: and)?(?: [a-z]+)?|one (?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?: [a-z]+)?|(?:${Numbers.PATTERN})(?: point [a-z]+)?"
    private const val NOT = "\\b(?:no|not|didn't|didnt|did not|haven't|havent|without|zero|free)\\b"
    private const val QUIT = "\\b(?:quit|stopped|gave up|giving up|quitting)\\b"
    private const val BODY_PARTS = "ankle|knee|wrist|back|finger|thumb|hand|foot|toe|shoulder|neck|hamstring|calf|arm|leg|hip|elbow|head|lip|eye|rib|ribs"

    private val NEW_KINDS: List<Kind> = listOf(
        // Measures: heart rate, blood pressure, temperature, blood sugar, oxygen, waist.
        Kind("vital", Regex("\\b(?:resting heart rate|heart rate|pulse|bpm|blood pressure|bp|temperature|temp|blood sugar|glucose|bgl|oxygen|spo2|sats|waist)\\b"), { c, _ ->
            fun after(words: String) = Regex("\\b(?:$words)\\b(?: (?:was|is|of|at|reading))*:? ?($NUM)").find(c)?.groupValues?.get(1)?.let(::amount)
            val pressure = Regex("\\b(?:blood pressure|bp)\\b(?: (?:was|is|of|at|reading))*:? ?($NUM) ?(?:over|/) ?($NUM)").find(c)
            when {
                pressure != null -> {
                    val hi = amount(pressure.groupValues[1])
                    val lo = amount(pressure.groupValues[2])
                    if (hi == null || lo == null) Item(SKIP, "") else Item("vital", "blood pressure", details = mapOf("value" to "${show(hi)}/${show(lo)}"))
                }
                Regex("\\b(?:resting heart rate|heart rate|pulse|bpm)\\b").containsMatchIn(c) ->
                    (after("resting heart rate|heart rate|pulse") ?: Regex("($NUM) ?bpm").find(c)?.groupValues?.get(1)?.let(::amount))
                        ?.let { Item("vital", if (c.contains("resting")) "resting heart rate" else "heart rate", details = mapOf("value" to show(it))) } ?: Item(SKIP, "")
                Regex("\\b(?:temperature|temp)\\b").containsMatchIn(c) ->
                    after("temperature|temp")?.let { Item("vital", "temperature", details = mapOf("value" to show(it))) } ?: Item(SKIP, "")
                Regex("\\b(?:blood sugar|glucose|bgl)\\b").containsMatchIn(c) ->
                    after("blood sugar|glucose|bgl")?.let { Item("vital", "blood sugar", details = mapOf("value" to show(it))) } ?: Item(SKIP, "")
                Regex("\\b(?:oxygen|spo2|sats)\\b").containsMatchIn(c) ->
                    after("oxygen|spo2|sats")?.let { Item("vital", "oxygen", details = mapOf("value" to show(it))) } ?: Item(SKIP, "")
                else -> after("waist")?.let { Item("vital", "waist", details = mapOf("value" to show(it))) } ?: Item(SKIP, "")
            }
        }),
        // "8000 steps", "ten thousand steps", "12k steps".
        Kind("steps", Regex("\\b($NUM|(?:$NUM) ?(?:k|thousand)) steps\\b"), { _, m ->
            amount(m.groupValues[1])?.let { Item("steps", "steps", count = it.toInt()) } ?: Item(SKIP, "")
        }),
        // Before smoking: "smoked a joint" is cannabis.
        Kind("cannabis", Regex("\\b(?:weed|joint|joints|cannabis|marijuana|bong|cones?|spliff|edibles?|got high|stoned)\\b"), { c, _ ->
            when {
                Regex(QUIT).containsMatchIn(c) -> Item("cannabis", "quit")
                Regex(NOT).containsMatchIn(c) -> Item("cannabis", "none", count = 0)
                else -> Item("cannabis", "cannabis")
            }
        }),
        // Smoking and vaping: a count, none today, or quitting.
        Kind("smoking", Regex("\\b(?:smoked|smoking|smoke|smokes|smoker|cigarettes?|ciggies?|ciggy|cigs?|durries?|darts|vaped|vaping|vape|vapes|nicotine|cigar)\\b(?! salmon| meat| ham| chicken| brisket| trout| paprika| alarm| detector)"), { c, _ ->
            val vape = Regex("\\b(?:vaped|vaping|vapes?)\\b").containsMatchIn(c)
            val what = if (vape) "vape" else "cigarette"
            val count = Regex("\\b(${Numbers.PATTERN}) (?:cigarettes?|ciggies?|cigs?|smokes|durries|darts|vapes)\\b").find(c)?.let { Numbers.parse(it.groupValues[1]) }
            when {
                Regex(QUIT).containsMatchIn(c) -> Item("smoking", "quit", details = mapOf("what" to what))
                Regex(NOT).containsMatchIn(c) -> Item("smoking", "none", count = 0, details = mapOf("what" to what))
                else -> Item("smoking", what, count = count ?: 1)
            }
        }),
        // A day without alcohol, said: a drink of none.
        Kind("drink", Regex("\\b(?:no (?:alcohol|drinks|booze|grog|beer|wine)|didn't drink|didnt drink|did not drink|stayed sober|sober|alcohol free|dry day|off the drink|off the booze)\\b"), { _, _ ->
            Item("drink", "no alcohol", count = 0, group = "alcohol", details = mapOf("none" to "yes"))
        }),
        Kind("craving", Regex("\\b(?:craving|cravings|craved|really wanted) (?:a |an |some |for )?(.+?)(?= today| tonight| all day| this|$)"), { _, m ->
            Item("craving", m.groupValues[1].trim())
        }),
        Kind("fasting", Regex("\\b(?:fasted|fasting|intermittent fasting|broke my fast)\\b"), { c, _ ->
            val hours = Regex("\\b($NUM) hours?\\b").find(c)?.groupValues?.get(1)?.let(::amount)?.toInt()
            Item("fasting", "fasting", minutes = hours?.times(60))
        }),
        Kind("injury", Regex("\\b(?:hurt|injured|sprained|twisted|rolled|cut|burnt|burned|bruised|broke|pulled|strained|jarred|stubbed) (?:my |a )?($BODY_PARTS|muscle)\\b"), { c, m ->
            Item("injury", m.value.replace(Regex("^(\\w+) (?:my |a )?"), "$1 "), details = details("where" to m.groupValues[1].takeIf { it != "muscle" }, "score" to scoreIn(c)))
        }, asks = listOf(Ask("score", "How bad, out of ten?"))),
        Kind("conflict", Regex("\\b(?:argued with|argument with|fought with|fight with|row with|fell out with|had words with) (?:my |the )?(.+?)(?= about| over| today| tonight| this|$)"), { c, m ->
            val who = m.groupValues[1].trim().takeIf { it !in VAGUE }
            Item("conflict", "argument", details = details("who" to who, "about" to (about(c) ?: Regex("\\bover (.+)$").find(c)?.groupValues?.get(1))))
        }, asks = listOf(Ask("about", "What was it about?"))),
        Kind("worry", Regex("\\b(?:worried|worrying|worry|worried sick) about (.+)$"), { _, m ->
            Item("worry", m.groupValues[1].trim())
        }),
        Kind("win", Regex("\\b(?:proud of (?:myself )?(?:for )?|a win today|small win|big win|nailed|smashed|achieved|accomplished|finally (?:did|finished|got))(.*)$"), { _, m ->
            Item("win", m.value.trim())
        }),
        Kind("idea", Regex("\\b(?:had an idea|an idea for|new idea|idea for|idea about|thought of an idea)(.*)$"), { _, m ->
            Item("idea", m.groupValues[1].trim().removePrefix(":").trim().replace(Regex("^(?:for|about|to) "), "").ifEmpty { "idea" })
        }),
        Kind("goal", Regex("\\b(?:my goal is|my goal for|set a goal|set myself a goal|new goal|i want to start|i want to stop|i want to quit)\\b ?(?:to |is )?(.*)$"), { c, m ->
            Item("goal", m.groupValues[1].trim().ifEmpty { c })
        }),
        Kind("journal", Regex("\\b(?:journaled|journalled|journaling|journalling|wrote in my (?:journal|diary)|morning pages|wrote my journal|did my journal)\\b"), { c, _ ->
            Item("journal", "journal", minutes = minutes(c))
        }),
        Kind("spirit", Regex("\\b(?:prayed|praying|prayer|prayers|went to (?:church|mass|mosque|temple|synagogue|shul|gurdwara)|at (?:church|mass|mosque|temple|synagogue))\\b"), { c, m ->
            Item("spirit", if (m.value.startsWith("pray")) "prayer" else m.value.substringAfterLast(' '), minutes = minutes(c))
        }),
        Kind("volunteer", Regex("\\b(?:volunteered|volunteering|volunteer shift|helped out at)\\b"), { c, _ ->
            Item("volunteer", "volunteering", minutes = minutes(c), details = details("at" to after("at", c)))
        }, asks = listOf(Ask("minutes", "How long for?"))),
        Kind("scrolling", Regex("\\b(?:scrolled|scrolling|doom ?scrolled|doom ?scrolling|on (?:instagram|insta|tiktok|facebook|reddit|twitter|social media)|(?:instagram|insta|tiktok|facebook|reddit|twitter|social media) for)\\b"), { c, _ ->
            val app = Regex("\\b(instagram|insta|tiktok|facebook|reddit|twitter|social media)\\b").find(c)?.value?.let { if (it == "insta") "instagram" else it }
            Item("scrolling", app ?: "scrolling", minutes = minutes(c))
        }, asks = listOf(Ask("minutes", "How long for?"))),
        // Making things: an instrument, drawing, writing a song. Before study, which takes "practised".
        Kind("creative", Regex("\\b(?:(?:played|playing|practised|practiced|practising|practicing) (?:the |my |some )?(guitar|piano|keys|drums|violin|bass|ukulele|uke|cello|saxophone|sax|flute|trumpet|harp|synth)|painted|painting|drew|drawing|sketched|sketching|wrote (?:a song|a poem|some music|music|lyrics|some poetry)|made (?:some )?music|knitted|knitting|crochet|crocheted|sewing|sewed|pottery|woodwork)\\b"), { c, m ->
            Item("creative", m.groupValues[1].ifEmpty { m.value.substringBefore(' ').let { w -> if (w == "wrote" || w == "made") m.value else w } }, minutes = minutes(c))
        }, asks = listOf(Ask("minutes", "How long for?"))),
        // Looking after yourself: yes-or-no things a day has or hasn't.
        Kind("care", Regex("\\b(?:flossed|flossing|brushed my teeth|skincare|skin care|cold shower|cold plunge|ice bath|sauna|massage|haircut|stretched|stretching|foam rolled|took a bath|had a bath|bubble bath|face mask|got my nails done)\\b"), { _, m ->
            val label = when (m.value) {
                "flossing" -> "flossed"
                "skin care" -> "skincare"
                "stretching" -> "stretched"
                "took a bath", "had a bath", "bubble bath" -> "bath"
                "ice bath" -> "cold plunge"
                else -> m.value
            }
            Item("care", label)
        }, whole = false),
        Kind("pet", Regex("\\b(?:walked the dog|took the dog (?:for a walk|out)|dog walk|(?:took|took the) (?:dog|cat) to the vet|vet|groomed (?:the )?(?:dog|cat)|played with the (?:dog|cat|puppy|kitten))\\b"), { _, m ->
            Item("pet", m.value)
        }, whole = false),
        Kind("travel", Regex("\\b(?:flew|flight|drove|took the (?:train|bus|tram|ferry)|caught the (?:train|bus|tram|ferry)|road trip|commuted|commute)\\b"), { c, m ->
            val how = when {
                m.value.startsWith("fl") -> "flight"
                m.value == "drove" || m.value == "road trip" -> "drive"
                m.value == "commuted" || m.value == "commute" -> "commute"
                else -> m.value.substringAfterLast(' ')
            }
            Item("travel", how, minutes = minutes(c), details = details("to" to Regex("\\bto ([a-z][a-z ]+?)(?= for| with| today| this|$)").find(c)?.groupValues?.get(1)))
        }, whole = false)
    )

    private val KINDS: List<Kind> = NEW_KINDS + listOf(
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
