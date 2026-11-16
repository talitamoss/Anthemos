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
    val pendingName: String? = null
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
    }

    fun listen() {
        speaker.say("") // stop anything being spoken
        _state.update { it.copy(phase = VoicePhase.LISTENING, heard = "", reply = "") }
        listener.start(object : Listener.Callbacks {
            override fun onListening() = Unit
            override fun onPartial(text: String) = _state.update { it.copy(heard = text) }
            override fun onHeard(text: String) {
                _state.update { it.copy(heard = text) }
                handle(text)
            }
            override fun onError(message: String) {
                _state.update { it.copy(phase = phaseFor(it.pendingCall), reply = message, pendingName = null) }
            }
        })
    }

    /** Stop tapped: act on what was heard so far. */
    fun stopListening() = listener.finish()

    /** Yes or no to a pending call, by tap. */
    fun confirm(yes: Boolean) {
        val call = _state.value.pendingCall ?: return
        listener.cancel()
        if (yes) dial(call) else reply("Okay, not calling", pendingCall = null)
    }

    fun undo() = viewModelScope.launch { undoLast() }

    private fun handle(text: String) {
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

        when (val request = parser.parse(text)) {
            is VoiceRequest.Record -> viewModelScope.launch { save(request.transcript, request.items, request.reply) }
            is VoiceRequest.Undo -> viewModelScope.launch { undoLast() }
            is VoiceRequest.Call -> viewModelScope.launch { prepareCall(request) }
            is VoiceRequest.Teach -> viewModelScope.launch { teach(request) }
            is VoiceRequest.Ask -> viewModelScope.launch { answer(request) }
        }
    }

    private suspend fun save(words: String, items: List<Item>, replyText: String?) {
        val entry = Entry(UUID.randomUUID().toString(), Instant.now(), words, items, RuleReader.NAME)
        withContext(Dispatchers.IO) { dataRepository.saveDataPoint(entry.toDataPoint()) }
        lastSavedId = entry.id
        replyText?.let { reply(it) }
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
        reply("Taken back")
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
        speaker.say(question) {
            viewModelScope.launch(Dispatchers.Main) {
                if (_state.value.pendingName == key) listen()
            }
        }
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
        save(transcript, items, reader.reply(items))
    }

    /** Asks a yes/no question about [call], then listens for the answer. */
    private fun ask(question: String, call: PendingCall) {
        _state.update { it.copy(phase = VoicePhase.CONFIRMING, reply = question, pendingCall = call) }
        speaker.say(question) {
            viewModelScope.launch(Dispatchers.Main) {
                if (_state.value.pendingCall == call) listen()
            }
        }
    }

    private fun dial(call: PendingCall) {
        reply("Calling ${call.match.name}", pendingCall = null)
        // Kept like anything else, so "when did I last call Dad?" can be answered.
        viewModelScope.launch {
            save("Called ${call.match.name}", listOf(Item("call", call.match.name, said = call.said)), null)
        }
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(call.match.number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun reply(text: String, pendingCall: PendingCall? = _state.value.pendingCall) {
        _state.update { it.copy(phase = phaseFor(pendingCall), reply = text, pendingCall = pendingCall) }
        speaker.say(text)
    }

    private fun phaseFor(call: PendingCall?) = if (call != null) VoicePhase.CONFIRMING else VoicePhase.IDLE

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
