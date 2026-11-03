package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RideTrackerTest {
    private fun north(m: Double) = LatLong(50.0 + m / 111_200.0, 14.0)

    @Test
    fun countsDistanceAndMovingTimeWithoutStops() {
        val t = RideTracker(0)
        // 60 s jízdy rychlostí 20 m/s, pak 60 s stání s chvěním polohy
        for (s in 0..60) t.onFix(north(s * 20.0), 5f, 20f, s * 1000L)
        for (s in 61..120) t.onFix(north(1200.0 + (s % 2) * 2.0), 5f, 0f, s * 1000L)
        val r = t.summary(LeanStats(), 120_000)
        assertEquals(1200.0, r.distanceMeters, 20.0)
        assertEquals(60.0, r.movingSeconds, 1.0)
        assertEquals(120.0, r.totalSeconds, 0.1)
        assertEquals(20.0, r.avgSpeed, 0.5)
    }

    @Test
    fun ignoresInaccurateFixes() {
        val t = RideTracker(0)
        t.onFix(north(0.0), 5f, 10f, 0)
        t.onFix(north(5000.0), 200f, 10f, 1000) // skok špatné polohy
        t.onFix(north(10.0), 5f, 10f, 1000)
        assertEquals(10.0, t.summary(LeanStats(), 1000).distanceMeters, 1.0)
    }
}
