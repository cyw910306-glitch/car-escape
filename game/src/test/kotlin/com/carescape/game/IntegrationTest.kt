package com.carescape.game

import com.carescape.core.GameEvent
import com.carescape.core.Gear
import com.carescape.core.Phase
import com.carescape.core.SfxKind
import com.carescape.core.SimVehicleGateway
import com.carescape.core.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the app loop: Sim gateway -> simCharge (time-lapse) -> gated -> EscapeGame.onVehicle every 200 ms. */
class IntegrationTest {
    private val sim = SimVehicleGateway(VehicleState(socPercent = 42f))
    private val game = EscapeGame(random = kotlin.random.Random(3))
    private var now = 0L
    private var timelapse = 1f

    private fun tick(ms: Long) {
        var left = ms
        do {
            val d = minOf(200, left); now += d; left -= d
            sim.update { simCharge(it, d, timelapse) }
            val s = sim.state.value
            game.onVehicle(gated(s, s.gear != Gear.PARK || s.speedKmh > 0.5f), now)
        } while (left > 0)
    }
    private val phase get() = game.ui.value.phase

    @Test fun map15FromTitleToEndingWithDrivingPause() {
        tick(200)
        game.startMatching(); tick(200)
        assertEquals(Phase.MATCHING, phase)
        game.startEpisode("map-15")
        game.skipIntro(); tick(200)
        assertEquals(Phase.PUZZLE, phase)

        game.currentPuzzles().indices.forEach { i ->
            if (i == 2) { // somebody shifts to D mid-episode
                sim.update { it.copy(gear = Gear.DRIVE, speedKmh = 12f) }; tick(3_000)
                assertEquals(Phase.PAUSED_DRIVING, phase)
                val frozen = game.ui.value.elapsedSec
                tick(5_000)
                assertEquals(frozen, game.ui.value.elapsedSec)
                sim.update { it.copy(gear = Gear.PARK, speedKmh = 0f) }; tick(200)
                assertEquals(Phase.PUZZLE, phase)
            }
            assertEquals(i, game.ui.value.puzzleIndex)
            game.demoSolution(i).forEach { step -> sim.update(step.change); tick(step.waitMs) }
            assertEquals("puzzle $i", Phase.SOLVED, phase)
            tick(EscapeGame.SOLVED_MS + 200)
        }
        assertEquals(Phase.ENDING, phase)
        assertTrue(game.ui.value.endingText.startsWith("모든 퍼즐을 풀었어요"))

        timelapse = TIMELAPSE // demo panel x30: 42% -> 80% within about 80 s
        tick(120_000)
        assertEquals(80f, sim.state.value.socPercent, 0.01f)
        assertTrue(game.ui.value.endingText, game.ui.value.endingText.startsWith("80% 충전 완료!"))
        assertTrue(GameEvent.Sfx(SfxKind.ENDING) in game.log)
    }
}
