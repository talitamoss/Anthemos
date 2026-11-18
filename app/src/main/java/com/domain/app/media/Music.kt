// app/src/main/java/com/domain/app/media/Music.kt
package com.domain.app.media

/**
 * Music, through whichever app plays it on the phone (docs/anthemos-2.md,
 * "Music"). Said as a command: "play", "pause", "skip", "play Bonobo".
 * "I played tennis" is something that happened, not this.
 */
sealed class Music {
    /** "Play Bonobo", "put on some jazz": handed to the music app's search. */
    data class Play(val what: String) : Music()
    object Resume : Music()
    object Pause : Music()
    object Next : Music()
    object Previous : Music()
    object Louder : Music()
    object Quieter : Music()

    companion object {
        /** [command] is lowercase, without punctuation or "hey Polly". */
        fun parse(command: String): Music? {
            val c = command.trim()
            return when {
                PAUSE.matches(c) -> Pause
                RESUME.matches(c) -> Resume
                NEXT.matches(c) -> Next
                PREVIOUS.matches(c) -> Previous
                LOUDER.matches(c) -> Louder
                QUIETER.matches(c) -> Quieter
                else -> PLAY.matchEntire(c)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(::Play)
            }
        }

        private const val IT = "(?: the| my| this| some)?(?: music| song| track| tune| it| playing)?"
        private val PAUSE = Regex("^(?:pause|stop|stop playing|mute)$IT$")
        private val RESUME = Regex("^(?:play|resume|unpause|continue|keep playing)$IT(?: again)?$|^put on (?:some )?music$|^play (?:me )?something$")
        private val NEXT = Regex("^(?:next|skip)$IT$|^(?:skip (?:this|to the next)|play the next|go to the next|next) (?:one|song|track)$")
        private val PREVIOUS = Regex("^(?:previous|go back)$IT$|^(?:play|go to) the (?:previous|last) (?:one|song|track)$|^play that again$")
        private val LOUDER = Regex("^(?:turn (?:it|the music|the volume|this) up|turn up (?:the )?(?:music|volume)|louder|volume up)$")
        private val QUIETER = Regex("^(?:turn (?:it|the music|the volume|this) down|turn down (?:the )?(?:music|volume)|quieter|softer|volume down)$")
        private val PLAY = Regex("^play(?: me)?(?: some)? (.+?)(?: (?:please|for me))?$")
    }
}
