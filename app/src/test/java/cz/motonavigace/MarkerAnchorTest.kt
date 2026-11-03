package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerAnchorTest {
    @Test
    fun anchorIsUnderTheFrontWheel() {
        // Snímek 0: motorka jede „do obrazovky“, přední kolo je nad středem motorky
        val (x0, y0) = MarkerIcons.anchor3d(0)
        assertEquals(0.5f, x0, 0.001f)
        assertTrue(y0 < MarkerIcons.ANCHOR_3D_Y)
        // Snímek 6 (90° doprava): přední kolo vpravo, stejně vysoko jako střed
        val (x6, y6) = MarkerIcons.anchor3d(6)
        assertTrue(x6 > 0.5f)
        assertEquals(MarkerIcons.ANCHOR_3D_Y, y6, 0.001f)
        // Snímek 12 (k jezdci): přední kolo pod středem
        assertTrue(MarkerIcons.anchor3d(12).second > MarkerIcons.ANCHOR_3D_Y)
    }

    @Test
    fun pointAlongRoute() {
        val pts = (0..10).map { org.mapsforge.core.model.LatLong(50.0 + it * 100 / 111_200.0, 14.0) }
        val dist = DoubleArray(pts.size)
        for (i in 1 until pts.size) dist[i] = dist[i - 1] + pts[i - 1].sphericalDistance(pts[i])
        val r = Route(pts, dist, DoubleArray(pts.size), emptyList(), dist.last().toInt())
        assertEquals(250.0, pts[0].sphericalDistance(r.pointAt(250.0)), 1.0)
        assertEquals(pts.last(), r.pointAt(5000.0))
        assertEquals(pts.first(), r.pointAt(-5.0))
    }
}
