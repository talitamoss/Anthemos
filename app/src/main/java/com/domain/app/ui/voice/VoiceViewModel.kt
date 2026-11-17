// app/src/main/java/com/domain/app/ui/voice/VoiceViewModel.kt
package com.domain.app.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.domain.app.core.data.DataRepository
import com.domain.app.calendar.Diary
import com.domain.app.calendar.Event
import com.domain.app.calendar.Reminders
import com.domain.app.prompts.Prompt
import com.domain.app.sense.PhoneAnswers
import com.domain.app.sense.PhoneQuestion
import com.domain.app.sense.PhoneSense
import com.domain.app.prompts.PromptInbox
import com.domain.app.prompts.PromptLog
import com.domain.app.prompts.PromptRules
import com.domain.app.prompts.PromptWorker
import com.domain.app.voice.Changes
import com.domain.app.voice.Contacts
import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import com.domain.app.voice.Listener
import com.domain.app.voice.Questions
import com.domain.app.voice.RuleParser
import com.domain.app.voice.RuleReader
import com.domain.app.voice.Speaker
import com.domain.app.voice.VoiceRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject

enum class VoicePhase { IDLE, LISTENING, CONFIRMING }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** What was heard, as it comes in. */
    val heard: String = "",
    /** What Anthemos said back: a confirmation or an answer. */
    val reply: String = "",
    /** A call waiting for yes or no. */
    val pendingCall: PendingCall? = null,
    /** A word ("dad") waiting to be told which contact it means. */
    val pendingName: String? = null,
    /** A prompt or follow-up waiting for its answer. */
    val prompt: Prompt? = null,
    /** An event waiting for yes or no. */
    val pendingEvent: Event? = null,
    /** An event just added, which can be handed to Proton. */
    val handoff: Event? = null,
    /** True when the screen can't be read: the phone's usage access is off. */
    val senseOff: Boolean = false,
    /** Something was just kept or changed, so Undo is offered. */
    val canUndo: Boolean = false
)

data class PendingCall(val match: Contacts.Match, val said: String)

