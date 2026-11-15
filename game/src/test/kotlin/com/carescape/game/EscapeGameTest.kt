package com.carescape.game

import com.carescape.core.CarSeat
import com.carescape.core.GameEvent
import com.carescape.core.Gear
import com.carescape.core.Phase
import com.carescape.core.PuzzleVisual
import com.carescape.core.SfxKind
import com.carescape.core.TurnSignal
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Drives a game with fake time: every wait is ticked at 100 ms. */
class Rig(val game: EscapeGame = EscapeGame(random = Random(7)), var s: VehicleState = VehicleState(socPercent = 40f)) {
    var now = 1_000_000L
    fun tick(ms: Long = 100) {
        var left = ms
        do { val d = minOf(100, left); now += d; left -= d; game.onVehicle(s, now) } while (left > 0)
    }
    fun set(ms: Long = 100, f: (VehicleState) -> VehicleState) { s = f(s); tick(ms) }
    fun play(steps: List<DemoStep>) = steps.forEach { set(it.waitMs, it.change) }
    val phase get() = game.ui.value.phase
    fun sfx(k: SfxKind) = game.log.count { it == GameEvent.Sfx(k) }
    fun toPuzzle(id: String) { tick(); game.startEpisode(id); tick(EscapeGame.INTRO_MS + 100) }
}

internal fun p(spec: PuzzleSpec) = Puzzle("이야기", "보상", spec, 0)
internal fun single(spec: PuzzleSpec, start: VehicleState = VehicleState(socPercent = 40f)): Rig {
    val ep = Episode("t", "테스트", 15, "인트로", "끝", 0, listOf(p(spec), p(PuzzleSpec.Wiper(9))), fixed = true)
    return Rig(EscapeGame(listOf(ep)), start).also { it.toPuzzle("t") }
}

class PuzzleTypeTest {
    @Test fun windowNeedsHoldInBand() {
        val r = single(PuzzleSpec.Window(50))
        r.set(1_000) { it.copy(windowDriverPercent = 48) }
        assertEquals(Phase.PUZZLE, r.phase)
        assertEquals(PuzzleVisual.Dial(50, 48, 10), r.game.ui.value.visual)
        r.set(300) { it.copy(windowDriverPercent = 80) } // left band: hold resets
        r.set(1_500) { it.copy(windowDriverPercent = 52) }
        assertEquals(Phase.PUZZLE, r.phase)
        r.tick(600)
        assertEquals(Phase.SOLVED, r.phase)
        assertEquals(1, r.sfx(SfxKind.CORRECT))
    }

