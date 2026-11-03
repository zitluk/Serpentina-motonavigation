package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class SunTest {
    private val pragueLat = 50.087
    private val pragueLon = 14.421

    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun dayAndNightInPrague() {
        assertFalse(Sun.isNight(pragueLat, pragueLon, at("2026-06-21T12:00:00+02:00")))
        assertTrue(Sun.isNight(pragueLat, pragueLon, at("2026-06-21T23:30:00+02:00")))
        assertFalse(Sun.isNight(pragueLat, pragueLon, at("2026-12-21T12:00:00+01:00")))
        // V prosinci je v Praze v 17:30 už tma, v červnu ještě dlouho světlo
        assertTrue(Sun.isNight(pragueLat, pragueLon, at("2026-12-21T17:30:00+01:00")))
        assertFalse(Sun.isNight(pragueLat, pragueLon, at("2026-06-21T20:30:00+02:00")))
    }

    @Test
    fun elevationNearSunsetIsAroundZero() {
        // Západ slunce v Praze 21. 6. kolem 21:15 SELČ
        assertEquals(0.0, Sun.elevation(pragueLat, pragueLon, at("2026-06-21T21:15:00+02:00")), 1.5)
        // Polední výška v létě: 90 − 50,1 + 23,4 ≈ 63°
        assertEquals(63.3, Sun.elevation(pragueLat, pragueLon, at("2026-06-21T13:03:00+02:00")), 1.5)
    }
}
