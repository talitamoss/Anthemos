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
        val reply: String,
        /** When it happened, if said ("at 8 am"); null for now. */
        val at: java.time.ZonedDateTime? = null,
        /**
         * Parts said with different times ("woke up at 5:30, having a
         * coffee now"), each kept as its own entry. Empty for one entry.
         */
        val parts: List<Record> = emptyList(),
        /** Asked for as a note ("take a note..."): kept as words, nothing to find. */
        val note: Boolean = false
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

    /** Something for the calendar, confirmed before it's added. */
    data class AddEvent(
        override val transcript: String,
        val event: com.domain.app.calendar.Event
    ) : VoiceRequest()

    /** "What's on tomorrow?" */
    data class AskDiary(
        override val transcript: String,
        val question: com.domain.app.calendar.DiaryQuestion
    ) : VoiceRequest()

    /** Screen time, last night's sleep. */
    data class AskPhone(
        override val transcript: String,
        val question: com.domain.app.sense.PhoneQuestion
    ) : VoiceRequest()

    /** Change or remove something already kept. */
    data class Edit(
        override val transcript: String,
        val change: Change
    ) : VoiceRequest()

    /** Places, the trail, and sharing where you are (docs/anthemos-2.md, "Places"). */
    data class Where(
        override val transcript: String,
        val ask: com.domain.app.place.Whereabouts
    ) : VoiceRequest()

    /** Hey Poly on or off. */
    data class Wake(override val transcript: String, val on: Boolean) : VoiceRequest()

    /** Take back the last entry. */
    data class Undo(override val transcript: String) : VoiceRequest()
}
