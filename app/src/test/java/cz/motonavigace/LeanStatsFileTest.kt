package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LeanStatsFileTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val s = LeanStats(maxLeft = 38.4, maxRight = 41.25, maxSpeed = 31.2, maxAccel = 3.1, maxBrake = 6.05)
        val back = LeanStats.decode(s.encode())!!
        assertEquals(s.maxLeft, back.maxLeft, 0.001)
        assertEquals(s.maxRight, back.maxRight, 0.001)
        assertEquals(s.maxSpeed, back.maxSpeed, 0.001)
        assertEquals(s.maxAccel, back.maxAccel, 0.001)
        assertEquals(s.maxBrake, back.maxBrake, 0.001)
    }

    @Test
    fun brokenFilesAreTolerated() {
        assertNull(LeanStats.decode(""))
        assertNull(LeanStats.decode("nesmysl"))
        // Neznámé klíče a poškozené hodnoty se přeskočí, chybějící jsou 0
        val s = LeanStats.decode("maxLeft=12.5\nfoo=1\nmaxRight=xx")!!
        assertEquals(12.5, s.maxLeft, 0.001)
        assertEquals(0.0, s.maxRight, 0.001)
    }

    @Test
    fun leanFileSitsNextToGpx() {
        assertEquals("jizda-2026-11-03-1345.lean", TrackLibrary.leanFile(File("tracks/jizda-2026-11-03-1345.gpx")).name)
    }
}
