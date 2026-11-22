// app/src/main/java/com/domain/app/ui/voice/VoiceViewModel.kt
package com.domain.app.ui.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.domain.app.calendar.Event
import com.domain.app.calendar.LinkedCalendar
import com.domain.app.calendar.Reminders
import com.domain.app.core.data.DataRepository
import com.domain.app.focus.ChangeFocus.undo
import com.domain.app.focus.Choice
import com.domain.app.focus.KronkAsks.fromKronk
import com.domain.app.focus.KronkFocus.finishConnecting
import com.domain.app.focus.KronkFocus.shareFromKronk
import com.domain.app.focus.KronkFocus.updateFromKronk
import com.domain.app.focus.Turn
import com.domain.app.focus.Waiting
import com.domain.app.home.HomeAssistant
import com.domain.app.kronk.Kronk
import com.domain.app.kronk.KronkInbox
import com.domain.app.place.LocationState
import com.domain.app.prompts.Prompt
import com.domain.app.prompts.PromptInbox
import com.domain.app.prompts.PromptLog
import com.domain.app.reflect.Lenses
import com.domain.app.reflect.Record
import com.domain.app.reflect.Reflection
import com.domain.app.sense.PhoneSense
import com.domain.app.sense.Sleep
import com.domain.app.voice.Contacts
import com.domain.app.voice.Entry
import com.domain.app.voice.Listener
import com.domain.app.voice.RuleParser
import com.domain.app.voice.RuleReader
import com.domain.app.voice.Speaker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class VoicePhase { IDLE, LISTENING, CONFIRMING }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** What was heard, as it comes in. */
    val heard: String = "",
    /** What Anthemos said back: a confirmation or an answer. */
    val reply: String = "",
    /** A question waiting for an answer: "Call Sam?", "Which coffee?" */
    val waiting: Waiting? = null,
    /** A prompt or follow-up waiting for its answer. */
    val prompt: Prompt? = null,
    /** An event just added, which can be handed to Proton. */
    val handoff: Event? = null,
    /** True when the screen can't be read: the phone's usage access is off. */
    val senseOff: Boolean = false,
    /** Something was just kept or changed, so Undo is offered. */
    val canUndo: Boolean = false,
    /** Location permission is needed: the screen asks for it. */
    val askLocation: Boolean = false
)

