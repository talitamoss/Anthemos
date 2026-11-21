// app/src/main/java/com/domain/app/focus/QuestionsFocus.kt
package com.domain.app.focus

import com.domain.app.focus.HomeFocus.tryHome
import com.domain.app.focus.LogFocus.keep
import com.domain.app.sense.PhoneAnswers
import com.domain.app.voice.Entry
import com.domain.app.voice.Question
import com.domain.app.voice.Focus
import com.domain.app.voice.Questions
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** Questions: about what was kept (docs/anthemos-2.md, "Where it stands", alpha 3). */
object QuestionsFocus {
    fun focus() = Focus(
        "questions", "Questions about what was kept", listOf(
            command(
                "questions.ask", "Counts, totals, the last time, what was had",
                listOf("when did I last call dad", "how many coffees have I had this week", "what time did I go to sleep yesterday", "when did I wake up"),
                { s -> Questions.parse(s.text, s.now)?.let { VoiceRequest.Ask(s.transcript, it) } }
            ) { request ->
                val entries = withContext(Dispatchers.IO) {
                    data.getPluginData(Entry.PLUGIN_ID).first().mapNotNull(Entry::from)
                }
                val now = ZonedDateTime.now()
                val q = request.question
                // Nothing said about going to sleep or waking: the phone's guess, if it can see.
                if (q is Question.At && Questions.aboutSleep(q) && (q.period?.phrase ?: "yesterday") in LAST_NIGHT && Questions.found(q, entries, now).isEmpty() && sense.allowed()) {
                    return@command reply(PhoneAnswers.slept(sense.lastNight(now), now))
                }
                reply(Questions.answer(q, entries, now, contacts::alias))
            },
            command(
                "questions.other", "A question nothing else answers: said so, never logged",
                listOf("why is the sky blue", "who won the cricket"),
                { s -> if (QUESTION.containsMatchIn(s.command) || s.transcript.endsWith("?")) VoiceRequest.Unanswered(s.transcript) else null }
            ) { request ->
                // A prompt's answer can sound like a question ("how about seven hours"): kept as an answer.
                if (prompt != null) return@command keep(parser.record(request.transcript))
                // The house may know ("is anyone home?").
                if (tryHome(request.transcript)) return@command
                reply("I can't answer that yet. I can tell you when you last did something, how many, what you had, and what time.")
            }
        )
    )

    private val LAST_NIGHT = setOf("today", "yesterday", "last night")
    private val QUESTION = Regex(
        "^(?:what|when|where|why|who|which|how)\\b|^(?:did|do|does|have|has|was|were|am|is|are|can|could|will) (?:i|we|you|my)\\b"
    )
}
