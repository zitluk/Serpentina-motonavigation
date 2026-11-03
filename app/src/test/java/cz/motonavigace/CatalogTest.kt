package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Katalog map světa z assets/world.json (vytváří tools/world_catalog.py). */
class CatalogTest {
    private val cs = Locale.forLanguageTag("cs")
    private val all = Catalog.parse(File("src/main/assets/world.json").readText())

    private fun find(q: String, locale: Locale = cs) = Catalog.search(q, all, locale).map { it.path }

    @Test
    fun coversTheWorld() {
        assertTrue(all.size > 300)
        val continents = all.map { it.continent }.toSet()
        assertTrue(continents.containsAll(listOf("europe", "asia", "africa", "north-america", "south-america", "australia-oceania")))
        // Každá mapa má body zájmu a aspoň jednu routovací dlaždici
        assertTrue(all.all { it.poiSize > 0 && it.segments.isNotEmpty() })
    }

    @Test
    fun keepsFileNamesOfAlreadyDownloadedMaps() {
        val cz = all.first { it.path == "europe/czech-republic" }
        assertEquals("czech-republic.map", cz.mapFileName)
        assertEquals("czech-republic.poi", cz.poiFileName)
        assertEquals(listOf("E10_N45.rd5", "E15_N45.rd5", "E10_N50.rd5", "E15_N50.rd5"), cz.segments)
        // Gruzie a americký stát Georgia se v telefonu nepřepíšou
        val georgias = all.filter { it.path.endsWith("/georgia") }.map { it.mapFileName }
        assertEquals(georgias.size, georgias.toSet().size)
    }

    @Test
    fun searchesCountriesWithoutDiacritics() {
        assertEquals("europe/czech-republic", find("cesko").first())
        assertEquals("europe/italy", find("Itálie").first())
        assertTrue("europe/italy/isole" in find("sardinie"))
        assertTrue("europe/germany/bayern" in find("nemecko bayern"))
        assertEquals("europe/czech-republic", find("czech", Locale.ENGLISH).first())
        assertTrue(find("xyzxyz").isEmpty())
    }

    @Test
    fun countryNamesInAppLanguage() {
        val isole = all.first { it.path == "europe/italy/isole" }
        assertEquals("Itálie – Isole", isole.label(cs))
        assertEquals("Italy – Isole", isole.label(Locale.ENGLISH))
    }
}
