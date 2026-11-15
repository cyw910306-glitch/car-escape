package com.carescape.game

import com.carescape.core.CarSeat
import com.carescape.core.CarWindow
import com.carescape.core.GameEvent
import com.carescape.core.MissionKind
import com.carescape.core.Phase
import com.carescape.core.PuzzleVisual
import com.carescape.core.SfxKind
import com.carescape.core.TurnSignal
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NewKindsTest {
    private val specs = listOf(
        PuzzleSpec.Memory(listOf("L", "R", "R")),
        PuzzleSpec.Riddle(PuzzleSpec.Wiper(3), "q", "1 더하기 2", 3),
        PuzzleSpec.Riddle(PuzzleSpec.Window(70), "q", "5 더하기 2", 7),
        PuzzleSpec.Chain(listOf(PuzzleSpec.SeatHeat(2), PuzzleSpec.Signals(listOf("L")), PuzzleSpec.Window(50))),
        PuzzleSpec.Chain(listOf(PuzzleSpec.Window(30), PuzzleSpec.Window(50, area = CarWindow.ROW_1_RIGHT), PuzzleSpec.Window(70, area = CarWindow.ROW_2_RIGHT))),
        PuzzleSpec.Belts(count = 2),
        PuzzleSpec.Belts(listOf(CarSeat.ROW_2_RIGHT)),
        PuzzleSpec.Belts(listOf(CarSeat.ROW_1_RIGHT, CarSeat.ROW_3_LEFT)),
        PuzzleSpec.Belts(listOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_2_CENTER, CarSeat.ROW_3_RIGHT), ordered = true),
        PuzzleSpec.Twin(),
        PuzzleSpec.Window(30, area = CarWindow.ROW_1_RIGHT),
    )

    @Test fun eachNewKindSolvesViaDemo() {
        // belts already on at baseline must still need a fresh click; demo handles it
        val start = VehicleState(socPercent = 40f, belts = CarSeat.entries.toSet())
        for (spec in specs) {
            val r = single(spec, start)
            r.play(spec.demo())
            assertEquals(spec.toString(), Phase.SOLVED, r.phase)
            assertEquals(spec.toString(), 0, r.sfx(SfxKind.WRONG))
            assertTrue(spec.prompt().isNotBlank() && spec.hints().size == 3 && "\u2014" !in spec.prompt() + spec.hints())
        }
    }

    @Test fun memoryHiddenAfterRevealAndRevealInputsIgnored() {
        val m = PuzzleSpec.Memory(listOf("L", "R", "L"))
        val r = single(m)
        val v = r.game.ui.value.visual as PuzzleVisual.Memory
        assertEquals(listOf("L", "R", "L"), v.target)
        assertTrue(v.revealLeftMs > 0)
        r.set { it.copy(turnSignal = TurnSignal.LEFT) } // during reveal: ignored
        r.set { it.copy(turnSignal = TurnSignal.OFF) }
        r.tick(m.revealMs)
        val hidden = r.game.ui.value.visual as PuzzleVisual.Memory
        assertEquals(listOf("?", "?", "?"), hidden.target)
        assertEquals(0L, hidden.revealLeftMs)
        assertEquals(emptyList<String>(), hidden.entered)
        r.set { it.copy(turnSignal = TurnSignal.RIGHT) } // wrong first symbol
        assertEquals(1, r.sfx(SfxKind.WRONG))
        r.set { it.copy(turnSignal = TurnSignal.LEFT) }
        assertEquals(listOf("L"), (r.game.ui.value.visual as PuzzleVisual.Memory).entered)
    }

    @Test fun wrongInputsGiveWrong() {
        val belt = single(PuzzleSpec.Belts(listOf(CarSeat.ROW_2_RIGHT)))
        belt.set { it.copy(belts = setOf(CarSeat.ROW_1_RIGHT)) }
        assertEquals(1, belt.sfx(SfxKind.WRONG))

        val relay = single(PuzzleSpec.Belts(listOf(CarSeat.ROW_1_RIGHT, CarSeat.ROW_2_LEFT), ordered = true))
        relay.set { it.copy(belts = setOf(CarSeat.ROW_2_LEFT)) } // 2nd row before 1st row
        assertEquals(1, relay.sfx(SfxKind.WRONG))
        relay.set { it.copy(belts = setOf(CarSeat.ROW_1_RIGHT)) }
        relay.set { it.copy(belts = setOf(CarSeat.ROW_1_RIGHT, CarSeat.ROW_2_LEFT)) }
        assertEquals(Phase.SOLVED, relay.phase)

        val chain = single(PuzzleSpec.Chain(listOf(PuzzleSpec.Signals(listOf("L")), PuzzleSpec.Signals(listOf("R")))))
        chain.set { it.copy(turnSignal = TurnSignal.RIGHT) }
        assertEquals(1, chain.sfx(SfxKind.WRONG))
        chain.set { it.copy(turnSignal = TurnSignal.LEFT) }
        assertEquals(PuzzleVisual.Chain(listOf("왼쪽 깜빡이", "오른쪽 깜빡이"), 1, PuzzleVisual.Sequence(listOf("R"), emptyList())), chain.game.ui.value.visual)

        val riddle = single(PuzzleSpec.Riddle(PuzzleSpec.Wiper(1), "q", "3 빼기 2", 1))
        repeat(2) { riddle.set(300) { it.copy(wiperOn = true) }; riddle.set(300) { it.copy(wiperOn = false) } }
        assertEquals(1, riddle.sfx(SfxKind.WRONG))
    }

    @Test fun riddleWindowOnlyAnswerCellAndFullRetryAfterWrong() {
        val rd = riddle(PuzzleSpec.Window(30), Random(1)) as PuzzleSpec.Riddle
        assertEquals("정답: 운전석 창문 10칸 중 3칸.", rd.hints()[2])
        val r = single(rd)
        r.set(3_000) { it.copy(windowDriverPercent = 38) } // shows 4칸
        assertEquals(Phase.PUZZLE, r.phase)
        r.set(2_200) { it.copy(windowDriverPercent = 31) }
        assertEquals(Phase.SOLVED, r.phase)

        val s = single(PuzzleSpec.Signals(listOf("L", "L", "R")))
        for (sig in listOf("L", "L", "L", "L", "L", "R")) { // L L L is wrong, then the whole code again
            s.set { it.copy(turnSignal = TurnSignal.OFF) }
            s.set { it.copy(turnSignal = if (sig == "L") TurnSignal.LEFT else TurnSignal.RIGHT) }
        }
        assertEquals(Phase.SOLVED, s.phase)
        assertEquals(1, s.sfx(SfxKind.WRONG))
    }

    @Test fun startOnTargetAsksForAWiggle() {
        val r = single(PuzzleSpec.Window(50), VehicleState(socPercent = 40f, windowDriverPercent = 50))
        assertEquals(EscapeGame.WIGGLE, r.game.ui.value.story)
        assertTrue(r.game.log.any { it is GameEvent.Speak && EscapeGame.WIGGLE in it.text })
        r.set { it.copy(windowDriverPercent = 0) }
        assertEquals("이야기", r.game.ui.value.story)
    }

    @Test fun wiperWearCapMovesOn() {
        val r = single(PuzzleSpec.Wiper(1))
        repeat(WIPE_CAP) { r.set(300) { it.copy(wiperOn = true) }; r.set(300) { it.copy(wiperOn = false) } }
        r.tick(2_500)
        assertEquals(Phase.SOLVED, r.phase)
        r.tick(EscapeGame.SOLVED_MS + 100)
        assertEquals(1, r.game.ui.value.puzzleIndex)
        r.game.skipPuzzle()
        assertEquals("힌트 0번, 건너뛰기 2번, 번개 0개", r.game.ui.value.summary)
        assertTrue(MissionKind.WIPER !in r.game.ui.value.solvedKinds)
    }

    /** 원래대로 출발: compared with the episode start (not with 0), reading light and wiper included, nothing moved back by the app. */
    @Test fun endingListsWhatDiffersFromTheStartUntilPutBack() {
        val start = VehicleState(socPercent = 40f, windowDriverPercent = 20, windows = mapOf(CarWindow.ROW_2_RIGHT to 0))
        val r = single(PuzzleSpec.Window(70), start)
        r.play(PuzzleSpec.Window(70).demo())
        r.set { it.copy(socPercent = 80f, seatHeatDriverLevel = 2, readingLightOn = true, windows = it.windows + (CarWindow.ROW_2_RIGHT to 40) + (CarWindow.ROW_1_RIGHT to 50)) }
        assertEquals(Phase.ENDING, r.phase)
        // ROW_1_RIGHT was unknown at the start: not compared
        assertEquals(listOf("운전석 창문", "2열 오른쪽 창문", "시트 열선", "독서등"), r.game.ui.value.restoreLeft)
        val said = r.game.log.filterIsInstance<GameEvent.Speak>().last().text
        assertTrue(said, said.endsWith("마지막 미션, 원래대로 출발! 시작 때와 달라진 곳을 돌려 놓아요. 운전석 창문, 2열 오른쪽 창문, 시트 열선, 독서등."))
        assertFalse(r.game.ui.value.endingText.contains("마지막 미션")) // the screen shows the live list instead
        r.set { it.copy(windowDriverPercent = 20, seatHeatDriverLevel = 0, readingLightOn = false) }
        assertEquals(listOf("2열 오른쪽 창문"), r.game.ui.value.restoreLeft)
        assertFalse(r.game.log.contains(GameEvent.Speak(EscapeGame.READY_TO_GO)))
        r.set { it.copy(windows = it.windows + (CarWindow.ROW_2_RIGHT to 0)) }
        r.tick(1_000)
        assertEquals(emptyList<String>(), r.game.ui.value.restoreLeft)
        assertEquals(1, r.game.log.count { it == GameEvent.Speak(EscapeGame.READY_TO_GO) })
    }

    @Test fun nothingChangedMeansReadyWithoutAnExtraLine() {
        val r = single(PuzzleSpec.Window(50))
        r.play(PuzzleSpec.Window(50).demo())
        r.set { it.copy(windowDriverPercent = 0, socPercent = 80f) }
        assertEquals(emptyList<String>(), r.game.ui.value.restoreLeft)
        r.tick(1_000)
        assertFalse(r.game.log.contains(GameEvent.Speak(EscapeGame.READY_TO_GO)))
    }

    @Test fun backgroundGapForfeitsLightning() {
        val ep = Episode("f", "고정", 15, "인트로", "끝", 0, listOf(Puzzle("a", "b", PuzzleSpec.Signals(listOf("L")), 0, timedSec = 45), p(PuzzleSpec.Wiper(9))), fixed = true)
        val r = Rig(EscapeGame(listOf(ep)))
        r.toPuzzle("f")
        assertEquals(45, r.game.ui.value.bonusSecTotal)
        r.now += 180_000 // app in the background for 3 minutes
        r.tick()
        assertNull(r.game.ui.value.bonusSecLeft)
        r.play(r.game.demoSolution(0))
        assertEquals(0, r.game.ui.value.lightning)
    }

    @Test fun twinNeedsBothWindowsMatchedAndOpen() {
        val r = single(PuzzleSpec.Twin())
        r.set(3_000) { it.copy(windowDriverPercent = 50, windows = mapOf(CarWindow.ROW_1_RIGHT to 80)) }
        assertEquals(Phase.PUZZLE, r.phase) // 30 apart
        r.set(3_000) { it.copy(windowDriverPercent = 95, windows = mapOf(CarWindow.ROW_1_RIGHT to 95)) }
        assertEquals(Phase.PUZZLE, r.phase) // matched but fully open: not allowed
        r.set(2_200) { it.copy(windowDriverPercent = 45, windows = mapOf(CarWindow.ROW_1_RIGHT to 52)) }
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun riddleAnswerEqualsSpecNumber() {
        val inners = listOf(PuzzleSpec.Wiper(1), PuzzleSpec.Wiper(3), PuzzleSpec.SeatHeat(2), PuzzleSpec.SteeringHeat(1), PuzzleSpec.Window(30), PuzzleSpec.Window(70))
        for (seed in 0..30) for (inner in inners) {
            val rd = riddle(inner, Random(seed)) as PuzzleSpec.Riddle
            assertEquals(inner.number(), rd.answer)
            val (a, op, b) = rd.math.split(" ")
            assertEquals(rd.math, rd.answer, if (op == "더하기") a.toInt() + b.toInt() else a.toInt() - b.toInt())
            assertTrue(rd.math, a.toInt() > 0 && b.toInt() > 0)
            assertTrue(rd.prompt().startsWith("답을 찾으면") && rd.question.endsWith("?"))
            assertTrue(rd.question, "개가 먹으면" !in rd.question)
            assertTrue(rd.hints()[1].endsWith("답은 ${rd.answer}!"))
        }
        assertNull(PuzzleSpec.Morse(".").number())
        assertEquals(PuzzleSpec.Morse("."), riddle(PuzzleSpec.Morse("."), Random(1)))
    }
}

