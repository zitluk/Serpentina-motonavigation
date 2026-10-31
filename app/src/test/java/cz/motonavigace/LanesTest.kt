package cz.motonavigace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanesTest {
    private fun m(cmd: String, exit: Int = 0) = Maneuver(0, 0.0, cmd, exit)

    @Test
    fun singleLaneRoadHasNoHint() {
        assertNull(LaneHint.forManeuver(m("TL"), "highway=primary lanes=2"))
        assertNull(LaneHint.forManeuver(m("TL"), "highway=primary"))
    }

    @Test
    fun leftTurnOnTwoLanesPerDirection() {
        val h = LaneHint.forManeuver(m("TL"), "highway=primary lanes=4")!!
        assertEquals(2, h.count)
        assertEquals(setOf(0), h.recommended)
    }

    @Test
    fun motorwayExitRightUsesRightHalf() {
        val h = LaneHint.forManeuver(m("ER"), "highway=motorway lanes=3")!!
        assertEquals(3, h.count)
        assertEquals(setOf(1, 2), h.recommended)
        assertEquals(LaneHint.Side.RIGHT, h.side)
    }

    @Test
    fun roundaboutByExit() {
        val tags = "highway=primary oneway=yes lanes=2"
        assertEquals(setOf(1), LaneHint.forManeuver(m("RNDB", 1), tags)!!.recommended)
        assertEquals(setOf(0), LaneHint.forManeuver(m("RNDB", 3), tags)!!.recommended)
        assertEquals(setOf(0, 1), LaneHint.forManeuver(m("RNDB", 2), tags)!!.recommended)
    }

    @Test
    fun straightHasNoHint() {
        assertNull(LaneHint.forManeuver(m("C"), "highway=motorway lanes=3"))
    }
}
