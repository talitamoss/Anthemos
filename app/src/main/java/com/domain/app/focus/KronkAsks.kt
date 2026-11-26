// app/src/main/java/com/domain/app/focus/KronkAsks.kt
package com.domain.app.focus

import com.domain.app.focus.KronkFocus.connect
import com.domain.app.focus.KronkFocus.start
import com.domain.app.focus.PlacesFocus.withLocation
import com.domain.app.kronk.Kronk
import com.domain.app.kronk.KronkInbox
import com.domain.app.place.Here
import com.domain.app.place.Whereabouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Asks from Kronk (docs/anthemos-2.md, "Asks from Kronk"; Kronk's
 * docs/spaces/map.md). Kronk's website keeps what it asked and opens
 * Anthemos with only its token (`anthemos://kronk/ask?id=…&server=…&for=…`).
 * Anthemos reads the ask with its own sign-in, so only an ask made in the
 * account it is connected to comes back, shows it with Allow and Not now,
 * a tap and never a spoken yes, and answers Kronk, signed. A new kind is a
 * handler here and an entry in Kronk's `AnthemosAsk::PAYLOAD`.
 */
object KronkAsks {

    /** What a kind asks, and doing it: an error to tell Kronk, or null when done. */
    class Handler(
        val location: Boolean,
        val question: (Kronk.Ask) -> String,
        val allow: String,
        val run: suspend Turn.(Kronk.Ask) -> Result
    )

    /** Done, with a short result for Kronk to show ("Sydney, Australia"), or failed with why. */
    class Result(val done: Boolean, val said: String)

    val HANDLERS: Map<String, Handler> = mapOf(
        "location.update" to Handler(
            location = true,
            question = { "Move your pin to where you are now? Mates see the town, not the spot." },
            allow = "Move it"
        ) {
            val here = Here.now(context) ?: return@Handler failed("I can't tell where you are right now")
            val town = try {
                withContext(Dispatchers.IO) { kronk.updatePin(here.lat, here.lng) }
            } catch (e: Exception) {
                return@Handler failed(e.message ?: "I couldn't reach Kronk")
            }
            reply(if (town != null) "Your pin on Kronk is in $town" else "Your pin on Kronk is moved")
            Result(true, town.orEmpty())
        },
        "location.share" to Handler(
            location = true,
            question = { ask -> "Share your exact location with ${ask.to?.name} until ${clock(until())}?" },
            allow = "Share"
        ) { ask ->
            val mate = ask.to ?: return@Handler failed("Kronk didn't say who with")
            val error = start(listOf(mate), until())
            if (error != null) Result(false, error) else Result(true, "")
        }
    )

    /** Kronk's website asked (`KronkInbox.ask`). */
    suspend fun Turn.fromKronk(link: KronkInbox.Ask) {
        if (!kronk.linked) {
            connect()
            return reply("Connect to Kronk first: sign in in the browser, then try again on Kronk")
        }
        val ours = android.net.Uri.parse(kronk.server).host
        if (link.server.isNotEmpty() && link.server != ours) {
            return reply("That was from ${link.server}, but Anthemos is connected to $ours")
        }
        val me = kronk.account.orEmpty()
        if (link.forAcct.isNotEmpty() && !link.forAcct.equals(me, ignoreCase = true)) {
            return reply("That was for @${link.forAcct} on Kronk, but Anthemos is connected as @$me. Switch Kronk to @$me, or connect Anthemos to @${link.forAcct}.")
        }
        val ask = try {
            withContext(Dispatchers.IO) { kronk.ask(link.id) }
        } catch (e: Exception) {
            return reply("Kronk has no ask like that for @$me. It may have run out: try again on Kronk.")
        }
        if (ask.status != "waiting") return reply(if (ask.status == "expired") "That ask from Kronk ran out. Try again on Kronk." else "That ask from Kronk is already answered")
        val handler = HANDLERS[ask.kind] ?: run {
            answer(ask, "failed", "This Anthemos needs updating")
            return reply("Kronk asked for something this Anthemos can't do yet. Update Anthemos.")
        }
        if (handler.location) withLocation { show(ask, handler) } else show(ask, handler)
    }

    private fun Turn.show(ask: Kronk.Ask, handler: Handler) {
        val question = "From Kronk, @${ask.account}: ${handler.question(ask)}"
        val allow = Choice(handler.allow, primary = true) { allowed(ask, handler) }
        val notNow = Choice("Not now") { declined(ask) }
        tap(
            Waiting(question, listOf(allow, notNow), confirming = true) { text, self ->
                // Typed instead of tapped.
                val lower = text.lowercase()
                when {
                    Words.no(lower) -> declined(ask)
                    Words.yes(lower) -> allowed(ask, handler)
                    else -> tap(Waiting(question, self.choices, true, self.answer))
                }
            }
        )
    }

    private suspend fun Turn.allowed(ask: Kronk.Ask, handler: Handler) {
        val result = handler.run(this, ask)
        answer(ask, if (result.done) "done" else "failed", result.said)
    }

    private suspend fun Turn.declined(ask: Kronk.Ask) {
        answer(ask, "declined", "")
        reply("Okay, left it")
    }

    private suspend fun Turn.answer(ask: Kronk.Ask, outcome: String, result: String) {
        withContext(Dispatchers.IO) { runCatching { kronk.answer(ask.id, outcome, result.take(200)) } }
    }

    private fun Turn.failed(why: String): Result {
        reply(why)
        return Result(false, why)
    }

    private fun until() = ZonedDateTime.now().plusMinutes(Whereabouts.DEFAULT_MINUTES)

    private fun clock(at: ZonedDateTime) = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(at).lowercase()
}
