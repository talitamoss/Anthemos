// app/src/main/java/com/domain/app/focus/NotesFocus.kt
package com.domain.app.focus

import com.domain.app.focus.LogFocus.keep
import com.domain.app.voice.Focus
import com.domain.app.voice.RuleParser
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command

/** Notes: words kept as they are, only when asked for. */
object NotesFocus {
    fun focus() = Focus(
        "notes", "Words kept as they are, only when asked for", listOf(
            command(
                "notes.note", "Keeps the words as a note",
                listOf("take a note the bus was late", "note to self call the bank"),
                { s -> if (RuleParser.NOTE.find(s.command) != null) VoiceRequest.Record(s.transcript, emptyList(), "Noted", note = true) else null }
            ) { keep(it) }
        )
    )
}
