package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class PoiSearchTest {
    private fun score(name: String, q: String, vararg tags: Pair<String, String>) =
        PoiSearch.score(mapOf("name" to name, *tags), PoiSearch.normalize(name), PoiSearch.normalize(q))

    @Test
    fun cityByPrefixBeatsSameNamedBusStop() {
        val city = score("Hradec Králové", "Hradec", "place" to "city")
        val stop = score("Hradec", "Hradec", "highway" to "bus_stop")
        assertTrue("Hradec Králové má být před zastávkou Hradec", city < stop)
    }

    @Test
    fun exactVillageBeatsCityByPrefix() {
        assertTrue(score("Hradec", "Hradec", "place" to "village") < score("Hradec Králové", "Hradec", "place" to "city"))
    }

    @Test
    fun shopBeatsHamletWithSimilarName() {
        val shop = score("Lidl", "Lidl", "shop" to "supermarket")
        val hamlet = score("Lídlovy Dvory", "Lidl", "place" to "hamlet")
        assertTrue("Lidl má být před osadou Lídlovy Dvory", shop < hamlet)
    }

    @Test
    fun sameNamedVillagesFarApartAreAllKept() {
        val a = Place("Nová Ves", "Obec", LatLong(49.90, 15.80), 1000.0)
        val b = Place("Nová Ves", "Obec", LatLong(48.97, 14.47), 2000.0)
        assertEquals(2, PoiSearch.dedupeNearby(listOf(a, b)).size)
    }

    @Test
    fun duplicatesCloseTogetherAreMerged() {
        // Zastávka pro oba směry: stejné jméno i druh, pár desítek metrů od sebe
        val a = Place("Náměstí", "Zastávka", LatLong(50.0000, 14.0000), 100.0)
        val b = Place("Náměstí", "Zastávka", LatLong(50.0003, 14.0002), 130.0)
        val other = Place("Náměstí", "Restaurace", LatLong(50.0001, 14.0001), 110.0)
        assertEquals(listOf(a, other), PoiSearch.dedupeNearby(listOf(a, b, other)))
    }

    @Test
    fun qualifierFromWikipediaTitle() {
        assertEquals("okres České Budějovice",
            PoiSearch.placeQualifier(mapOf("name" to "Nová Ves", "wikipedia" to "cs:Nová Ves (okres České Budějovice)")))
        assertEquals("Nasavrky", PoiSearch.placeQualifier(mapOf("name" to "Nová Ves", "wikipedia" to "cs:Nová Ves (Nasavrky)")))
        assertNull(PoiSearch.placeQualifier(mapOf("name" to "Brno", "wikipedia" to "cs:Brno")))
        assertNull(PoiSearch.placeQualifier(mapOf("name" to "Lhota")))
    }
}
