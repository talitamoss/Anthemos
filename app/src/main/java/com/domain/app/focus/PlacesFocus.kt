// app/src/main/java/com/domain/app/focus/PlacesFocus.kt
package com.domain.app.focus

import com.domain.app.place.Geo
import com.domain.app.place.Here
import com.domain.app.place.LocationService
import com.domain.app.place.Place
import com.domain.app.place.Whereabouts
import com.domain.app.voice.Command
import com.domain.app.voice.Entry
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Places: where you are and have been, kept on the phone (docs/anthemos-2.md, "Places"). */
object PlacesFocus {

    fun focus() = Focus(
        "places", "Where you are and have been, kept on the phone", listOf(
            where<Whereabouts.Name>("places.name", "Names where you are", listOf("this is home", "call this place the gym")) { namePlace(it.name) },
            where<Whereabouts.WhereAmI>("places.here", "Where you are now", listOf("where am I")) { whereAmI() },
            where<Whereabouts.WhereWas>("places.was", "Where you were on a day", listOf("where was I yesterday")) { reply(whereWas(it)) },
            where<Whereabouts.Trail>("places.trail", "Turns the trail on or off", listOf("start tracking", "stop tracking")) { trail(it.on) }
        )
    )

    /** A command for one kind of [Whereabouts] ask; Places and Kronk share the reading. */
    inline fun <reified W : Whereabouts> where(
        id: String,
        about: String,
        examples: List<String>,
        crossinline run: suspend Turn.(W) -> Unit
    ): Command = command(
        id, about, examples,
        { s -> (Whereabouts.parse(s.command, s.now) as? W)?.let { VoiceRequest.Where(s.transcript, it) } }
    ) { request -> run(request.ask as W) }

    /** Runs [then] now, or once location permission is given. */
    suspend fun Turn.withLocation(then: suspend Turn.() -> Unit) {
        if (LocationService.allowed(context)) then() else needLocation(then)
    }

    private suspend fun Turn.places(): List<Place> = withContext(Dispatchers.IO) {
        data.getPluginData(Place.PLUGIN_ID).first().mapNotNull(Place::from)
    }

    /** "This is home": names where the phone is, or moves the name here. */
    private suspend fun Turn.namePlace(name: String) = withLocation {
        val here = Here.now(context) ?: return@withLocation reply("I can't tell where you are right now")
        val all = places()
        val same = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val place = same?.copy(lat = here.lat, lng = here.lng)
            ?: Geo.placeAt(all, here.lat, here.lng)?.copy(name = name)
            ?: Place.named(name, here)
        withContext(Dispatchers.IO) { data.saveDataPoint(place.toDataPoint()) }
        lastSavedId = place.id
        lastTurn = listOf(place.id)
        show { it.copy(canUndo = true) }
        reply("Saved this place as $name")
    }

    private suspend fun Turn.whereAmI() = withLocation {
        val here = Here.now(context) ?: return@withLocation reply("I can't tell where you are right now")
        val place = Geo.describe(places(), here.lat, here.lng)
        reply(if (place.startsWith("somewhere")) "You're $place. Say this is, and a name, to name it." else "You're at $place")
    }

    /** "Yesterday: home until 8:10 am, the gym 9:00 to 10:15 am." */
    private suspend fun Turn.whereWas(ask: Whereabouts.WhereWas): String {
        val zone = ZoneId.systemDefault()
        val from = ask.day.atStartOfDay(zone).toInstant()
        val until = ask.day.plusDays(1).atStartOfDay(zone).toInstant()
        val stays = withContext(Dispatchers.IO) {
            data.getPluginData(Entry.PLUGIN_ID).first().mapNotNull(Entry::from)
        }.filter { e -> e.source == "phone" && e.items.any { it.kind == "place" } && e.time >= from && e.time < until }
            .sortedBy { it.time }
        if (stays.isEmpty()) {
            return if (location.trail) "Nothing kept ${ask.phrase} yet" else "I haven't kept a trail. Say start tracking."
        }
        val said = stays.joinToString("; ") { e -> e.words.removePrefix("At ").removePrefix("Stayed ") }
        return "${ask.phrase.replaceFirstChar { it.uppercase() }}: $said"
    }

    private suspend fun Turn.trail(on: Boolean) {
        val change: suspend Turn.() -> Unit = {
            location.trail = on
            LocationService.refresh(context)
            reply(if (on) "Keeping your trail, only on this phone" else "Stopped keeping your trail")
        }
        if (on) withLocation(change) else change()
    }
}
