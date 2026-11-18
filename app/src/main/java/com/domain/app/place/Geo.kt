// app/src/main/java/com/domain/app/place/Geo.kt
package com.domain.app.place

import com.domain.app.core.data.DataPoint
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One reading of where the phone was. */
data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val time: Instant)

/**
 * A place named by voice ("this is home"). Kept on the phone only: no map
 * service is asked what anywhere is called (docs/anthemos-2.md, "Places").
 */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Double = Geo.PLACE_RADIUS
) {
    fun toDataPoint(): DataPoint = DataPoint(
        id = id,
        pluginId = PLUGIN_ID,
        type = "place",
        value = mapOf("name" to name, "lat" to lat, "lng" to lng, "radius" to radius),
        source = "voice"
    )

    companion object {
        const val PLUGIN_ID = "place"

        fun named(name: String, at: Fix) = Place(UUID.randomUUID().toString(), name, at.lat, at.lng)

        fun from(point: DataPoint): Place? {
            if (point.pluginId != PLUGIN_ID) return null
            fun d(key: String) = (point.value[key] as? Number)?.toDouble()
            return Place(
                point.id,
                point.value["name"]?.toString() ?: return null,
                d("lat") ?: return null,
                d("lng") ?: return null,
                d("radius") ?: Geo.PLACE_RADIUS
            )
        }
    }
}

/** Time spent in one spot: where, from when, until when. */
data class Stay(val lat: Double, val lng: Double, val start: Instant, val end: Instant) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}

/** Distances, nearest places, and stays. Pure, so it can be tested. */
object Geo {
    const val PLACE_RADIUS = 120.0
    /** Readings within this of where a stay began are still that stay. */
    const val STAY_RADIUS = 150.0
    const val STAY_MINUTES = 10L
    /** Readings vaguer than this say too little to use. */
    const val WORST_ACCURACY = 250f

    fun metres(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * 6_371_000 * asin(sqrt(h))
    }

    /** The named place this spot is in, the closest if several. */
    fun placeAt(places: List<Place>, lat: Double, lng: Double): Place? =
        places.map { it to metres(it.lat, it.lng, lat, lng) }
            .filter { (place, d) -> d <= place.radius }
            .minByOrNull { it.second }?.first

    /** "Home", or "somewhere new" with how far it is from the nearest named place. */
    fun describe(places: List<Place>, lat: Double, lng: Double): String {
        placeAt(places, lat, lng)?.let { return it.name }
        val near = places.minByOrNull { metres(it.lat, it.lng, lat, lng) } ?: return "somewhere you haven't named"
        val d = metres(near.lat, near.lng, lat, lng)
        val far = if (d < 1000) "${(d / 50).toInt() * 50} m" else "%.1f km".format(java.util.Locale.ENGLISH, d / 1000)
        return "somewhere you haven't named, $far from ${near.name}"
    }

    /** Where the phone stayed at least [STAY_MINUTES], from readings in time order. */
    fun stays(fixes: List<Fix>): List<Stay> {
        val tracker = StayTracker()
        val found = fixes.mapNotNull(tracker::add).toMutableList()
        tracker.current()?.let(found::add)
        return found
    }
}

/**
 * Follows readings as they come and says when a stay has ended: the phone
 * was within [Geo.STAY_RADIUS] of one spot for [Geo.STAY_MINUTES] or more,
 * then left. Its state is small enough to keep between readings.
 */
class StayTracker(
    var lat: Double = 0.0,
    var lng: Double = 0.0,
    var start: Instant? = null,
    var last: Instant? = null
) {
    /** Takes a reading; returns the stay it ended, if any. */
    fun add(fix: Fix): Stay? {
        if (fix.accuracy > Geo.WORST_ACCURACY) return null
        val began = start
        if (began == null) {
            begin(fix)
            return null
        }
        if (Geo.metres(lat, lng, fix.lat, fix.lng) <= Geo.STAY_RADIUS) {
            last = fix.time
            return null
        }
        val ended = current()
        begin(fix)
        return ended
    }

    /** The stay going on now, once it is long enough to count. */
    fun current(): Stay? {
        val began = start ?: return null
        val until = last ?: return null
        return Stay(lat, lng, began, until).takeIf { it.minutes >= Geo.STAY_MINUTES }
    }

    private fun begin(fix: Fix) {
        lat = fix.lat
        lng = fix.lng
        start = fix.time
        last = fix.time
    }
}