class RandomizeTest {
    @Test fun sameSeedSamePuzzlesDifferentSeedsDiffer() {
        for (ep in Episodes.all) {
            assertEquals(roll(ep, Random(42)), roll(ep, Random(42)))
            val distinct = (1..20).map { roll(ep, Random(it)).map { p -> p.spec to p.story } }.toSet()
            assertTrue("${ep.id} ${distinct.size}", distinct.size >= 15)
            val r = roll(ep, Random(5))
            assertEquals(ep.puzzles.first().story, r.first().story)
            assertEquals(ep.puzzles.last().story, r.last().story)
            assertEquals(ep.puzzles.map { it.story }.toSet(), r.map { it.story }.toSet())
        }
    }

    @Test fun rerollStaysInSafeRanges() {
        for (seed in 0..200) for (p in roll(Episodes.letter30, Random(seed)) + roll(Episodes.map15, Random(seed))) {
            fun check(s: PuzzleSpec): Unit = when (s) {
                is PuzzleSpec.Window -> assertTrue(s.target in listOf(30, 50, 70))
                is PuzzleSpec.Wiper -> assertTrue(s.times in 1..3)
                is PuzzleSpec.Morse -> assertTrue(s.pattern.length in 2..4)
                is PuzzleSpec.Signals -> assertTrue(s.target.size in 1..5)
                is PuzzleSpec.Memory -> assertTrue(s.target.size in 3..5)
                is PuzzleSpec.SeatHeat -> assertTrue(s.level in 1..s.max)
                is PuzzleSpec.SteeringHeat -> assertTrue(s.level in 1..s.max)
                is PuzzleSpec.Chain -> { assertTrue(s.steps.none { it is PuzzleSpec.Wiper }); s.steps.forEach(::check) }
                is PuzzleSpec.Riddle -> check(s.inner)
                else -> {}
            }
            check(p.spec)
        }
    }

