package cz.motonavigace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun newerVersionIsDetected() {
        assertTrue(UpdateCheck.isNewer("0.3.0", "0.2.0"))
        assertTrue(UpdateCheck.isNewer("v1.0.0", "0.9.9"))
        assertTrue(UpdateCheck.isNewer("0.2.1", "0.2"))
    }

    @Test
    fun numericNotAlphabeticComparison() {
        // Řetězcově by „0.10.0“ bylo menší než „0.9.0“
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"))
    }

    @Test
    fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateCheck.isNewer("0.2.0", "0.2.0"))
        assertFalse(UpdateCheck.isNewer("v0.2.0", "0.2.0"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.2.0"))
        assertFalse(UpdateCheck.isNewer("0.2", "0.2.0"))
    }
}