/**
 * The voice loop (docs/anthemos-2.md): listen, understand, then keep the
 * words, answer a question, or confirm an action first, and say what
 * happened.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataRepository: DataRepository
) : ViewModel() {

    private val listener = Listener(context)
    private val speaker = Speaker(context)
    private val parser = RuleParser()
    private val reader = RuleReader()
    private val contacts = Contacts(context)
    private val promptLog = PromptLog(context)
    private val sense = PhoneSense(context)
    /** Whether this turn was spoken: typed turns aren't answered aloud. */
    private var voice = true
    private var lastSavedId: String? = null

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Today's entries, newest first. */
    val today: StateFlow<List<Entry>> = dataRepository.getPluginData(Entry.PLUGIN_ID)
        .map { points ->
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            points.mapNotNull(Entry::from).filter { !it.time.isBefore(start) }.sortedByDescending { it.time }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        listener.prepare()
        refreshSense()
    }

    fun listen() {
        // A reminder like "Call Dad" was tapped: act on it as if said.
        PromptInbox.takeSay()?.let { words ->
            voice = true
            _state.update { it.copy(heard = words, handoff = null) }
            handle(words)
            return
        }
        speaker.say("") // stop anything being spoken
        // Opened from a prompt notification: show its question while listening.
        val prompt = PromptInbox.take() ?: _state.value.prompt
        _state.update {
            it.copy(phase = VoicePhase.LISTENING, heard = "", reply = prompt?.text.orEmpty(), prompt = prompt, handoff = null, canUndo = false)
        }
        listener.start(object : Listener.Callbacks {
            override fun onListening() = Unit
            override fun onPartial(text: String) = _state.update { it.copy(heard = text) }
            override fun onHeard(text: String) {
                voice = true
                _state.update { it.copy(heard = text) }
                handle(text)
            }
            override fun onError(message: String) {
                _state.update { it.copy(phase = phaseFor(it), reply = message, pendingName = null, prompt = null) }
            }
        })
    }

    /** Stop tapped: act on what was heard so far. */
    fun stopListening() = listener.finish()

    /** Typed instead of spoken: the same path, answered in words only. */
    fun typed(text: String) {
        if (text.isBlank()) return
        listener.cancel()
        voice = false
        _state.update { it.copy(phase = phaseFor(it), heard = text.trim(), handoff = null, canUndo = false) }
        handle(text.trim())
    }

    /** Yes or no to a pending call or event, by tap. */
    fun confirm(yes: Boolean) {
        listener.cancel()
        _state.value.pendingEvent?.let { event ->
            if (yes) viewModelScope.launch { addEvent(event) } else reply("Okay, not added", pendingEvent = null)
            return
        }
        val call = _state.value.pendingCall ?: return
        if (yes) dial(call) else reply("Okay, not calling", pendingCall = null)
    }

    /** Copies the event just added into Proton (or whichever calendar app takes it). */
    fun handOff() {
        val event = _state.value.handoff ?: return
        _state.update { it.copy(handoff = null) }
        if (!Reminders.handOff(context, event)) reply("No calendar app on the phone took it")
    }

    fun allowSense() {
        context.startActivity(sense.settings())
    }

    /**
     * This app's page in Settings. Android blocks usage access for apps not
     * from a store until "Allow restricted settings" is chosen there.
     */
    fun appInfo() {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun refreshSense() = _state.update { it.copy(senseOff = !sense.allowed()) }

    fun undo() = viewModelScope.launch { undoLast() }

    private fun handle(text: String) {
        _state.value.pendingEvent?.let { event ->
            val lower = text.lowercase()
            when {
                NO.any { Regex("\\b$it\\b").containsMatchIn(lower) } -> reply("Okay, not added", pendingEvent = null)
                YES.any { Regex("\\b$it\\b").containsMatchIn(lower) } || lower.contains("add") ->
                    viewModelScope.launch { addEvent(event) }
                else -> confirmEvent(event)
            }
            return
        }
        _state.value.pendingCall?.let { call ->
            val lower = text.lowercase()
            // No before yes: "don't call" contains "call".
            when {
                NO.any { Regex("\\b$it\\b").containsMatchIn(lower) } -> reply("Okay, not calling", pendingCall = null)
                YES.any { Regex("\\b$it\\b").containsMatchIn(lower) } -> dial(call)
                else -> ask("Call ${call.match.name}? Say yes or no.", call)
            }
            return
        }
        _state.value.pendingName?.let { word ->
            _state.update { it.copy(pendingName = null) }
            viewModelScope.launch { learnName(word, text) }
            return
        }

        _state.value.prompt?.let { prompt ->
            _state.update { it.copy(prompt = null) }
            viewModelScope.launch { answer(prompt, text) }
            return
        }

        when (val request = parser.parse(text)) {
            is VoiceRequest.Record -> viewModelScope.launch {
                val entry = save(request.transcript, request.items, null)
                followUpOr(entry, request.reply)
            }
            is VoiceRequest.Undo -> viewModelScope.launch { undoLast() }
            is VoiceRequest.Edit -> viewModelScope.launch { change(request) }
            is VoiceRequest.Call -> viewModelScope.launch { prepareCall(request) }
            is VoiceRequest.Teach -> viewModelScope.launch { teach(request) }
            is VoiceRequest.Ask -> viewModelScope.launch { answer(request) }
            is VoiceRequest.AddEvent -> confirmEvent(request.event)
            is VoiceRequest.AskDiary -> viewModelScope.launch {
                reply(Diary.answer(request.question, events(), ZonedDateTime.now()))
            }
            is VoiceRequest.AskPhone -> answerPhone(request.question)
        }
    }

    private suspend fun events(): List<Event> = withContext(Dispatchers.IO) {
        dataRepository.getPluginData(Event.PLUGIN_ID).first().mapNotNull(Event::from)
    }

    /** "Add Dentist, Thursday 20 November at 3:00 pm?" */
    private fun confirmEvent(event: Event) {
        val now = ZonedDateTime.now()
        val question = if (event.reminder) {
            "Remind you: ${event.title}, ${Diary.dayAndTime(event, now)}?"
        } else {
            "Add ${Diary.describe(event, now)}?"
        }
        _state.update { it.copy(phase = VoicePhase.CONFIRMING, reply = question, pendingEvent = event) }
        speakThen(question) { if (_state.value.pendingEvent == event) listen() }
    }

    private suspend fun addEvent(event: Event) {
        withContext(Dispatchers.IO) { dataRepository.saveDataPoint(event.toDataPoint()) }
        Reminders.schedule(context, event)
        val now = ZonedDateTime.now()
        if (event.reminder) {
            reply("I'll remind you ${Diary.dayAndTime(event, now)}", pendingEvent = null)
        } else {
            reply("Added ${Diary.describe(event, now)}", pendingEvent = null)
            _state.update { it.copy(handoff = event) }
        }
    }

    private fun answerPhone(question: PhoneQuestion) {
        if (!sense.allowed()) {
            _state.update { it.copy(senseOff = true) }
            reply("I can't see that yet. Tap Allow to let Anthemos read screen time.")
            return
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

    /** Speaks [text] on a spoken turn, then runs [then]; on a typed turn, just shows it. */
    private fun speakThen(text: String, then: () -> Unit) {
        if (!voice) return
        speaker.say(text) { viewModelScope.launch(Dispatchers.Main) { then() } }
    }

    private suspend fun save(words: String, items: List<Item>, replyText: String?, prompt: String? = null): Entry {
        val entry = Entry(
            UUID.randomUUID().toString(), Instant.now(), words, items, RuleReader.NAME, prompt,
            source = if (voice) "voice" else "typed"
        )
        withContext(Dispatchers.IO) { dataRepository.saveDataPoint(entry.toDataPoint()) }
        lastSavedId = entry.id
        _state.update { it.copy(canUndo = true) }
        replyText?.let { reply(it) }
        return entry
    }

    /** Says [said], and asks one follow-up if an obvious detail is missing. */
    private fun followUpOr(entry: Entry, said: String) {
        val next = PromptRules.followUp(entry)
        if (next == null) {
            reply(said)
            return
        }
        val question = "$said. ${next.text}"
        _state.update { it.copy(phase = VoicePhase.IDLE, reply = question, prompt = next) }
        speakThen(question) { if (_state.value.prompt == next) listen() }
    }

    /** An answer to a prompt or a follow-up. */
    private suspend fun answer(prompt: Prompt, text: String) {
        if (prompt.entryId != null) {
            addTo(prompt, text)
            return
        }
        promptLog.answered(prompt.id)
        if (prompt.id.startsWith("${PromptRules.EVENT}:")) promptLog.answered(PromptRules.EVENT)
        // Hours the phone saw, when the answer gives none.
        val items = reader.read(text, prompt.context).map { item ->
            if (item.kind == "sleep" && item.minutes == null && prompt.minutes != null) item.copy(minutes = prompt.minutes) else item
        }
        val entry = save(text, items, null, prompt = prompt.text)
        followUpOr(entry, if (items.isEmpty()) "Kept" else reader.reply(items))
    }

    /** A follow-up answer fills in the detail on the entry it was about. */
    private suspend fun addTo(prompt: Prompt, answer: String) {
        val entry = withContext(Dispatchers.IO) { dataRepository.getDataPoint(prompt.entryId!!) }?.let(Entry::from)
        if (entry == null) {
            reply("Noted")
            return
        }
        val items = entry.items.toMutableList()
        var said = "Noted"
        when (prompt.field) {
            "minutes" -> {
                val i = items.indexOfFirst { (it.kind == "activity" || it.kind == "sleep") && it.minutes == null }
                val minutes = if (i >= 0) reader.duration(answer, bareIsHours = items[i].kind == "sleep") else null
                if (minutes != null) {
                    items[i] = items[i].copy(minutes = minutes)
                    said = if (items[i].kind == "sleep") "Added, ${minutes / 60.0} hours".replace(".0 ", " ") else "Added, $minutes minutes"
                }
            }
            "amount" -> reader.money(answer)?.let {
                items += it
                said = "Added, ${it.label}"
            }
        }
        val updated = entry.copy(words = entry.words.trimEnd('.', ' ') + ". " + answer, items = items)
        withContext(Dispatchers.IO) { dataRepository.saveDataPoint(updated.toDataPoint()) }
        reply(said)
    }

    private suspend fun answer(request: VoiceRequest.Ask) {
        val entries = withContext(Dispatchers.IO) {
            dataRepository.getPluginData(Entry.PLUGIN_ID).first().mapNotNull(Entry::from)
        }
        reply(Questions.answer(request.question, entries, ZonedDateTime.now(), contacts::alias))
    }

    private suspend fun undoLast() {
        val id = lastSavedId ?: today.value.firstOrNull()?.id
        if (id == null) {
            reply("There's nothing to take back")
            return
        }
        withContext(Dispatchers.IO) { dataRepository.deleteDataPoint(id) }
        lastSavedId = null
        _state.update { it.copy(canUndo = false) }
        reply("Taken back")
    }

    /** "Change the coffee to tea", "delete the coffee", "that was at eight". */
    private suspend fun change(request: VoiceRequest.Edit) {
        val now = ZonedDateTime.now()
        val since = now.minusDays(7).toInstant()
        val entries = withContext(Dispatchers.IO) {
            dataRepository.getPluginData(Entry.PLUGIN_ID).first().mapNotNull(Entry::from)
        }.filter { it.source != "phone" && it.time.isAfter(since) }.sortedByDescending { it.time }
        val last = entries.firstOrNull { it.id == lastSavedId } ?: entries.firstOrNull()
        when (val outcome = Changes.apply(request.change, entries, last, now)) {
            is Changes.Outcome.Save -> {
                withContext(Dispatchers.IO) { dataRepository.saveDataPoint(outcome.entry.toDataPoint()) }
                lastSavedId = outcome.entry.id
                reply(outcome.reply)
            }
            is Changes.Outcome.Delete -> {
                withContext(Dispatchers.IO) { dataRepository.deleteDataPoint(outcome.entry.id) }
                if (lastSavedId == outcome.entry.id) lastSavedId = null
                reply(outcome.reply)
            }
            is Changes.Outcome.Say -> reply(outcome.reply)
            Changes.Outcome.Keep -> keepWords(request.transcript)
        }
    }

    private suspend fun prepareCall(request: VoiceRequest.Call) {
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            if (request.sure) reply("I need your contacts to call someone") else keepWords(request.transcript)
            return
        }
        val match = withContext(Dispatchers.IO) { contacts.find(request.name) }
        when {
            match != null -> ask("Call ${match.name}?", PendingCall(match, request.name.lowercase()))
            // Only heard as something like "call": probably wasn't one.
            !request.sure -> keepWords(request.transcript)
            else -> askWho(request.name)
        }
    }

    /** "Who's Dad?", then listens for the name as it is in the contacts. */
    private fun askWho(word: String) {
        val key = word.lowercase()
        val question = "I don't know who $word is. Say their name as it is in your contacts."
        _state.update { it.copy(phase = VoicePhase.IDLE, reply = question, pendingName = key) }
        speakThen(question) { if (_state.value.pendingName == key) listen() }
    }

    private suspend fun learnName(word: String, said: String) {
        val name = said.lowercase()
            .replace(Regex("^(?:it's|it is|that's|that is|they're|she's|he's|${Regex.escape(word)} is|${Regex.escape(word)}'s)\\s+"), "")
            .trim()
        val match = withContext(Dispatchers.IO) { contacts.find(name) }
        if (match == null) {
            reply("I couldn't find $name either")
            return
        }
        contacts.teach(word, match.name)
        ask("Got it, $word is ${match.name}. Call now?", PendingCall(match, word))
    }

    private suspend fun teach(request: VoiceRequest.Teach) {
        val match = withContext(Dispatchers.IO) {
            if (granted(Manifest.permission.READ_CONTACTS)) contacts.find(request.name) else null
        }
        if (match == null) {
            // "Dad is tired" isn't teaching a name: keep it.
            keepWords(request.transcript)
            return
        }
        contacts.teach(request.alias, match.name)
        reply("Got it, ${request.alias} is ${match.name}")
    }

    private suspend fun keepWords(transcript: String) {
        val items = reader.read(transcript)
        followUpOr(save(transcript, items, null), reader.reply(items))
    }

    /** Asks a yes/no question about [call], then listens for the answer. */
    private fun ask(question: String, call: PendingCall) {
        _state.update { it.copy(phase = VoicePhase.CONFIRMING, reply = question, pendingCall = call) }
        speakThen(question) { if (_state.value.pendingCall == call) listen() }
    }

    private fun dial(call: PendingCall) {
        reply("Calling ${call.match.name}", pendingCall = null)
        // Kept like anything else, so "when did I last call Dad?" can be answered.
        viewModelScope.launch {
            save("Called ${call.match.name}", listOf(Item("call", call.match.name, said = call.said)), null)
        }
        // Afterwards: "Anything to remember from the call?"
        PromptWorker.afterCall(context, call.match.name)
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(call.match.number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun reply(
        text: String,
        pendingCall: PendingCall? = _state.value.pendingCall,
        pendingEvent: Event? = _state.value.pendingEvent
    ) {
        _state.update {
            val next = it.copy(reply = text, pendingCall = pendingCall, pendingEvent = pendingEvent)
            next.copy(phase = phaseFor(next))
        }
        if (voice) speaker.say(text)
    }

    private fun phaseFor(s: VoiceUiState) =
        if (s.pendingCall != null || s.pendingEvent != null) VoicePhase.CONFIRMING else VoicePhase.IDLE

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCleared() {
        listener.shutdown()
        speaker.shutdown()
    }

    companion object {
        private val YES = listOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "do it", "go ahead", "call", "please")
        private val NO = listOf("no", "nope", "nah", "cancel", "don't", "stop", "never mind")
    }
}