    @Test fun beltsFollowFrontOccupancy() {
        val occ = setOf(CarSeat.ROW_3_RIGHT, CarSeat.ROW_1_RIGHT, CarSeat.ROW_1_LEFT, CarSeat.ROW_2_CENTER)
        val relay = PuzzleSpec.Belts(ordered = true).reroll(Random(1), occ, 4)
        assertEquals(PuzzleSpec.Belts(listOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_1_RIGHT), ordered = true), relay)
        assertEquals("운전석, 조수석 순서로 한 명씩 벨트를 다시 찰칵 채워 보세요", relay.prompt())
        repeat(20) {
            // rear belts may hold a child seat: never a target
            val free = PuzzleSpec.Belts().reroll(Random(it), occ, 4) as PuzzleSpec.Belts
            assertTrue(free.seats.isNotEmpty() && free.seats.all { s -> s.name.startsWith("ROW_1") })
        }
        assertEquals(PuzzleSpec.Belts(count = 2), PuzzleSpec.Belts().reroll(Random(1), emptySet(), 5)) // unknown occupancy
        assertEquals(PuzzleSpec.Belts(count = 1), PuzzleSpec.Belts(ordered = true).reroll(Random(1), setOf(CarSeat.ROW_2_LEFT), 1))
        // count mode ignores rear clicks
        val r = single(PuzzleSpec.Belts(count = 1))
        r.set { it.copy(belts = setOf(CarSeat.ROW_2_LEFT)) }
        assertEquals(Phase.PUZZLE, r.phase)
        r.set { it.copy(belts = setOf(CarSeat.ROW_2_LEFT, CarSeat.ROW_1_RIGHT)) }
        assertEquals(Phase.SOLVED, r.phase)
    }

    @Test fun dailyRollIgnoresOccupancy() {
        for (day in 0L..100L) for (ep in Episodes.all) {
            val a = roll(ep, Random(day), emptySet(), 1)
            val b = roll(ep, Random(day), setOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_1_RIGHT, CarSeat.ROW_2_LEFT), 3)
            assertEquals(a.map { if (it.spec is PuzzleSpec.Belts) null else it }, b.map { if (it.spec is PuzzleSpec.Belts) null else it })
        }
    }

    @Test fun rearWindowAtMostHalfAndTimedFitsFinalKind() {
        fun all(s: PuzzleSpec): List<PuzzleSpec> = when (s) {
            is PuzzleSpec.Chain -> s.steps.flatMap(::all)
            is PuzzleSpec.Riddle -> listOf(s) + all(s.inner)
            else -> listOf(s)
        }
        for (seed in 0..300) for (ep in Episodes.all) for (p in roll(ep, Random(seed))) {
            all(p.spec).filterIsInstance<PuzzleSpec.Window>().filter { it.area == CarWindow.ROW_2_LEFT || it.area == CarWindow.ROW_2_RIGHT }
                .forEach { assertTrue("$it", it.target <= 50) }
            val want = when (p.spec) {
                is PuzzleSpec.Chain -> 90
                is PuzzleSpec.Riddle -> 75
                is PuzzleSpec.Memory, is PuzzleSpec.Belts, is PuzzleSpec.Twin -> 60
                else -> 45
            }
            p.timedSec?.let { assertEquals("${ep.id} $seed ${p.spec}", want, it) }
        }
    }

    @Test fun heatLevelsFitTheCar() {
        val oneStep = VehicleState(steeringHeatMax = 1, seatHeatMax = 2)
        assertEquals(PuzzleSpec.SteeringHeat(1, max = 1), PuzzleSpec.SteeringHeat(2).fit(oneStep))
        assertEquals(PuzzleSpec.SeatHeat(2, max = 2), PuzzleSpec.SeatHeat(3).fit(oneStep))
        val rd = PuzzleSpec.Riddle(PuzzleSpec.SeatHeat(3), "q", "1 더하기 2", 3).fit(oneStep) as PuzzleSpec.Riddle
        assertEquals(2, rd.answer)
        assertEquals(PuzzleSpec.SeatHeat(2, max = 2), rd.inner)
        val chain = PuzzleSpec.Chain(listOf(PuzzleSpec.SteeringHeat(2), PuzzleSpec.Window(50))).fit(oneStep) as PuzzleSpec.Chain
        assertEquals(PuzzleSpec.SteeringHeat(1, max = 1), chain.steps[0])
    }
}

