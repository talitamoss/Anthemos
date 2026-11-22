// app/src/main/java/com/domain/app/focus/Turn.kt
package com.domain.app.focus

import android.content.Context
import com.domain.app.calendar.LinkedCalendar
import com.domain.app.core.data.DataRepository
import com.domain.app.home.HomeAssistant
import com.domain.app.kronk.Kronk
import com.domain.app.place.LocationState
import com.domain.app.prompts.Prompt
import com.domain.app.prompts.PromptLog
import com.domain.app.sense.PhoneSense
import com.domain.app.ui.voice.VoiceUiState
import com.domain.app.voice.Contacts
import com.domain.app.voice.RuleParser
import com.domain.app.voice.RuleReader

/**
 * What a focus can do while handling one thing said (docs/anthemos-2.md,
 * "Commands"). Speak (`ui/voice/VoiceViewModel.kt`) provides it: listening,
 * showing and saying replies, and the pieces every focus shares. Each focus
 * keeps its own handling beside its commands.
 */
interface Turn {
    val context: Context
    val data: DataRepository
    val parser: RuleParser
    val reader: RuleReader
    val contacts: Contacts
    val promptLog: PromptLog
    val sense: PhoneSense
    val kronk: Kronk
    val location: LocationState
    val calendar: LinkedCalendar
    val home: HomeAssistant

    /** Spoken, not typed: replies are said aloud. */
    val voice: Boolean
    /** The recogniser wasn't sure of the words. */
    val unclear: Boolean
    /** A prompt asked in the last few minutes, which this may answer. */
    val prompt: Prompt?

    /** The entry last kept or changed, for Undo and "that". */
    var lastSavedId: String?
    /** Everything kept from the last thing said, so Undo takes it all back. */
    var lastTurn: List<String>

    /** Shows [text], and says it on a spoken turn. */
    fun reply(text: String)

    /** Asks something, then listens for the answer, which goes to [Waiting.answer]. */
    fun ask(waiting: Waiting)

    /** Asks on screen only, answered by a tap: nothing is said and nothing listened for. */
    fun tap(waiting: Waiting)

    /** Asks a prompt or follow-up, then listens for its answer. */
    fun askPrompt(question: String, prompt: Prompt)

    /** Changes what Speak shows (Undo, the Proton hand-off, the screen-time card). */
    fun show(change: (VoiceUiState) -> VoiceUiState)

    /** Starts listening again. */
    fun listen()

    /** Handles words as if just said: a second try. */
    suspend fun handle(text: String)

    /** Location permission is needed: asks for it, then runs [then] if given. */
    fun needLocation(then: suspend Turn.() -> Unit)
}

/** A question waiting for the next thing said, or a tap on one of its [choices]. */
class Waiting(
    val question: String,
    val choices: List<Choice> = emptyList(),
    /** A yes or no before something is done. */
    val confirming: Boolean = false,
    val answer: suspend Turn.(text: String, self: Waiting) -> Unit
)

/** A button under a question. */
class Choice(val label: String, val primary: Boolean = false, val run: suspend Turn.() -> Unit)

/**
 * "Call Sam?", "Add dentist?", "Share your location?": done only on yes.
 * [yesWords] also count as yes ("add", "share"); anything that is neither
 * asks [again].
 */
fun confirm(
    question: String,
    yesLabel: String,
    noReply: String,
    yesWords: List<String> = emptyList(),
    again: String = question,
    yes: suspend Turn.() -> Unit
): Waiting = Waiting(
    question,
    listOf(Choice(yesLabel, primary = true, run = yes), Choice("No") { reply(noReply) }),
    confirming = true
) { text, self ->
    val lower = text.lowercase()
    // No before yes: "don't call" contains "call".
    when {
        Words.no(lower) -> reply(noReply)
        Words.yes(lower) || yesWords.any { lower.contains(it) } -> yes()
        else -> ask(Waiting(again, self.choices, true, self.answer))
    }
}

object Words {
    private val YES = listOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "do it", "go ahead", "call", "please")
    private val NO = listOf("no", "nope", "nah", "cancel", "don't", "stop", "never mind")

    fun yes(lower: String) = YES.any { Regex("\\b$it\\b").containsMatchIn(lower) }
    fun no(lower: String) = NO.any { Regex("\\b$it\\b").containsMatchIn(lower) }
    fun exactlyNo(lower: String) = lower in NO
}
