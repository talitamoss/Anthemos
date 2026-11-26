// app/src/main/java/com/domain/app/focus/PhoneFocus.kt
package com.domain.app.focus

import com.domain.app.sense.PhoneAnswers
import com.domain.app.sense.PhoneQuestion
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import java.time.ZonedDateTime

/** Phone: what the phone noticed, read with usage access (docs/anthemos-2.md, "What the phone already knows"). */
object PhoneFocus {
    fun focus() = Focus(
        "phone", "What the phone noticed", listOf(
            command(
                "phone.ask", "Screen time and estimated sleep",
                listOf("how much screen time today", "how long did I sleep"),
                { s -> PhoneQuestion.parse(s.transcript, s.now)?.let { VoiceRequest.AskPhone(s.transcript, it) } }
            ) { answer(it.question) }
        )
    )

    private fun Turn.answer(question: PhoneQuestion) {
        if (!sense.allowed()) {
            show { it.copy(senseOff = true) }
            return reply("I can't see that yet. Tap Allow to let Anthemos read screen time.")
        }
        val now = ZonedDateTime.now()
        reply(
            when (question) {
                is PhoneQuestion.ScreenTime -> PhoneAnswers.screenTime(
                    question.phrase,
                    sense.screenMinutes(question.from, question.until),
                    sense.topApps(question.from, question.until)
                )
                PhoneQuestion.Slept -> PhoneAnswers.slept(sense.lastNight(now), now)
            }
        )
    }
}
