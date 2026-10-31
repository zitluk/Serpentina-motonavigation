package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong
import kotlin.math.PI

class RoundTripTest {
    private val start = LatLong(49.1951, 16.6068)

    @Test
    fun pointsLieOnCircleThroughStart() {
        val radius = 10_000.0
        val pts = RoundTripPlanner.circle(start, radius, direction = 45.0, count = 5)
        assertEquals(5, pts.size)
        // Střed kružnice je o poloměr od startu – každý bod i start jsou od něj stejně daleko
        val center = RoundTripPlanner.circle(start, radius, 45.0, 1).first().let {
            // bod naproti startu: střed leží v polovině mezi startem a ním
            LatLong((start.latitude + it.latitude) / 2, (start.longitude + it.longitude) / 2)
        }
        for (p in pts) assertEquals(radius, center.sphericalDistance(p), radius * 0.02)
        assertEquals(radius, center.sphericalDistance(start), radius * 0.02)
    }

    @Test
    fun polygonLengthMatchesCircumference() {
        val radius = 5_000.0
        val ring = listOf(start) + RoundTripPlanner.circle(start, radius, 200.0, 8) + start
        val length = ring.zipWithNext { a, b -> a.sphericalDistance(b) }.sum()
        // Devítiúhelník vepsaný do kružnice má obvod jen o pár procent kratší než kružnice
        assertEquals(2 * PI * radius, length, 2 * PI * radius * 0.05)
    }
}
