package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Test

class GpxStatsTest {

    /** Jízda po poledníku: každou sekundu bod o [stepDeg] stupňů severněji. */
    private fun ride(seconds: Int, stepDeg: Double, ele: (Int) -> Double = { 300.0 }) =
        (0..seconds).map { TrackPoint(49.0 + it * stepDeg, 16.0, ele(it), 1_000_000L + it * 1000L) }

    @Test
    fun maxSpeedFromOneSecondFixes() {
        // 0.0001° ≈ 11,1 m za sekundu ≈ 40 km/h
        val s = Gpx.stats(ride(60, 0.0001))
        assertEquals(40.0, s.maxSpeedKmh, 1.0)
        assertEquals(40.0, s.avgMovingSpeedKmh, 1.0)
        assertEquals(60.0, s.movingSeconds, 0.1)
    }

    @Test
    fun maxSpeedTakesFastestSection() {
        val slow = ride(30, 0.0001)
        val last = slow.last()
        // Navazující rychlejší úsek ~80 km/h
        val fast = (1..30).map { TrackPoint(last.lat + it * 0.0002, 16.0, 300.0, last.time + it * 1000L) }
        assertEquals(80.0, Gpx.stats(slow + fast).maxSpeedKmh, 2.0)
    }

    @Test
    fun ascentIgnoresSmallNoise() {
        // Šum ±4 m se nesčítá, souvislé stoupání o 100 m ano
        val noisy = Gpx.stats(ride(60, 0.0001) { if (it % 2 == 0) 300.0 else 304.0 })
        assertEquals(0.0, noisy.ascentMeters, 0.1)
        val climb = Gpx.stats(ride(100, 0.0001) { 300.0 + it })
        assertEquals(100.0, climb.ascentMeters, 11.0)
    }

    @Test
    fun noTimesMeansNoSpeeds() {
        val s = Gpx.stats(listOf(TrackPoint(49.0, 16.0), TrackPoint(49.01, 16.0)))
        assertEquals(1112.0, s.distanceMeters, 5.0)
        assertEquals(0.0, s.maxSpeedKmh, 0.0)
        assertEquals(0.0, s.durationSeconds, 0.0)
    }
}
