// app/src/main/java/com/domain/app/focus/MediaFocus.kt
package com.domain.app.focus

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
import com.domain.app.media.Music
import com.domain.app.ui.voice.VoicePhase
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command

/**
 * Music: whichever app plays it on the phone, through the phone's own
 * media controls; nothing to sign in to (docs/anthemos-2.md, "Music").
 */
object MediaFocus {

    fun focus() = Focus(
        "media", "Music in whichever app plays it", listOf(
            command(
                "media.control", "Play, pause, skip, back, louder, quieter",
                listOf("play music", "pause", "skip this song", "play the previous song", "turn it up"),
                { s -> Music.parse(s.command)?.takeIf { it !is Music.Play }?.let { VoiceRequest.Media(s.transcript, it) } }
            ) { control(it.music) },
            command(
                "media.play", "Plays an artist, album, song or playlist, found by the music app",
                listOf("play Bonobo", "play some jazz"),
                { s -> (Music.parse(s.command) as? Music.Play)?.let { VoiceRequest.Media(s.transcript, it) } }
            ) { play((it.music as Music.Play).what) }
        )
    )

    private fun Turn.control(music: Music) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun key(code: Int) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (music) {
            Music.Resume -> key(KeyEvent.KEYCODE_MEDIA_PLAY).also { quietly("Playing") }
            Music.Pause -> key(KeyEvent.KEYCODE_MEDIA_PAUSE).also { quietly("Paused") }
            Music.Next -> key(KeyEvent.KEYCODE_MEDIA_NEXT).also { quietly("Next") }
            Music.Previous -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS).also { quietly("Back one") }
            Music.Louder -> repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                .also { quietly("Louder") }
            Music.Quieter -> repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                .also { quietly("Quieter") }
            is Music.Play -> play(music.what)
        }
    }

    /** Shown, not said: speaking would talk over the music. */
    private fun Turn.quietly(text: String) = show { it.copy(reply = text, phase = VoicePhase.IDLE) }

    /** Handed to the music app's own search, which picks the best match and plays it. */
    private fun Turn.play(what: String) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, what)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            quietly("Playing $what")
        } catch (e: ActivityNotFoundException) {
            reply("No music app on the phone takes a search. Open it, and say play.")
        }
    }
}
