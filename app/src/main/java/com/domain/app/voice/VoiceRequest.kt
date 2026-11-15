// app/src/main/java/com/domain/app/voice/VoiceRequest.kt
package com.domain.app.voice

/**
 * What a spoken sentence asks for (docs/anthemos-2.md, "What it does").
 *
 * Records save straight away and can be undone. Actions leave the phone, so
 * they are confirmed first.
 */
sealed class VoiceRequest {
    abstract val transcript: String

    /** An entry for one of the kinds (plugins): Food, Water, Caffeine... */
    data class Record(
        override val transcript: String,
        val pluginId: String,
        val type: String,
        val value: Map<String, Any>,
        /** What Anthemos says back, e.g. "Lunch logged". */
        val reply: String
    ) : VoiceRequest()

    /** Phone someone from the contacts. Confirmed before dialling. */
    data class Call(
        override val transcript: String,
        val name: String
    ) : VoiceRequest()

    /** Take back the last entry. */
    data class Undo(override val transcript: String) : VoiceRequest()
}
