// app/src/main/java/com/domain/app/focus/KronkFocus.kt
package com.domain.app.focus

import android.content.Intent
import com.domain.app.focus.PlacesFocus.where
import com.domain.app.focus.PlacesFocus.withLocation
import com.domain.app.kronk.Kronk
import com.domain.app.kronk.KronkInbox
import com.domain.app.kronk.SoundsLike
import com.domain.app.place.Here
import com.domain.app.place.LiveShare
import com.domain.app.place.LocationService
import com.domain.app.place.Whereabouts
import com.domain.app.voice.Focus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Kronk: sharing with Kronk, signed by this phone (docs/anthemos-2.md,
 * "Kronk"). Anything that leaves the phone is asked first.
 */
object KronkFocus {

    fun focus() = Focus(
        "kronk", "Sharing with Kronk, signed by this phone", listOf(
            where<Whereabouts.LinkKronk>("kronk.connect", "Signs in to Kronk and links this phone", listOf("connect to Kronk")) { connect() },
            where<Whereabouts.Share>(
                "kronk.share", "Shares live location with Mates, after asking",
                listOf("share my location with Ana for an hour", "share live tracking with Jye on Kronk")
            ) { share(it) },
            where<Whereabouts.StopSharing>("kronk.stop", "Stops sharing live location", listOf("stop sharing", "stop sharing with Ana")) { stop(it.name) },
            where<Whereabouts.UpdateKronk>(
                "kronk.update", "Moves your Kronk pin to where you are, after asking",
                listOf("update my location on Kronk", "put me on the map")
            ) { update() }
        )
    )

