// app/src/main/java/com/domain/app/voice/VoiceRequest.kt
package com.domain.app.voice

/**
 * What a spoken sentence asks for (docs/anthemos-2.md, "What it does").
 *
 * Entries save straight away and can be undone. Actions leave the phone, so
 * they are confirmed first. Questions are answered from the entries.
 */
sealed class VoiceRequest {
    abstract val transcript: String

    /** Something to keep: the words, and what the reader found in them. */
    data class Record(
        override val transcript: String,
        val items: List<Item>,
        /** What Anthemos says back, e.g. "Logged salad and coffee". */
        val reply: String
    ) : VoiceRequest()

    /**
     * Phone someone from the contacts. Confirmed before dialling. [sure] is
     * false when "call" was only heard as something like it ("cool mum"):
     * if no contact matches, the words are kept as an entry instead.
     */
    data class Call(
        override val transcript: String,
        val name: String,
        val sure: Boolean = true
    ) : VoiceRequest()

    /** "Dad is John Moss": a name to use for a contact. */
    data class Teach(
        override val transcript: String,
        val alias: String,
        val name: String
    ) : VoiceRequest()

    /** A question about what has been recorded. */
    data class Ask(
        override val transcript: String,
        val question: Question
    ) : VoiceRequest()

    /** Take back the last entry. */
    data class Undo(override val transcript: String) : VoiceRequest()
}
