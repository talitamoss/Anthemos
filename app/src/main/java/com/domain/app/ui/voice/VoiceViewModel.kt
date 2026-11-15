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
import com.domain.app.core.data.DataPoint
import com.domain.app.core.data.DataRepository
import com.domain.app.voice.Contacts
import com.domain.app.voice.Listener
import com.domain.app.voice.RuleParser
import com.domain.app.voice.Speaker
import com.domain.app.voice.VoiceRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class VoicePhase { IDLE, LISTENING, CONFIRMING }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** What was heard, as it comes in. */
    val heard: String = "",
    /** What Anthemos said back. */
    val reply: String = "",
    /** A call waiting for yes or no. */
    val pendingCall: Contacts.Match? = null,
    val onDeviceSpeech: Boolean = false
)

/**
 * The voice loop (docs/anthemos-2.md, build order 1-3): listen, understand,
 * then record straight away or confirm an action first, and say what
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
    private val contacts = Contacts(context)
    private var lastSavedId: String? = null

    private val _state = MutableStateFlow(VoiceUiState(onDeviceSpeech = listener.onDevice))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Today's entries, newest first. */
    val today: StateFlow<List<DataPoint>> = dataRepository.getRecentData(200)
        .map { points ->
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            points.filter { !it.timestamp.isBefore(start) }.sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun listen() {
        speaker.say("") // stop anything being spoken
        _state.update { it.copy(phase = VoicePhase.LISTENING, heard = "") }
        listener.start(object : Listener.Callbacks {
            override fun onListening() = Unit
            override fun onPartial(text: String) = _state.update { it.copy(heard = text) }
            override fun onHeard(text: String) {
                _state.update { it.copy(heard = text) }
                handle(text)
            }
            override fun onError(message: String) {
                val confirming = _state.value.pendingCall != null
                _state.update {
                    it.copy(phase = if (confirming) VoicePhase.CONFIRMING else VoicePhase.IDLE, reply = message)
                }
            }
        })
    }

    fun stopListening() {
        listener.stop()
        _state.update { it.copy(phase = if (it.pendingCall != null) VoicePhase.CONFIRMING else VoicePhase.IDLE) }
    }

    /** Yes or no to a pending call, by tap. */
    fun confirm(yes: Boolean) {
        val call = _state.value.pendingCall ?: return
        listener.stop()
        if (yes) dial(call) else reply("Okay, not calling", pendingCall = null)
    }

    fun undo() = viewModelScope.launch { undoLast() }

    private fun handle(text: String) {
        _state.value.pendingCall?.let { call ->
            // No before yes: "don't call" contains "call".
            when {
                NO.any { Regex("\\b$it\\b").containsMatchIn(text.lowercase()) } -> reply("Okay, not calling", pendingCall = null)
                YES.any { Regex("\\b$it\\b").containsMatchIn(text.lowercase()) } -> dial(call)
                else -> ask("Call ${call.name}? Say yes or no.", call)
            }
            return
        }

        when (val request = parser.parse(text)) {
            is VoiceRequest.Record -> viewModelScope.launch { save(request) }
            is VoiceRequest.Undo -> viewModelScope.launch { undoLast() }
            is VoiceRequest.Call -> viewModelScope.launch { prepareCall(request) }
        }
    }

    private suspend fun save(request: VoiceRequest.Record) {
        val point = DataPoint(
            pluginId = request.pluginId,
            type = request.type,
            value = request.value,
            // The exact words stay with the entry, so a better model can
            // re-read them later.
            metadata = mapOf("transcript" to request.transcript, "inputType" to "voice"),
            source = "voice"
        )
        withContext(Dispatchers.IO) { dataRepository.saveDataPoint(point) }
        lastSavedId = point.id
        reply(request.reply)
    }

    private suspend fun undoLast() {
        val id = lastSavedId ?: today.value.firstOrNull { it.source == "voice" }?.id
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
            reply("I need your contacts to call someone")
            return
        }
        val match = withContext(Dispatchers.IO) { contacts.find(request.name) }
        if (match == null) {
            reply("I couldn't find ${request.name} in your contacts")
            return
        }
        ask("Call ${match.name}?", match)
    }

    /** Asks a yes/no question about [call], then listens for the answer. */
    private fun ask(question: String, call: Contacts.Match) {
        _state.update { it.copy(phase = VoicePhase.CONFIRMING, reply = question, pendingCall = call) }
        speaker.say(question) {
            viewModelScope.launch(Dispatchers.Main) {
                if (_state.value.pendingCall == call) listen()
            }
        }
    }

    private fun dial(call: Contacts.Match) {
        reply("Calling ${call.name}", pendingCall = null)
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(call.number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun reply(text: String, pendingCall: Contacts.Match? = _state.value.pendingCall) {
        _state.update {
            it.copy(
                phase = if (pendingCall != null) VoicePhase.CONFIRMING else VoicePhase.IDLE,
                reply = text,
                pendingCall = pendingCall
            )
        }
        speaker.say(text)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCleared() {
        listener.stop()
        speaker.shutdown()
    }

    companion object {
        private val YES = listOf("yes", "yeah", "yep", "sure", "ok", "okay", "do it", "go ahead", "call")
        private val NO = listOf("no", "nope", "cancel", "don't", "stop", "never mind")
    }
}
