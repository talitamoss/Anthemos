// app/src/main/java/com/domain/app/focus/QuestionsFocus.kt
package com.domain.app.focus

import com.domain.app.voice.Entry
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
                listOf("when did I last call dad", "how many coffees have I had this week"),
                { s -> Questions.parse(s.text, s.now)?.let { VoiceRequest.Ask(s.transcript, it) } }
            ) { request ->
                val entries = withContext(Dispatchers.IO) {
                    data.getPluginData(Entry.PLUGIN_ID).first().mapNotNull(Entry::from)
                }
                reply(Questions.answer(request.question, entries, ZonedDateTime.now(), contacts::alias))
            }
        )
    )
}