class ScoreTest {
    @Test fun starsMath() {
        assertEquals(3, EscapeGame.stars(0, 0, 4))
        assertEquals(2, EscapeGame.stars(1, 0, 4))
        assertEquals(2, EscapeGame.stars(4, 0, 4))
        assertEquals(1, EscapeGame.stars(5, 0, 4))
        assertEquals(1, EscapeGame.stars(0, 1, 4))
    }

    private fun fixedEp(timed: Int?) = Episode("f", "고정", 15, "인트로", "끝", 0, listOf(
        Puzzle("a", "b", PuzzleSpec.Signals(listOf("L")), 0, timedSec = timed),
        Puzzle("c", "d", PuzzleSpec.Belts(count = 1), 0),
    ), fixed = true)

    @Test fun cleanRunGivesThreeStarsLightningAndKinds() {
        val r = Rig(EscapeGame(listOf(fixedEp(45))))
        r.toPuzzle("f")
        assertEquals(45, r.game.ui.value.bonusSecLeft)
        assertEquals(MissionKind.SIGNALS, r.game.ui.value.missionKind)
        r.play(r.game.demoSolution(0)); r.tick(EscapeGame.SOLVED_MS + 100)
        assertNull(r.game.ui.value.bonusSecLeft)
        assertEquals(0, r.game.ui.value.stars) // not at the ending yet
        r.play(r.game.demoSolution(1)); r.tick(EscapeGame.SOLVED_MS + 100)
        val ui = r.game.ui.value
        assertEquals(Phase.ENDING, ui.phase)
        assertEquals(3, ui.stars)
        assertEquals(1, ui.lightning)
        assertEquals(setOf(MissionKind.SIGNALS, MissionKind.SEATBELT, MissionKind.LIGHTNING), ui.solvedKinds)
        assertEquals("힌트 0번, 건너뛰기 0번, 번개 1개", ui.summary)
        assertEquals("f", ui.episodeId)
    }

