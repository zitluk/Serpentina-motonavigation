package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LeanMeterTest {

    /** Ustálená zatáčka: telefon na řídítkách vidí zdánlivou tíži podél osy motorky. */
    private fun steadyTurn(meter: LeanMeter, speed: Double, radius: Double, right: Boolean) {
        val phi = atan(speed * speed / (radius * LeanMeter.G))
        val yaw = (if (right) -1 else 1) * speed / radius
        val apparent = sqrt(LeanMeter.G * LeanMeter.G + (speed * speed / radius).let { it * it })
        val s = if (right) 1 else -1
        meter.onSpeed(speed, 1_000_000_000L)
        meter.onSpeed(speed, 2_000_000_000L)
        var t = 2_000_000_000L
        repeat(200) {
            meter.onGravity(0f, apparent.toFloat(), 0f)
            // Svislá osa světa v souřadnicích nakloněného telefonu: (-sin φ, cos φ, 0) pro náklon doprava
            meter.onGyro((yaw * -s * sin(phi)).toFloat(), (yaw * cos(phi)).toFloat(), 0f, t)
            t += 20_000_000L
        }
    }

    @Test
    fun rightTurnGivesPositiveLean() {
        val m = LeanMeter()
        steadyTurn(m, speed = 20.0, radius = 50.0, right = true)
        assertEquals(39.2, m.lean, 0.5)
        assertEquals(39.2, m.stats.maxRight, 0.5)
        assertEquals(0.0, m.stats.maxLeft, 0.01)
    }

    @Test
    fun leftTurnGivesNegativeLean() {
        val m = LeanMeter()
        steadyTurn(m, speed = 15.0, radius = 60.0, right = false)
        assertEquals(-20.9, m.lean, 0.5)
        assertEquals(20.9, m.stats.maxLeft, 0.5)
    }

    @Test
    fun standingUsesTiltAndCalibration() {
        val m = LeanMeter()
        // Telefon v držáku mírně pootočený doleva
        val tilt = Math.toRadians(5.0)
        repeat(100) { m.onGravity((LeanMeter.G * sin(tilt)).toFloat(), (LeanMeter.G * cos(tilt)).toFloat(), 0f) }
        assertEquals(-5.0, m.lean, 0.1)
        m.calibrate()
        repeat(100) { m.onGravity((LeanMeter.G * sin(tilt)).toFloat(), (LeanMeter.G * cos(tilt)).toFloat(), 0f) }
        assertEquals(0.0, m.lean, 0.1)
        // Při stání se náklon do maxim nepočítá
        assertEquals(0.0, m.stats.maxLeft, 0.01)
    }

    @Test
    fun withoutGyroLeanComesFromGpsCourse() {
        val m = LeanMeter()
        // Telefon bez gyroskopu: tíže podél osy motorky, zatáčka doprava 20 m/s, poloměr 50 m
        val speed = 20.0
        val radius = 50.0
        var t = 1_000_000_000L
        var course = 0.0
        repeat(10) {
            m.onGravity(0f, LeanMeter.G.toFloat(), 0f, t)
            m.onSpeed(speed, t)
            m.onCourse(course, t)
            course += Math.toDegrees(speed / radius)
            t += 1_000_000_000L
        }
        assertEquals(39.2, m.lean, 1.0)
    }

    @Test
    fun handlebarJitterIsSmoothedOut() {
        val m = LeanMeter()
        m.onSpeed(20.0, 1_000_000_000L)
        var t = 1_000_000_000L
        var maxAbs = 0.0
        // Rovně, ale řídítka a vibrace střídavě točí telefonem o ±0,3 rad/s
        repeat(300) { i ->
            m.onGravity(0f, LeanMeter.G.toFloat(), 0f, t)
            m.onGyro(0f, (if (i % 10 < 5) 0.3f else -0.3f), 0f, t)
            t += 20_000_000L
            if (i > 50) maxAbs = maxOf(maxAbs, kotlin.math.abs(m.lean))
        }
        assertTrue("náklon na rovince poskakuje o $maxAbs°", maxAbs < 5.0)
    }

    @Test
    fun staleSpeedFallsBackToTilt() {
        val m = LeanMeter()
        // Poslední rychlost z jízdy, pak signál zmizel (garáž) a jezdec telefon nakloní rukou
        m.onSpeed(15.0, 1_000_000_000L)
        val tilt = Math.toRadians(30.0)
        var t = 10_000_000_000L
        repeat(200) {
            m.onGravity((-LeanMeter.G * sin(tilt)).toFloat(), (LeanMeter.G * cos(tilt)).toFloat(), 0f)
            m.onGyro(0f, 0f, 0f, t)
            t += 20_000_000L
        }
        assertEquals(30.0, m.lean, 0.5)
    }

    @Test
    fun accelerationAndBrakingFromSpeed() {
        val m = LeanMeter()
        var t = 0L
        listOf(5.0, 8.0, 11.0, 14.0, 14.0, 9.0, 4.0).forEach { v ->
            t += 1_000_000_000L
            m.onSpeed(v, t)
        }
        assertTrue(m.stats.maxAccel in 2.0..3.0)
        assertTrue(m.stats.maxBrake in 3.0..5.0)
        assertEquals(14.0, m.stats.maxSpeed, 0.01)
    }
}
