package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchIndexTest {
    @Test
    fun queryIsSplitIntoWords() {
        assertEquals(listOf("nova", "ves"), SearchIndex.tokens("nova ves"))
        assertEquals(listOf("praha", "4"), SearchIndex.tokens("praha 4"))
        assertEquals(listOf("ceske", "budejovice"), SearchIndex.tokens("ceske-budejovice"))
    }

    @Test
    fun ftsSyntaxIsStripped() {
        // Uživatel nesmí napsat dotaz, který by SQLite FTS vyložil jako příkaz
        assertEquals(listOf("brno", "or", "praha"), SearchIndex.tokens("brno\" OR praha*"))
        assertEquals(emptyList<String>(), SearchIndex.tokens("*\"-"))
    }
}