    @Test fun slowRunMissesLightningAndHintsCostStars() {
        val r = Rig(EscapeGame(listOf(fixedEp(45))))
        r.toPuzzle("f")
        r.game.requestHint()
        r.tick(46_000)
        assertEquals(0, r.game.ui.value.bonusSecLeft) // timer ran out, puzzle still open
        r.play(r.game.demoSolution(0)); r.tick(EscapeGame.SOLVED_MS + 100)
        r.game.skipPuzzle()
        val ui = r.game.ui.value
        assertEquals(0, ui.lightning)
        assertEquals(1, ui.stars)
        assertEquals("힌트 1번, 건너뛰기 1번, 번개 0개", ui.summary)
    }

    @Test fun dailySeedGivesSamePuzzles() {
        fun daily(day: Long, otherRandom: Int) = EscapeGame(random = Random(otherRandom)).run {
            startDaily("letter-30", day); currentPuzzles() to ui.value.daily
        }
        assertEquals(daily(20_000, 1), daily(20_000, 2))
        assertTrue(daily(20_000, 1).second)
        assertNotEquals(daily(20_000, 1).first, daily(20_001, 1).first)
    }
}

class SubstituteV2Test {
    @Test fun familyWithoutBeltsOrExtraWindows() {
        val six = setOf(VehicleInput.WINDOW, VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL, VehicleInput.STEERING_HEAT, VehicleInput.SEAT_HEAT, VehicleInput.WIPER)
        for (seed in 0..20) {
            val out = substitute(roll(Episodes.family20, Random(seed)), six)
            assertTrue(out.all { p -> p.spec.inputs.all { it in six } })
            assertTrue(out.none { it.spec is PuzzleSpec.Belts || it.spec is PuzzleSpec.Twin })
            assertEquals(Episodes.family20.puzzles.map { it.story }.toSet(), out.map { it.story }.toSet())
        }
        // relay keeps the driver step only
        val relay = Episodes.family20.puzzles[3]
        assertEquals(PuzzleSpec.Window(50), substitute(listOf(relay), six).single().spec)
        // no rear windows (child lock): relay keeps driver + passenger
        val noRear = VehicleInput.entries.toSet() - VehicleInput.REAR_WINDOW
        assertEquals(2, (substitute(listOf(relay), noRear).single().spec as PuzzleSpec.Chain).steps.size)
    }

    @Test fun rearWindowIsNeverASwapPick() {
        val out = substitute(Episodes.letter30.puzzles, setOf(VehicleInput.REAR_WINDOW, VehicleInput.SEATBELT))
        assertTrue(out.all { it.spec is PuzzleSpec.Belts })
        assertEquals(PuzzleSpec.Belts(count = 1), out.first().spec)
    }
}