    suspend fun Turn.connect() {
        if (kronk.linked) return reply("Already connected to Kronk")
        val page = try {
            withContext(Dispatchers.IO) { kronk.signInPage() }
        } catch (e: Exception) {
            return reply(e.message ?: "I couldn't reach Kronk")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        reply("Sign in to Kronk in the browser, then come back here")
    }

    /** Back from the browser with a code (`KronkInbox`): link this phone. */
    suspend fun Turn.finishConnecting(code: String) {
        if (code.isEmpty()) return reply("Kronk didn't sign you in")
        try {
            withContext(Dispatchers.IO) {
                kronk.finishSignIn(code)
                runCatching { kronk.allMates(fresh = true) }
            }
            reply("Connected to Kronk. Say share my location with, and a Mate's name.")
        } catch (e: Exception) {
            reply(e.message ?: "I couldn't finish connecting to Kronk")
        }
    }

    /** Finds the Mates named, then asks before anything leaves the phone. */
    private suspend fun Turn.share(ask: Whereabouts.Share) = withLocation {
        if (!kronk.linked) return@withLocation reply("Connect to Kronk first. Say connect to Kronk.")
        val found = mutableListOf<Kronk.Mate>()
        for (name in ask.names) {
            val matches = try {
                withContext(Dispatchers.IO) { mateFor(name) }
            } catch (e: Exception) {
                return@withLocation reply(e.message ?: "I couldn't reach Kronk")
            }
            when (matches.size) {
                1 -> found += matches[0]
                0 -> return@withLocation reply("I can't find a Mate called $name on Kronk. You can share from Kronk's Map too: Place me, then Share live location.")
                else -> return@withLocation reply("Which $name: ${matches.take(3).joinToString(" or ") { it.name }}? Say it again with their full name.")
            }
        }
        val mates = found.distinctBy { it.id }
        ask(
            confirm(
                "Share your exact location with ${names(mates)} until ${clock(ask.until)}?",
                "Yes, share", "Okay, not sharing", yesWords = listOf("share")
            ) { start(mates, ask.until) }
        )
    }

    /**
     * The Mates [name] could be. Names are matched by how they sound against
     * the Mates kept on the phone, since the speech model hears an unusual
     * name as a word it knows ("pago" for Pargo); then Kronk's own search,
     * then the kept Mates again, asked fresh, for someone new.
     */
    private fun Turn.mateFor(name: String): List<Kronk.Mate> {
        fun bySound(mates: List<Kronk.Mate>) =
            SoundsLike.find(name, mates) { listOf(it.name, it.handle.substringBefore('@')) }.map { it.item }.distinctBy { it.id }
        bySound(kronk.allMates()).takeIf { it.isNotEmpty() }?.let { return it }
        val searched = kronk.mates(name)
        if (searched.isNotEmpty()) return bySound(searched).ifEmpty { searched }
        return bySound(kronk.allMates(fresh = true))
    }

    /** "Share live location" tapped on Kronk's Map, for one Mate (`KronkInbox.share`). */
    suspend fun Turn.shareFromKronk(share: KronkInbox.Share) = withLocation {
        if (!kronk.linked) {
            connect()
            return@withLocation reply("Connect to Kronk first: sign in in the browser, then tap Share live location on Kronk again")
        }
        val ours = android.net.Uri.parse(kronk.server).host
        if (share.server.isNotEmpty() && share.server != ours) {
            return@withLocation reply("That was ${share.server}, but Anthemos is connected to $ours")
        }
        val mate = try {
            withContext(Dispatchers.IO) { kronk.account(share.to) }
        } catch (e: Exception) {
            return@withLocation reply(e.message ?: "I couldn't reach Kronk")
        }
        val until = ZonedDateTime.now().plusMinutes(Whereabouts.DEFAULT_MINUTES)
        ask(
            confirm(
                "Share your exact location with ${mate.name} until ${clock(until)}?",
                "Yes, share", "Okay, not sharing", yesWords = listOf("share")
            ) { start(listOf(mate), until) }
        )
    }

    /** "Update" tapped on Kronk's Map (`KronkInbox.update`), holding Kronk's host. */
    suspend fun Turn.updateFromKronk(server: String) {
        if (!kronk.linked) {
            connect()
            return reply("Connect to Kronk first: sign in in the browser, then tap Update on Kronk again")
        }
        val ours = android.net.Uri.parse(kronk.server).host
        if (server.isNotEmpty() && server != ours) return reply("That was $server, but Anthemos is connected to $ours")
        update()
    }

    /** Asks, then moves the pin. Mates see the town, never the exact spot. */
    private suspend fun Turn.update() = withLocation {
        if (!kronk.linked) return@withLocation reply("Connect to Kronk first. Say connect to Kronk.")
        ask(
            confirm(
                "Move your pin on Kronk to where you are now? Mates see the town, not the spot.",
                "Yes, move it", "Okay, leaving it", yesWords = listOf("move")
            ) {
                val here = Here.now(context) ?: return@confirm reply("I can't tell where you are right now")
                val town = try {
                    withContext(Dispatchers.IO) { kronk.updatePin(here.lat, here.lng) }
                } catch (e: Exception) {
                    return@confirm reply(e.message ?: "I couldn't reach Kronk")
                }
                reply(if (town != null) "Your pin on Kronk is in $town" else "Your pin on Kronk is moved")
            }
        )
    }

    private suspend fun Turn.start(mates: List<Kronk.Mate>, until: ZonedDateTime) {
        val id = try {
            withContext(Dispatchers.IO) { kronk.startSharing(mates, until.toInstant()) }
        } catch (e: Exception) {
            return reply(e.message ?: "I couldn't reach Kronk")
        }
        location.shares = location.shares + LiveShare(id, mates.map { it.name }, until.toInstant())
        LocationService.refresh(context)
        reply("Sharing with ${names(mates)} until ${clock(until)}. Say stop sharing to end it.")
    }

    private suspend fun Turn.stop(name: String?) {
        val shares = location.shares
        val ending = if (name == null) shares else shares.filter { s -> s.names.any { it.contains(name, ignoreCase = true) } }
        if (ending.isEmpty()) return reply(if (name == null) "You're not sharing your location" else "You're not sharing with $name")
        location.shares = shares - ending.toSet()
        LocationService.refresh(context)
        withContext(Dispatchers.IO) { ending.forEach { runCatching { kronk.stopSharing(it.id) } } }
        reply("Stopped sharing with ${ending.flatMap { it.names }.distinct().joinToString(" and ")}")
    }

    private fun names(mates: List<Kronk.Mate>) = when (mates.size) {
        1 -> mates[0].name
        else -> mates.dropLast(1).joinToString(", ") { it.name } + " and " + mates.last().name
    }

    private fun clock(at: ZonedDateTime) = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(at).lowercase()
}
