package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class SpeedCamerasTest {
    private val here = LatLong(50.0, 14.0)

    @Test
    fun parsesLimitDirectionAndSection() {
        val cam = SpeedCameras.parse(here, "average_speed=70\rdirection=65\rhighway=speed_camera\rmaxspeed=70")
        assertEquals(70, cam.maxspeed)
        assertEquals(65f, cam.direction!!, 0.01f)
        assertTrue(cam.section)
    }

    @Test
    fun unknownValuesStayNull() {
        val cam = SpeedCameras.parse(here, "direction=both\rhighway=speed_camera\rmaxspeed=CZ:urban")
        assertNull(cam.maxspeed)
        assertNull(cam.direction)
        assertFalse(cam.section)
        assertEquals(50, SpeedCameras.parse(here, "highway=speed_camera\rmaxspeed=50 km/h").maxspeed)
        assertEquals(270f, SpeedCameras.parse(here, "direction=W\rhighway=speed_camera").direction!!, 0.01f)
        assertTrue(SpeedCameras.parse(here, "highway=speed_camera\rnote=Úsekové měření začátek").section)
    }

    /** Trasa 2 km na sever po poledníku 14° (body po 100 m). */
    private val northRoute: Route = run {
        val pts = (0..20).map { LatLong(50.0 + it * 100 / 111_200.0, 14.0) }
        val dist = DoubleArray(pts.size)
        for (i in 1 until pts.size) dist[i] = dist[i - 1] + pts[i - 1].sphericalDistance(pts[i])
        Route(pts, dist, DoubleArray(pts.size), emptyList(), dist.last().toInt())
    }

    private fun camAt(meters: Double, eastMeters: Double, direction: Float?) = SpeedCamera(
        LatLong(50.0 + meters / 111_200.0, 14.0 + eastMeters / (111_320.0 * Math.cos(Math.toRadians(50.0)))),
        50, direction, false,
    )

    @Test
    fun camerasOnRouteInDrivingDirection() {
        val cams = listOf(
            camAt(1500.0, 8.0, null), // u silnice, oba směry
            camAt(500.0, -5.0, 10f), // měří náš směr (sever)
            camAt(800.0, 5.0, 180f), // měří protisměr
            camAt(1200.0, 150.0, null), // souběžná silnice
        )
        val found = SpeedCameras.onRoute(northRoute, cams)
        assertEquals(2, found.size)
        assertEquals(500.0, found[0].along, 15.0)
        assertEquals(1500.0, found[1].along, 15.0)
    }
}
