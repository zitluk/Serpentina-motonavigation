package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedZoomTest {
    @Test
    fun closerWhenSlowFartherWhenFast() {
        assertEquals(18.0, MapController.speedZoom(0.0), 0.001)
        assertEquals(17.0, MapController.speedZoom(50.0), 0.001)
        assertEquals(14.8, MapController.speedZoom(130.0), 0.001)
        assertEquals(14.8, MapController.speedZoom(200.0), 0.001)
        // Mezi body plynule a s rostoucí rychlostí vždy oddálenější
        assertEquals(17.25, MapController.speedZoom(40.0), 0.001)
        var last = Double.MAX_VALUE
        for (v in 0..180 step 5) {
            val z = MapController.speedZoom(v.toDouble())
            assertTrue("$v km/h", z <= last)
            last = z
        }
    }
}
