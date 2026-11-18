// app/src/main/java/com/domain/app/voice/Commands.kt
package com.domain.app.voice

import com.domain.app.focus.CalendarFocus
import com.domain.app.focus.CallsFocus
import com.domain.app.focus.ChangeFocus
import com.domain.app.focus.KronkFocus
import com.domain.app.focus.LogFocus
import com.domain.app.focus.NotesFocus
import com.domain.app.focus.PhoneFocus
import com.domain.app.focus.PlacesFocus
import com.domain.app.focus.QuestionsFocus
import com.domain.app.focus.Turn
import com.domain.app.focus.WakeFocus
import java.time.ZonedDateTime

/**
 * Everything Anthemos understands, in one place (docs/anthemos-2.md,
 * "Commands").
 *
 * A [Focus] is one area of what it does (Log, Calendar, Places...), and lives
 * in its own file in `focus/`. Each has its [Command]s: a name, what it does,
 * example sentences, how it is recognised, and what it does then. To add
 * something Anthemos understands, add a command to its focus, with examples,
 * and place it in [Commands.order]; `CommandsTest` checks every example lands
 * on its own command.
 */
data class Focus(val id: String, val about: String, val commands: List<Command>)

class Command(
    /** "focus.name", e.g. "calendar.add". */
    val id: String,
    val about: String,
    val examples: List<String>,
    val recognise: (Said) -> VoiceRequest?,
    val run: suspend Turn.(VoiceRequest) -> Unit
)

/** A command recognising one kind of request, and handling it. */
inline fun <reified R : VoiceRequest> command(
    id: String,
    about: String,
    examples: List<String>,
    noinline recognise: (Said) -> R?,
    crossinline run: suspend Turn.(R) -> Unit
) = Command(id, about, examples, recognise) { run(it as R) }

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
        LogFocus.focus(parser),
        NotesFocus.focus(),
        ChangeFocus.focus(),
        QuestionsFocus.focus(),
        CalendarFocus.focus(),
        CallsFocus.focus(parser),
        PhoneFocus.focus(),
        PlacesFocus.focus(),
        KronkFocus.focus(),
        WakeFocus.focus()
    )

    /**
     * The order commands are tried in, first match wins. It crosses focuses
     * on purpose: "remind me to call Dad" is the calendar before it is a call,
     * "this is home" a place before it is something to log, and anything left
     * is logged.
     */
    val order = listOf(
        "change.undo", "change.edit", "wake.on", "wake.off",
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
}
