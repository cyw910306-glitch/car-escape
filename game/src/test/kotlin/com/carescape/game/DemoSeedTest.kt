package com.carescape.game

import com.carescape.core.VehicleInput
import org.junit.Assert.assertEquals
import org.junit.Test

/** tools/demo/record_demo_v2.py launches with `--el seed 35`: if the roll changes, pick a new seed and update both. */
class DemoSeedTest {
    @Test fun seed35IsTheDemoPlay() {
        val console = setOf(VehicleInput.WINDOW, VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL, VehicleInput.STEERING_HEAT, VehicleInput.SEAT_HEAT, VehicleInput.WIPER)
        val g = EscapeGame(supported = console)
        g.startDaily("map-15", 35)
        val ps = g.currentPuzzles()
        // v3: 1화 has no turn signals or wipers. tools/demo/record_demo_v2.py still plays the v2 roll (memory R,R,L / wiper 1).
        assertEquals(listOf(PuzzleSpec.Window(50), PuzzleSpec.SteeringHeat(1), PuzzleSpec.Morse("-.-."), PuzzleSpec.SeatHeat(1)), ps.map { it.spec })
        assertEquals(listOf<Int?>(null, null, null, null), ps.map { it.timedSec })
    }
}
