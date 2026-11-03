package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightColorsTest {
    private fun lightness(c: Int): Double {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return (maxOf(r, g, b) + minOf(r, g, b)) / 510.0
    }

    @Test
    fun areasGetDarkRoadsStayVisibleTextInverts() {
        val land = NightColors.transform(NightColors.Kind.AREA, 0xFFF8F4F0.toInt())
        val road = NightColors.transform(NightColors.Kind.LINE, 0xFFFFFFFF.toInt())
        assertTrue(lightness(land) < 0.3)
        // Silnice musí být výrazně světlejší než plocha pod ní
        assertTrue(lightness(road) - lightness(land) > 0.3)
        assertTrue(lightness(NightColors.transform(NightColors.Kind.TEXT, 0xFF000000.toInt())) > 0.9)
        assertTrue(lightness(NightColors.transform(NightColors.Kind.TEXT, 0xFFFFFFFF.toInt())) < 0.3)
    }

    @Test
    fun keepsAlphaAndHue() {
        val water = NightColors.transform(NightColors.Kind.AREA, 0x80B5D0D0.toInt())
        assertEquals(0x80, water ushr 24)
        // Modrozelená voda zůstane modrozelená (modrá i zelená složka nad červenou)
        val r = water shr 16 and 0xFF; val g = water shr 8 and 0xFF; val b = water and 0xFF
        assertTrue(g > r && b > r)
        val primary = NightColors.transform(NightColors.Kind.LINE, 0xFFF7A35C.toInt())
        assertTrue((primary shr 16 and 0xFF) > (primary and 0xFF)) // oranžová zůstane oranžová
    }
}
