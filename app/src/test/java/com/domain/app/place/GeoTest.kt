package com.domain.app.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GeoTest {
    private val start = Instant.parse("2026-11-18T08:00:00Z")
    private fun fix(lat: Double, lng: Double, minutes: Long, accuracy: Float = 10f) =
        Fix(lat, lng, accuracy, start.plusSeconds(minutes * 60))

    @Test
    fun `distance`() {
        // About 1.1 km per hundredth of a degree of latitude.
        val d = Geo.metres(-33.86, 151.20, -33.87, 151.20)
        assertTrue(d in 1100.0..1120.0)
    }

    @Test
    fun `named places are found by how close they are`() {
        val home = Place("1", "home", -33.8600, 151.2000)
        val gym = Place("2", "gym", -33.8700, 151.2000)
        assertEquals("home", Geo.placeAt(listOf(home, gym), -33.8605, 151.2001)?.name)
        assertNull(Geo.placeAt(listOf(home, gym), -33.8650, 151.2000))
        assertEquals("somewhere you haven't named, 500 m from home", Geo.describe(listOf(home, gym), -33.8648, 151.2000))
    }

    @Test
    fun `a stay is time in one spot, ended by leaving`() {
        val fixes = listOf(
            fix(-33.8600, 151.2000, 0),
            fix(-33.8601, 151.2001, 20),
            fix(-33.8600, 151.2000, 45),
            // A blurry reading far off is ignored.
            fix(-33.9000, 151.2000, 50, accuracy = 900f),
            fix(-33.8700, 151.2000, 60),
            fix(-33.8800, 151.2000, 63)
        )
        val stays = Geo.stays(fixes)
        assertEquals(1, stays.size)
        assertEquals(45, stays[0].minutes)
    }
}