    @Test fun windowAlreadyInBandIsNotAutoSolved() {
        val r = single(PuzzleSpec.Window(50), VehicleState(socPercent = 40f, windowDriverPercent = 50))
        r.tick(5_000)
        assertEquals(Phase.PUZZLE, r.phase)
        r.play(PuzzleSpec.Window(50).demo())
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun morseShortLongAndWrong() {
        val r = single(PuzzleSpec.Morse(".-"))
        r.set(300) { it.copy(readingLightOn = true) } // 0.3 s = short
        r.set(300) { it.copy(readingLightOn = false) }
        assertEquals(PuzzleVisual.Morse(".-", "."), r.game.ui.value.visual)
        r.set(300) { it.copy(readingLightOn = true) } // short again: wrong
        r.set(300) { it.copy(readingLightOn = false) }
        assertEquals(1, r.sfx(SfxKind.WRONG))
        assertEquals(PuzzleVisual.Morse(".-", ""), r.game.ui.value.visual)
        r.set(400) { it.copy(readingLightOn = true) }
        r.set(300) { it.copy(readingLightOn = false) }
        r.set(800) { it.copy(readingLightOn = true) } // 0.8 s = long
        r.set(100) { it.copy(readingLightOn = false) }
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun morseBoundaryAndGapReset() {
        val r = single(PuzzleSpec.Morse("-"))
        r.set(500) { it.copy(readingLightOn = true) } // 0.5 s: short, wrong
        r.set(100) { it.copy(readingLightOn = false) }
        assertEquals(1, r.sfx(SfxKind.WRONG))
        r.set(600) { it.copy(readingLightOn = true) } // exactly 0.6 s: long
        r.set(100) { it.copy(readingLightOn = false) }
        assertEquals(Phase.SOLVED, r.phase)

        val g = single(PuzzleSpec.Morse(".."))
        g.set(200) { it.copy(readingLightOn = true) }
        g.set(2_600) { it.copy(readingLightOn = false) } // pause > 2.5 s
        assertEquals(PuzzleVisual.Morse("..", ""), g.game.ui.value.visual)
        g.set(200) { it.copy(readingLightOn = true) }
        g.set(100) { it.copy(readingLightOn = false) }
        assertEquals(Phase.PUZZLE, g.phase)
    }

    @Test fun morseBaselineLightOnIgnored() {
        val r = single(PuzzleSpec.Morse("."), VehicleState(socPercent = 40f, readingLightOn = true))
        r.set(100) { it.copy(readingLightOn = false) } // release of a light that was on before the puzzle
        assertEquals(Phase.PUZZLE, r.phase)
        assertEquals(0, r.sfx(SfxKind.WRONG))
    }

    @Test fun signalSequence() {
        val r = single(PuzzleSpec.Signals(listOf("L", "R", "R")))
        r.set { it.copy(turnSignal = TurnSignal.LEFT) }
        r.set { it } // no change, no input
        r.set { it.copy(turnSignal = TurnSignal.RIGHT) }
        r.set { it.copy(turnSignal = TurnSignal.LEFT) } // wrong: start over
        assertEquals(1, r.sfx(SfxKind.WRONG))
        assertEquals(PuzzleVisual.Sequence(listOf("L", "R", "R"), emptyList()), r.game.ui.value.visual)
        r.set { it.copy(turnSignal = TurnSignal.OFF) }
        r.set { it.copy(turnSignal = TurnSignal.LEFT) }
        r.set { it.copy(turnSignal = TurnSignal.RIGHT) }
        r.set { it.copy(turnSignal = TurnSignal.OFF) }
        r.set { it.copy(turnSignal = TurnSignal.RIGHT) }
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun signalBaselineDoesNotCount() {
        val r = single(PuzzleSpec.Signals(listOf("L")), VehicleState(socPercent = 40f, turnSignal = TurnSignal.LEFT))
        r.tick(1_000)
        assertEquals(Phase.PUZZLE, r.phase)
        r.set { it.copy(turnSignal = TurnSignal.OFF) }
        r.set { it.copy(turnSignal = TurnSignal.LEFT) }
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun steeringAndSeatLevels() {
        val st = single(PuzzleSpec.SteeringHeat(1))
        st.set(1_000) { it.copy(steeringHeatLevel = 1) }
        assertEquals(Phase.PUZZLE, st.phase)
        st.tick(600)
        assertEquals(Phase.SOLVED, st.phase)

        val seat = single(PuzzleSpec.SeatHeat(2), VehicleState(socPercent = 40f, seatHeatDriverLevel = 2))
        seat.tick(3_000)
        assertEquals(Phase.PUZZLE, seat.phase) // baseline already at target
        seat.set(500) { it.copy(seatHeatDriverLevel = 3) }
        seat.set(2_000) { it.copy(seatHeatDriverLevel = 2) }
        assertEquals(Phase.SOLVED, seat.phase)
    }

    @Test fun wiperCountRightAndWrong() {
        val r = single(PuzzleSpec.Wiper(2))
        repeat(3) { r.set(300) { it.copy(wiperOn = true) }; r.set(300) { it.copy(wiperOn = false) } }
        assertEquals(1, r.sfx(SfxKind.WRONG)) // third wipe overshoots
        r.tick(2_500)
        r.set(300) { it.copy(wiperOn = true) }; r.set(300) { it.copy(wiperOn = false) }
        r.tick(2_500)
        assertEquals(2, r.sfx(SfxKind.WRONG)) // one wipe is too few
        repeat(2) { r.set(300) { it.copy(wiperOn = true) }; r.set(300) { it.copy(wiperOn = false) } }
        r.tick(2_100)
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun wiperBaselineOnIgnored() {
        val r = single(PuzzleSpec.Wiper(1), VehicleState(socPercent = 40f, wiperOn = true))
        r.set(300) { it.copy(wiperOn = false) }
        r.tick(3_000)
        assertEquals(Phase.PUZZLE, r.phase)
    }
}

class FlowTest {
    @Test fun demoSolvesEveryPuzzleOfEveryEpisode() {
        for (seed in 1..12) for (ep in Episodes.all) {
            val occ = if (seed % 2 == 0) setOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_2_RIGHT, CarSeat.ROW_3_LEFT) else emptySet()
            val r = Rig(EscapeGame(random = Random(seed)), VehicleState(socPercent = 40f, occupied = occ))
            r.toPuzzle(ep.id)
            ep.puzzles.indices.forEach { i ->
                assertEquals("${ep.id} #$i", Phase.PUZZLE, r.phase)
                assertEquals(i, r.game.ui.value.puzzleIndex)
                r.play(r.game.demoSolution(i))
                assertEquals("${ep.id} #$i", Phase.SOLVED, r.phase)
                r.tick(EscapeGame.SOLVED_MS + 100)
            }
            assertEquals(Phase.ENDING, r.phase) // still charging: waiting state
            assertTrue(r.game.ui.value.endingText.startsWith("모든 퍼즐을 풀었어요"))
            assertTrue(r.game.ui.value.waitingForCharge)
            r.set { it.copy(socPercent = 80f) }
            assertTrue(r.game.ui.value.endingText.startsWith("80% 충전 완료!"))
            assertFalse(r.game.ui.value.waitingForCharge)
            assertEquals(1, r.sfx(SfxKind.ENDING))
        }
    }

    @Test fun bonusEndingWhenChargeFinishesFirst() {
        val r = Rig()
        r.toPuzzle("map-15")
        r.play(r.game.demoSolution(0))
        r.set { it.copy(socPercent = 80f) }
        assertEquals(Phase.ENDING, r.phase)
        val text = r.game.ui.value.endingText
        assertTrue(text, text.startsWith("보너스 엔딩!"))
        assertFalse(r.game.ui.value.waitingForCharge)
        val left = r.game.currentPuzzles().drop(1).joinToString(", ") { it.title }
        assertTrue(text, text.contains("남은 퍼즐 3개: $left"))
        assertEquals(1, r.game.ui.value.stars) // unsolved count as skips
    }

    private fun allSolvedWaiting(): Rig = Rig().apply {
        toPuzzle("map-15")
        Episodes.map15.puzzles.indices.forEach { play(game.demoSolution(it)); tick(EscapeGame.SOLVED_MS + 100) }
        assertEquals(Phase.ENDING, phase)
        assertTrue(game.ui.value.waitingForCharge)
    }

    @Test fun chargerPulledWhileParkedGivesStoppedEnding() {
        val r = allSolvedWaiting()
        r.set { it.copy(charging = false, socPercent = 63.4f) }
        val text = r.game.ui.value.endingText
        assertTrue(text, text.startsWith("충전이 멈췄어요. 지금 배터리 63%."))
        assertTrue(text, text.contains("퍼즐 4개 중 4개 완료!"))
        assertFalse(text, text.contains("충전 완료"))
        // speech = the screen text in spoken form, then the 원래대로 출발 line (heaters were turned on)
        val said = r.game.log.filterIsInstance<GameEvent.Speak>().last().text
        assertTrue(said, said.startsWith(text.replace("배터리 63%.", "배터리는 63퍼센트예요.") + " 마지막 미션"))
        assertTrue(text, text.endsWith(Episodes.map15.teaser))
        assertEquals(1, r.sfx(SfxKind.ENDING))
    }

    @Test fun chargerOffWhileDrivingStaysPausedThenGraceThenStopped() {
        val r = allSolvedWaiting()
        r.set(30_000) { it.copy(gear = Gear.DRIVE, charging = false) }
        assertEquals(Phase.PAUSED_DRIVING, r.phase)
        assertEquals(0, r.sfx(SfxKind.ENDING))
        r.set(EscapeGame.REPLUG_MS - 1_000) { it.copy(gear = Gear.PARK) }
        assertEquals(Phase.ENDING, r.phase) // back in P: still waiting for a re-plug
        assertTrue(r.game.ui.value.waitingForCharge)
        r.tick(2_000)
        assertFalse(r.game.ui.value.waitingForCharge)
        assertTrue(r.game.ui.value.endingText.startsWith("충전이 멈췄어요."))
    }

    @Test fun replugAfterDriveKeepsWaitingForTarget() {
        val r = allSolvedWaiting()
        r.set { it.copy(gear = Gear.DRIVE, charging = false) }
        r.set(10_000) { it.copy(gear = Gear.PARK) }
        r.set(EscapeGame.REPLUG_MS) { it.copy(charging = true) }
        assertTrue(r.game.ui.value.waitingForCharge)
        r.set { it.copy(socPercent = 80f) }
        assertTrue(r.game.ui.value.endingText.startsWith("80% 충전 완료!"))
    }

    @Test fun drivingPausesAndResumes() {
        val r = Rig()
        r.toPuzzle("map-15")
        val before = r.game.ui.value.elapsedSec
        r.set(10_000) { it.copy(gear = Gear.DRIVE) }
        assertEquals(Phase.PAUSED_DRIVING, r.phase)
        assertTrue(r.game.log.contains(GameEvent.AmbientRestore))
        r.set(10_000) { it.copy(gear = Gear.PARK, speedKmh = 3f) } // P but still rolling
        assertEquals(Phase.PAUSED_DRIVING, r.phase)
        assertEquals(before, r.game.ui.value.elapsedSec)
        r.set { it.copy(speedKmh = 0f) }
        assertEquals(Phase.PUZZLE, r.phase)
        r.play(r.game.demoSolution(0)) // still solvable after resume
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun signalsUsedWhileDrivingDoNotCount() {
        val r = single(PuzzleSpec.Signals(listOf("L", "R", "L")))
        r.set { it.copy(gear = Gear.DRIVE, turnSignal = TurnSignal.LEFT) }
        r.set { it.copy(gear = Gear.PARK) }
        r.set { it.copy(turnSignal = TurnSignal.OFF) }
        assertEquals(PuzzleVisual.Sequence(listOf("L", "R", "L"), emptyList()), r.game.ui.value.visual)
    }

    @Test fun hintsAndSkip() {
        val r = Rig()
        r.toPuzzle("map-15")
        repeat(4) { r.game.requestHint() }
        assertEquals(3, r.game.ui.value.hintsUsed)
        assertTrue(r.game.ui.value.hintText!!.startsWith("정답"))
        r.game.skipPuzzle()
        assertEquals(1, r.game.ui.value.puzzleIndex)
        assertEquals(0, r.game.ui.value.hintsUsed)
        assertEquals(1, r.sfx(SfxKind.UNLOCK))
    }

    @Test fun matchingPicksByEta() {
        val g = EscapeGame()
        assertEquals("map-15", g.recommend().id) // unknown ETA
        var t = 0L
        var soc = 40f
        repeat(60) { g.onVehicle(VehicleState(socPercent = soc), t); t += 10_000; soc += 0.1f } // 0.6 %/min
        assertEquals("letter-30", g.recommend().id) // ~66 min left
        g.startMatching()
        assertEquals(Phase.MATCHING, g.ui.value.phase)
        assertEquals(30, g.ui.value.episodeMinutes)

        val fast = EscapeGame()
        soc = 70f; t = 0
        repeat(20) { fast.onVehicle(VehicleState(socPercent = soc), t); t += 10_000; soc += 0.3f } // 1.8 %/min
        assertEquals("map-15", fast.recommend().id) // ~4 min left: nothing fits, shortest
    }
}

class SubstitutionTest {
    @Test fun unsupportedInputsAreSwapped() {
        val supported = setOf(VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL, VehicleInput.WINDOW)
        val out = substitute(Episodes.letter30.puzzles, supported)
        assertTrue(out.all { it.spec.input in supported })
        assertEquals(Episodes.letter30.puzzles.map { it.story }, out.map { it.story })
        // seat, steering, wiper missing -> least used first, enum order on ties
        assertEquals(
            listOf(VehicleInput.WINDOW, VehicleInput.WINDOW, VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL, VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL),
            out.map { it.spec.input },
        )
    }

    @Test fun nothingSupportedKeepsOriginal() = assertEquals(Episodes.map15.puzzles, substitute(Episodes.map15.puzzles, emptySet()))

    @Test fun gameUsesSupportedSet() {
        val r = Rig(EscapeGame(supported = setOf(VehicleInput.READING_LIGHT)))
        r.toPuzzle("map-15")
        assertTrue(r.game.currentPuzzles().all { it.spec is PuzzleSpec.Morse })
        assertEquals(VehicleInput.READING_LIGHT, r.game.ui.value.requiredInput)
        r.play(r.game.demoSolution(0))
        assertEquals(Phase.SOLVED, r.phase)
    }
}

class ChargeClockTest {
    @Test fun needsEnoughSamples() {
        val c = ChargeClock()
        c.add(0, 40f); c.add(10_000, 40.2f)
        assertNull(c.etaMinutes(40.2f, 80, true))
        c.add(20_000, 40.4f)
        assertNull(c.etaMinutes(40.4f, 80, true)) // span under 90 s
    }

    @Test fun noisyIntegerStepsGiveSaneEta() {
        val c = ChargeClock()
        // 1 %/min, integer SoC with +-1 jitter, one sample every 5 s for 6 min
        var last = 0f
        for (i in 0..72) {
            val t = i * 5_000L
            val soc = (40 + t / 60_000.0).toInt() + listOf(0, 1, -1)[i % 3]
            last = soc.toFloat(); c.add(t, last)
        }
        val rate = c.ratePerMin()!!
        assertTrue("$rate", rate in 0.8..1.2)
        val eta = c.etaMinutes(46f, 80, true)!!
        assertTrue("$eta", eta in 28..43)
    }

    @Test fun stallAndNotChargingGiveNull() {
        val c = ChargeClock()
        for (i in 0..12) c.add(i * 60_000L, 50f + minOf(i, 3)) // rises then flat for 9 min
        assertNull(c.ratePerMin())
        assertNull(c.etaMinutes(53f, 80, false))
        assertEquals(0, c.etaMinutes(80f, 80, true))
    }

    @Test fun shortSamplesAreThinned() {
        val c = ChargeClock()
        for (i in 0..1_000) c.add(i * 100L, 40f + i * 0.001f) // 100 s of 100 ms ticks
        assertFalse(c.ratePerMin() == null)
    }
}