/**
 * Speak's voice loop (docs/anthemos-2.md): listen, find which command was
 * said (`voice/Commands.kt`), and let its focus (`focus/`) handle it. This
 * holds only what every focus shares: listening, replies, questions waiting
 * for an answer, and prompts.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    dataRepository: DataRepository
) : ViewModel() {

    private val listener = Listener(appContext)
    private val speaker = Speaker(appContext)
    /** When the waiting prompt was asked: an old one is dropped, not answered by whatever is said next. */
    private var promptAt: Instant = Instant.EPOCH
    /** What to do once location permission is answered. */
    private var afterLocation: (suspend Turn.() -> Unit)? = null
    /** The last request to listen (from Hey Poly, a reminder, a headset) Speak has answered. */
    var listenAnswered = 0

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val turn = object : Turn {
        override val context = appContext
        override val data = dataRepository
        override val parser = RuleParser()
        override val reader = RuleReader()
        override val contacts = Contacts(appContext)
        override val promptLog = PromptLog(appContext)
        override val sense = PhoneSense(appContext)
        override val kronk = Kronk(appContext)
        override val location = LocationState(appContext)
        override val calendar = LinkedCalendar(appContext)
        override val home = HomeAssistant(appContext)
        override var voice = true
        override var unclear = false
        override var prompt: Prompt? = null
        override var lastSavedId: String? = null
        override var lastTurn: List<String> = emptyList()

        override fun reply(text: String) {
            _state.update { it.copy(reply = text, phase = phaseFor(it)) }
            if (voice) speaker.say(text)
        }

        override fun ask(waiting: Waiting) {
            _state.update {
                it.copy(reply = waiting.question, waiting = waiting, phase = if (waiting.confirming) VoicePhase.CONFIRMING else VoicePhase.IDLE)
            }
            speakThen(waiting.question) { if (_state.value.waiting === waiting) listen() }
        }

        override fun tap(waiting: Waiting) {
            listener.cancel()
            _state.update { it.copy(reply = waiting.question, waiting = waiting, phase = VoicePhase.IDLE) }
        }

        override fun askPrompt(question: String, prompt: Prompt) {
            promptAt = Instant.now()
            _state.update { it.copy(phase = VoicePhase.IDLE, reply = question, prompt = prompt) }
            speakThen(question) { if (_state.value.prompt == prompt) listen() }
        }

        override fun show(change: (VoiceUiState) -> VoiceUiState) = _state.update(change)

        override fun listen() = this@VoiceViewModel.listen()

        override suspend fun handle(text: String) = this@VoiceViewModel.handle(text, unclear)

        override fun needLocation(then: suspend Turn.() -> Unit) {
            afterLocation = then
            _state.update { it.copy(askLocation = true) }
        }
    }

    /** Today's entries, newest first. */
    val today: StateFlow<List<Entry>> = dataRepository.getPluginData(Entry.PLUGIN_ID)
        .map { points ->
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            points.mapNotNull(Entry::from).filter { !it.time.isBefore(start) }.sortedByDescending { it.time }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Bumped on coming back to the page, so what the phone saw is read again. */
    private val looked = MutableStateFlow(0)

    /** What you've recorded, looked at (`reflect/`): read from the encrypted store, on the phone. */
    val reflections: StateFlow<List<Reflection>> = combine(dataRepository.getPluginData(Entry.PLUGIN_ID), looked) { points, _ ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // All of it: "smoke-free" and weight look back further than a week.
        val entries = points.mapNotNull(Entry::from)
        val sense = turn.sense
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val nights = if (sense.allowed()) {
            week.mapNotNull { day ->
                val from = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
                val until = minOf(day.atTime(13, 0).atZone(zone).toInstant(), Instant.now())
                if (!until.isAfter(from)) null else Sleep.guess(sense.screenOn(from, until), from, until)?.let { day to it }
            }.toMap()
        } else {
            emptyMap()
        }
        val screen = if (sense.allowed()) {
            week.associateWith { day ->
                sense.screenMinutes(day.atStartOfDay(zone).toInstant(), minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now()))
            }
        } else {
            emptyMap()
        }
        Lenses.look(Record(entries, today, zone, nights, screen))
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        listener.prepare()
        refreshSense()
        // Back from signing in to Kronk in the browser.
        viewModelScope.launch {
            KronkInbox.code.collect { code ->
                code ?: return@collect
                KronkInbox.code.value = null
                turn.finishConnecting(code)
            }
        }
        // "Share live location" tapped on Kronk's Map, before asks (Kronk 2.1.16).
        viewModelScope.launch {
            KronkInbox.share.collect { share ->
                share ?: return@collect
                KronkInbox.share.value = null
                turn.shareFromKronk(share)
            }
        }
        // Kronk's website asked: shown with Allow and Not now, nothing spoken.
        viewModelScope.launch {
            KronkInbox.ask.collect { ask ->
                ask ?: return@collect
                KronkInbox.ask.value = null
                turn.voice = false
                turn.fromKronk(ask)
            }
        }
        // "Update" tapped on Kronk's Map, before asks (Kronk 2.1.16).
        viewModelScope.launch {
            KronkInbox.update.collect { server ->
                server ?: return@collect
                KronkInbox.update.value = null
                turn.updateFromKronk(server)
            }
        }
    }

    fun listen() {
        // A reminder like "Call Dad" was tapped: act on it as if said.
        PromptInbox.takeSay()?.let { words ->
            turn.voice = true
            _state.update { it.copy(heard = words, handoff = null) }
            launchHandle(words)
            return
        }
        speaker.say("") // stop anything being spoken
        // Opened from a prompt notification: show its question while listening.
        val prompt = PromptInbox.take()?.also { promptAt = Instant.now() } ?: freshPrompt()
        _state.update {
            it.copy(
                phase = VoicePhase.LISTENING, heard = "", reply = it.waiting?.question ?: prompt?.text.orEmpty(),
                prompt = prompt, handoff = null, canUndo = false
            )
        }
        listener.start(object : Listener.Callbacks {
            override fun onListening() = Unit
            override fun onPartial(text: String) = _state.update { it.copy(heard = text) }
            override fun onHeard(text: String, unclear: Boolean) {
                turn.voice = true
                _state.update { it.copy(heard = text) }
                launchHandle(text, unclear)
            }
            override fun onError(message: String) {
                // A yes or no stays asked; anything else waiting is dropped.
                _state.update {
                    val waiting = it.waiting?.takeIf { w -> w.confirming }
                    it.copy(reply = message, waiting = waiting, prompt = null, phase = if (waiting != null) VoicePhase.CONFIRMING else VoicePhase.IDLE)
                }
            }
        })
    }

    /** Stop tapped: act on what was heard so far. */
    fun stopListening() = listener.finish()

    /** Typed instead of spoken: the same path, answered in words only. */
    fun typed(text: String) {
        if (text.isBlank()) return
        listener.cancel()
        turn.voice = false
        _state.update { it.copy(phase = phaseFor(it), heard = text.trim(), handoff = null, canUndo = false) }
        launchHandle(text.trim())
    }

    /** A button under a question, tapped. */
    fun choose(choice: Choice) {
        listener.cancel()
        _state.update { it.copy(waiting = null, phase = VoicePhase.IDLE) }
        viewModelScope.launch { choice.run(turn) }
    }

    /** Copies the event just added into Proton (or whichever calendar app takes it). */
    fun handOff() {
        val event = _state.value.handoff ?: return
        _state.update { it.copy(handoff = null) }
        if (!Reminders.handOff(appContext, event)) turn.reply("No calendar app on the phone took it")
    }

    fun allowSense() {
        appContext.startActivity(turn.sense.settings())
    }

    /**
     * This app's page in Settings. Android blocks usage access for apps not
     * from a store until "Allow restricted settings" is chosen there.
     */
    fun appInfo() {
        appContext.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun refreshSense() {
        _state.update { it.copy(senseOff = !turn.sense.allowed()) }
        looked.value++
    }

    fun undo() {
        viewModelScope.launch { turn.undo() }
    }

    /** The screen asked for location: carry on with what needed it. */
    fun locationAnswered(granted: Boolean) {
        _state.update { it.copy(askLocation = false) }
        val then = afterLocation ?: return
        afterLocation = null
        viewModelScope.launch { if (granted) then(turn) else turn.reply("I need your location for that") }
    }

    private fun launchHandle(text: String, unclear: Boolean = false) {
        viewModelScope.launch { handle(text, unclear) }
    }

    /** One thing said: the answer to a question waiting, or a command. */
    private suspend fun handle(text: String, unclear: Boolean) {
        turn.unclear = unclear
        _state.value.waiting?.let { waiting ->
            _state.update { it.copy(waiting = null, phase = VoicePhase.IDLE) }
            waiting.answer(turn, text, waiting)
            return
        }
        // A prompt lasts a few minutes; only something to keep answers it.
        turn.prompt = freshPrompt()
        _state.update { it.copy(prompt = null) }
        val match = turn.parser.match(text)
        match.command.run(turn, match.request)
    }

    /** Speaks [text] on a spoken turn, then runs [then]; on a typed turn, just shows it. */
    private fun speakThen(text: String, then: () -> Unit) {
        if (!turn.voice) return
        speaker.say(text) { viewModelScope.launch(Dispatchers.Main) { then() } }
    }

    /** The waiting prompt, if it was asked in the last few minutes. */
    private fun freshPrompt(): Prompt? =
        _state.value.prompt?.takeIf { Instant.now().isBefore(promptAt.plusSeconds(PROMPT_MINUTES * 60)) }

    private fun phaseFor(s: VoiceUiState) =
        if (s.waiting?.confirming == true) VoicePhase.CONFIRMING else VoicePhase.IDLE

    override fun onCleared() {
        listener.shutdown()
        speaker.shutdown()
    }

    companion object {
        private const val PROMPT_MINUTES = 3L
    }
}
