// app/src/main/java/com/domain/app/focus/CallsFocus.kt
package com.domain.app.focus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.domain.app.focus.LogFocus.keepWords
import com.domain.app.focus.LogFocus.save
import com.domain.app.prompts.PromptWorker
import com.domain.app.voice.Contacts
import com.domain.app.voice.Focus
import com.domain.app.voice.Item
import com.domain.app.voice.RuleParser
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calls: phoning people from the contacts, always asked first
 * (docs/anthemos-2.md, "Where it stands", alpha 3).
 */
object CallsFocus {

    fun focus(parser: RuleParser) = Focus(
        "calls", "Phoning people from the contacts", listOf(
            command(
                "calls.call", "Calls someone, after asking",
                listOf("call Sam Smith", "give dad a call", "ring mum"),
                { parser.call(it.transcript, it.command) }
            ) { request ->
                if (!granted(Manifest.permission.READ_CONTACTS)) {
                    return@command if (request.sure) reply("I need your contacts to call someone") else keepWords(request.transcript)
                }
                val match = withContext(Dispatchers.IO) { contacts.find(request.name) }
                when {
                    match != null -> confirmCall(match, request.name.lowercase())
                    // Only heard as something like "call": probably wasn't one.
                    !request.sure -> keepWords(request.transcript)
                    else -> askWho(request.name)
                }
            },
            command(
                "calls.teach", "Who a name like Dad means",
                listOf("Dad is John Moss"),
                { parser.teach(it.transcript, it.command) }
            ) { request ->
                val match = withContext(Dispatchers.IO) {
                    if (granted(Manifest.permission.READ_CONTACTS)) contacts.find(request.name) else null
                }
                // "Dad is tired" isn't teaching a name: keep it.
                if (match == null) return@command keepWords(request.transcript)
                contacts.teach(request.alias, match.name)
                reply("Got it, ${request.alias} is ${match.name}")
            }
        )
    )

    private fun Turn.confirmCall(match: Contacts.Match, said: String, question: String = "Call ${match.name}?") =
        ask(confirm(question, "Yes, call", "Okay, not calling", again = "Call ${match.name}? Say yes or no.") { dial(match, said) })

    /** "Who's Dad?", then listens for the name as it is in the contacts. */
    private fun Turn.askWho(word: String) {
        val key = word.lowercase()
        ask(Waiting("I don't know who $word is. Say their name as it is in your contacts.") { said, _ -> learnName(key, said) })
    }

    private suspend fun Turn.learnName(word: String, said: String) {
        val name = said.lowercase()
            .replace(Regex("^(?:it's|it is|that's|that is|they're|she's|he's|${Regex.escape(word)} is|${Regex.escape(word)}'s)\\s+"), "")
            .trim()
        val match = withContext(Dispatchers.IO) { contacts.find(name) }
            ?: return reply("I couldn't find $name either")
        contacts.teach(word, match.name)
        confirmCall(match, word, "Got it, $word is ${match.name}. Call now?")
    }

    private suspend fun Turn.dial(match: Contacts.Match, said: String) {
        reply("Calling ${match.name}")
        // Kept like anything else, so "when did I last call Dad?" can be answered.
        save("Called ${match.name}", listOf(Item("call", match.name, said = said)))
        // Afterwards: "Anything to remember from the call?"
        PromptWorker.afterCall(context, match.name)
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(Intent(action, Uri.parse("tel:${Uri.encode(match.number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun Turn.granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
