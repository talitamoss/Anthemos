// app/src/main/java/com/domain/app/voice/Commands.kt
package com.domain.app.voice

import com.domain.app.calendar.Diary
import com.domain.app.place.Whereabouts
import com.domain.app.sense.PhoneQuestion
import java.time.ZonedDateTime

/**
 * Everything Anthemos understands, in one place (docs/anthemos-2.md,
 * "Commands").
 *
 * A [Focus] is one area of what it does (Log, Calendar, Places...). Each has
 * its [Command]s: a name, what it does, example sentences, and how it is
 * recognised. To add something Anthemos understands, add a command to its
 * focus here, with examples, and place it in [Commands.order];
 * `CommandsTest` checks every example lands on its own command.
 */
data class Focus(val id: String, val about: String, val commands: List<Command>)

data class Command(
    /** "focus.name", e.g. "calendar.add". */
    val id: String,
    val about: String,
    val examples: List<String>,
    val recognise: (Said) -> VoiceRequest?
)

/** One sentence, in the forms the commands read it. */
data class Said(
    /** As heard or typed. */
    val transcript: String,
    /** Lowercase, without punctuation. */
    val text: String,
    /** [text] without "please", "can you", "hey Anthemos". */
    val command: String,
    val now: ZonedDateTime
)

/** Which command a sentence was, and what it asks for. */
data class Match(val command: Command, val request: VoiceRequest)

object Commands {

    fun focuses(parser: RuleParser): List<Focus> = listOf(
        Focus(
            "log", "Things that happened, kept as said with what was in them", listOf(
                Command(
                    "log.record", "Food, drink, activity, sleep, money; at the time said, or now",
                    listOf("I had a coffee and toast", "went for a walk for forty five minutes", "I had a coffee at about 8 am")
                ) { parser.record(it.transcript) }
            )
        ),
        Focus(
            "notes", "Words kept as they are, only when asked for", listOf(
                Command("notes.note", "Keeps the words as a note", listOf("take a note the bus was late", "note to self call the bank")) { s ->
                    if (RuleParser.NOTE.find(s.command) != null) VoiceRequest.Record(s.transcript, emptyList(), "Noted", note = true) else null
                }
            )
        ),
        Focus(
            "change", "Fixing what was kept", listOf(
                Command("change.undo", "Takes back everything from the last thing said", listOf("undo", "scratch that")) { s ->
                    if (s.command in RuleParser.UNDO) VoiceRequest.Undo(s.transcript) else null
                },
                Command(
                    "change.edit", "Changes, moves or removes something kept",
                    listOf("change the coffee to tea", "delete the coffee", "the walk was at seven", "make that two")
                ) { s -> Change.parse(s.command, s.now)?.let { VoiceRequest.Edit(s.transcript, it) } }
            )
        ),
        Focus(
            "questions", "Questions about what was kept", listOf(
                Command(
                    "questions.ask", "Counts, totals, the last time, what was had",
                    listOf("when did I last call dad", "how many coffees have I had this week")
                ) { s -> Questions.parse(s.text, s.now)?.let { VoiceRequest.Ask(s.transcript, it) } }
            )
        ),
        Focus(
            "calendar", "Anthemos's own calendar and reminders", listOf(
                Command(
                    "calendar.add", "Adds an event or a reminder, after asking",
                    listOf("add dentist thursday at three", "remind me to call dad at five")
                ) { s -> Diary.parseAdd(s.transcript, s.now)?.let { VoiceRequest.AddEvent(s.transcript, it) } },
                Command(
                    "calendar.ask", "What's on, when something is, whether you're free",
                    listOf("what's on tomorrow", "when's the dentist", "am I free friday afternoon")
                ) { s -> Diary.parseQuestion(s.transcript, s.now)?.let { VoiceRequest.AskDiary(s.transcript, it) } }
            )
        ),
        Focus(
            "calls", "Phoning people from the contacts", listOf(
                Command("calls.call", "Calls someone, after asking", listOf("call Sam Smith", "give dad a call", "ring mum")) { parser.call(it.transcript, it.command) },
                Command("calls.teach", "Who a name like Dad means", listOf("Dad is John Moss")) { parser.teach(it.transcript, it.command) }
            )
        ),
        Focus(
            "phone", "What the phone noticed", listOf(
                Command("phone.ask", "Screen time and estimated sleep", listOf("how much screen time today", "how long did I sleep")) { s ->
                    PhoneQuestion.parse(s.transcript, s.now)?.let { VoiceRequest.AskPhone(s.transcript, it) }
                }
            )
        ),
        Focus(
            "places", "Where you are and have been, kept on the phone", listOf(
                where("places.name", "Names where you are", listOf("this is home", "call this place the gym")) { it is Whereabouts.Name },
                where("places.here", "Where you are now", listOf("where am I")) { it == Whereabouts.WhereAmI },
                where("places.was", "Where you were on a day", listOf("where was I yesterday")) { it is Whereabouts.WhereWas },
                where("places.trail", "Turns the trail on or off", listOf("start tracking", "stop tracking")) { it is Whereabouts.Trail }
            )
        ),
        Focus(
            "kronk", "Sharing with Kronk, signed by this phone", listOf(
                where("kronk.connect", "Signs in to Kronk and links this phone", listOf("connect to Kronk")) { it == Whereabouts.LinkKronk },
                where(
                    "kronk.share", "Shares live location with Mates, after asking",
                    listOf("share my location with Ana for an hour", "share live tracking with Jye on Kronk")
                ) { it is Whereabouts.Share },
                where("kronk.stop", "Stops sharing live location", listOf("stop sharing", "stop sharing with Ana")) { it is Whereabouts.StopSharing }
            )
        )
    )

    /**
     * The order commands are tried in, first match wins. It crosses focuses
     * on purpose: "remind me to call Dad" is the calendar before it is a call,
     * "this is home" a place before it is something to log, and anything left
     * is logged.
     */
    val order = listOf(
        "change.undo", "change.edit",
        "kronk.connect", "places.here", "places.was", "places.trail", "kronk.stop", "kronk.share", "places.name",
        "calendar.add", "calls.call", "calls.teach", "calendar.ask", "phone.ask", "questions.ask",
        "notes.note", "log.record"
    )

    fun match(said: Said, parser: RuleParser): Match {
        val all = focuses(parser).flatMap { it.commands }.associateBy { it.id }
        for (id in order) {
            val command = all.getValue(id)
            command.recognise(said)?.let { return Match(command, it) }
        }
        error("log.record always matches")
    }

    private fun where(id: String, about: String, examples: List<String>, which: (Whereabouts) -> Boolean) =
        Command(id, about, examples) { s ->
            Whereabouts.parse(s.command, s.now)?.takeIf(which)?.let { VoiceRequest.Where(s.transcript, it) }
        }
}
