package com.carescape.game

import com.carescape.core.CarWindow
import com.carescape.core.GameEvent
import com.carescape.core.Gear
import com.carescape.core.ParkingAnchor
import com.carescape.core.Phase
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v3: port card, serial episodes, door pause, range ending, child lock swap, season 1, care wording. */
class V3Test {
    private fun said(r: Rig) = r.game.log.filterIsInstance<GameEvent.Speak>().map { it.text }

    @Test fun portOpeningOnTitleOffersTheNextEpisodeOnce() {
        val r = Rig()
        r.set { it.copy(chargePortOpen = true) } // already open when the app came up: no card
        assertEquals(0, r.game.ui.value.readyEpisode)
        r.set { it.copy(chargePortOpen = false) }
        r.set { it.copy(chargePortOpen = null) } // unknown in between changes nothing
        r.set { it.copy(chargePortOpen = true) }
        assertEquals(1, r.game.ui.value.readyEpisode)
        r.game.dismissReady()
        assertEquals(0, r.game.ui.value.readyEpisode)
        r.tick()
        assertEquals(0, r.game.ui.value.readyEpisode)

        r.game.nextEpisodeIndex = 2
        r.set { it.copy(chargePortOpen = false) }
        r.set { it.copy(chargePortOpen = true) }
        assertEquals(3, r.game.ui.value.readyEpisode)
        r.game.startNext()
        assertEquals(Phase.INTRO, r.phase)
        assertEquals("letter-30", r.game.ui.value.episodeId)
        assertEquals(3, r.game.ui.value.episodeNumber)
    }

    @Test fun portOutsideTitleShowsNoCard() {
        val r = Rig()
        r.set { it.copy(chargePortOpen = false) }
        r.game.startMatching()
        r.set { it.copy(chargePortOpen = true) }
        r.game.backToTitle()
        r.tick()
        assertEquals(0, r.game.ui.value.readyEpisode)
    }

    @Test fun finishingAnEpisodeOpensTheNextWithRecapAndTeaser() {
        val r = Rig()
        r.toPuzzle("map-15")
        assertEquals("", r.game.ui.value.recap)
        assertTrue(said(r).any { it.startsWith("오늘은 1화예요. ") })
        Episodes.map15.puzzles.indices.forEach { r.play(r.game.demoSolution(it)); r.tick(EscapeGame.SOLVED_MS + 100) }
        r.set { it.copy(socPercent = 80f) }
        assertTrue(r.game.ui.value.endingText, r.game.ui.value.endingText.endsWith("다음 충전 때, 우리 차는 우주선이 돼요."))
        assertEquals(1, r.game.nextEpisodeIndex)

        r.game.backToTitle()
        r.set { it.copy(socPercent = 40f) }
        r.game.startEpisode("family-20")
        assertEquals("지난 충전 때 우리는 충전소 지도를 완성했어요.", r.game.ui.value.recap)
        assertEquals(2, r.game.ui.value.episodeNumber)
        assertTrue(said(r).last(), said(r).last().startsWith("지난 충전 때 우리는 충전소 지도를 완성했어요. 오늘은 2화예요."))
        r.tick(EscapeGame.INTRO_MS + 100)
        assertEquals(Phase.INTRO, r.phase) // the recap gets its extra seconds
        r.tick(EscapeGame.RECAP_MS)
        assertEquals(Phase.PUZZLE, r.phase)

        // Jumping ahead to 3화 without finishing 2화: no "last time" line, that would claim something untrue.
        r.game.backToTitle()
        r.game.startEpisode("letter-30")
        assertEquals("", r.game.ui.value.recap)
    }

    @Test fun bonusEndingKeepsTheSameEpisodeNext() {
        val r = Rig()
        r.toPuzzle("map-15")
        r.set { it.copy(socPercent = 80f) }
        assertTrue(r.game.ui.value.endingText.startsWith("보너스 엔딩!"))
        assertFalse(r.game.ui.value.endingText.contains(Episodes.map15.teaser))
        assertEquals(0, r.game.nextEpisodeIndex)
    }

    @Test fun seasonOneWrapsAfterTheFourthEpisode() {
        val g = EscapeGame()
        g.nextEpisodeIndex = 4
        assertEquals(0, g.nextEpisodeIndex)
        g.nextEpisodeIndex = -1
        assertEquals(3, g.nextEpisodeIndex)
    }

    @Test fun doorPausesVoiceAndTimersAndAsksOnceWhenClosed() {
        val r = Rig()
        r.toPuzzle("map-15")
        val idx = r.game.ui.value.puzzleIndex
        val t0 = r.game.ui.value.elapsedSec
        r.set { it.copy(doorOpen = true) }
        assertEquals(Phase.PAUSED_DOOR, r.phase)
        assertFalse(r.game.ui.value.doorAsk)
        assertTrue(r.game.log.contains(GameEvent.Silence))
        r.set(60_000) { it.copy(windowDriverPercent = 50) } // inputs while out do not count, the clock does not run
        assertEquals(t0, r.game.ui.value.elapsedSec)
        r.game.resumeAfterDoor() // doors still open: ignored
        assertEquals(Phase.PAUSED_DOOR, r.phase)
        r.set { it.copy(doorOpen = false) }
        assertTrue(r.game.ui.value.doorAsk)
        r.tick(5_000)
        assertEquals(1, said(r).count { it == EscapeGame.RESUME_ASK })
        assertEquals(Phase.PAUSED_DOOR, r.phase) // waits for the tap
        r.game.resumeAfterDoor()
        assertEquals(Phase.PUZZLE, r.phase)
        assertEquals(idx, r.game.ui.value.puzzleIndex)
        r.tick(3_000)
        assertEquals(Phase.PUZZLE, r.phase) // the window left at 50 while out is the new baseline, not an answer
    }

    @Test fun drivingWinsOverDoorPauseAndBothResume() {
        val r = Rig()
        r.toPuzzle("map-15")
        r.set { it.copy(doorOpen = true) }
        r.set { it.copy(doorOpen = false, gear = Gear.DRIVE) }
        assertEquals(Phase.PAUSED_DRIVING, r.phase)
        r.set { it.copy(gear = Gear.PARK) }
        r.tick()
        assertEquals(Phase.PAUSED_DOOR, r.phase)
        assertTrue(r.game.ui.value.doorAsk)
        r.game.resumeAfterDoor()
        assertEquals(Phase.PUZZLE, r.phase)
    }

    @Test fun doorOnTitleOrEndingDoesNothing() {
        val r = Rig()
        r.set { it.copy(doorOpen = true) }
        assertEquals(Phase.TITLE, r.phase)
    }

    @Test fun endingSaysRangeOnlyWhenKnown() {
        val r = Rig(s = VehicleState(socPercent = 40f, rangeMeters = 312_400f))
        r.toPuzzle("map-15")
        r.set { it.copy(socPercent = 80f) }
        assertTrue(r.game.ui.value.endingText, r.game.ui.value.endingText.startsWith("보너스 엔딩! 충전이 먼저 80%에 도착했어요. 이제 312km 갈 수 있어요. 남은"))
        assertTrue(said(r).last().contains("이제 312킬로미터 갈 수 있어요."))

        val none = Rig()
        none.toPuzzle("map-15")
        none.set { it.copy(socPercent = 80f) }
        assertFalse(none.game.ui.value.endingText.contains("갈 수 있어요"))
    }

    @Test fun childLockMovesSecondRowWindowsToTheFront() {
        for (seed in 0..30) {
            val g = EscapeGame(random = Random(seed))
            g.onVehicle(VehicleState(rearWindowLocked = true), 0)
            g.startEpisode("moon-25")
            assertTrue("$seed " + g.currentPuzzles().map { it.spec }, g.currentPuzzles().none { VehicleInput.REAR_WINDOW in it.spec.inputs })
            g.startEpisode("family-20")
            assertTrue(g.currentPuzzles().none { VehicleInput.REAR_WINDOW in it.spec.inputs })
        }
        val unlocked = EscapeGame(random = Random(1))
        unlocked.startEpisode("moon-25")
        assertTrue(unlocked.currentPuzzles().map { it.spec }.toString(), unlocked.currentPuzzles().any { VehicleInput.REAR_WINDOW in it.spec.inputs })
    }

    @Test fun lockedRearWindowGoesToThePassengerWindowFirst() {
        val all = VehicleInput.entries.toSet() - VehicleInput.REAR_WINDOW
        val out = substitute(listOf(p(PuzzleSpec.Window(40, area = CarWindow.ROW_2_RIGHT))), all)
        assertEquals(PuzzleSpec.Window(40, area = CarWindow.ROW_1_RIGHT), out.single().spec)
    }

    @Test fun lockedRearWindowWithoutPassengerWindowFallsBackToDriver() {
        val out = substitute(listOf(p(PuzzleSpec.Window(40, area = CarWindow.ROW_2_RIGHT))), setOf(VehicleInput.WINDOW, VehicleInput.WIPER))
        assertEquals(PuzzleSpec.Window(40), out.single().spec)
    }

    @Test fun firstEpisodeNeverUsesSignalsOrWipers() {
        for (seed in 0..200) {
            val g = EscapeGame(random = Random(seed))
            g.startEpisode("map-15")
            val inputs = g.currentPuzzles().flatMap { it.spec.inputs }
            assertFalse(inputs.toString(), VehicleInput.TURN_SIGNAL in inputs || VehicleInput.WIPER in inputs)
        }
        // a car without seat heat must not get a blinker or wiper puzzle as the swap either
        val g = EscapeGame(supported = setOf(VehicleInput.WINDOW, VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL, VehicleInput.WIPER), random = Random(3))
        g.startEpisode("map-15")
        assertTrue(g.currentPuzzles().flatMap { it.spec.inputs }.all { it == VehicleInput.WINDOW || it == VehicleInput.READING_LIGHT })
    }

    @Test fun seasonOneHasFourEpisodesInOrder() {
        assertEquals(listOf("map-15", "family-20", "letter-30", "moon-25"), Episodes.all.map { it.id })
        assertEquals("", Episodes.all.first().recap)
        Episodes.all.drop(1).forEach { assertTrue(it.id, it.recap.startsWith("지난 충전 때")) }
        Episodes.all.forEach { assertTrue(it.id, it.teaser.isNotBlank()) }
        assertEquals("시즌 2에서 또 만나요.", Episodes.all.last().teaser)
    }

    /**
     * B5 care wording (검수 점검표 3 and 5, plus 4): no line pretends to know how the family feels, no line sounds like a safety
     * feature, no line asks to lean out. Covers every episode string and every prompt, hint and title over many rolls.
     */
    @Test fun noForbiddenPhrases() {
        val texts = mutableListOf<String>()
        for (ep in Episodes.all) {
            texts += listOf(ep.title, ep.intro, ep.outro, ep.recap, ep.teaser)
            ep.puzzles.forEach { texts += listOf(it.story, it.reward) }
            for (seed in 0..60) roll(ep, Random(seed)).forEach { p -> texts += p.title; texts += p.prompt; texts += p.hints }
        }
        texts += listOf(EscapeGame.WIGGLE, EscapeGame.RESUME_ASK, EscapeGame.READY_TO_GO)
        // and everything the game master says in a whole play, door pause and ending included
        val r = Rig(s = VehicleState(socPercent = 40f, rangeMeters = 300_000f))
        r.toPuzzle("map-15")
        r.set { it.copy(doorOpen = true) }; r.set { it.copy(doorOpen = false) }; r.game.resumeAfterDoor()
        r.game.requestHint(); r.game.requestHint(); r.game.requestHint()
        r.set { it.copy(seatHeatDriverLevel = 2, socPercent = 80f) }
        texts += said(r)
        texts += r.game.ui.value.endingText
        // 출발 금고 scripts: fork, save, return, reveal and its away line (B11)
        val p = Rig(s = VehicleState(socPercent = 42f, chargePortOpen = false, doorLocked = false, rangeMeters = 300_000f))
        p.tick(); p.game.onParkedAfterDrive()
        listOf(ParkingAnchor(-4, "C", "12"), ParkingAnchor(0, "", ""), ParkingAnchor(-1, "H", "7")).forEach { a ->
            p.game.saveAnchor(a)
            p.set { it.copy(doorLocked = true) }
            p.set { it.copy(socPercent = it.socPercent + 20f, rangeMeters = 400_000f) }
            p.set { it.copy(doorLocked = false, doorOpen = true) }; p.set { it.copy(doorOpen = false) }
            p.game.revealReturn()
            texts += p.game.ui.value.awayLine
            texts += p.game.ui.value.parkAnchor
            p.game.closeReturn(); p.game.onDriveAway(); p.set { it.copy(socPercent = 42f) }
        }
        texts += said(p)
        assertEquals("every 암호 was saved, asked and revealed", 3, said(p).count { it.startsWith("정답은 ") })
        texts += EscapeGame.RETURN_ASK

        for (t in texts) {
            FORBIDDEN.forEach { bad -> assertFalse("\"$bad\" in: $t", t.contains(bad)) }
            assertFalse("em dash in: $t", t.contains('\u2014'))
        }
    }

    companion object {
        /** 검수 점검표 B1 3 (state guessing), 4 (risky moves), 5 (safety feature claims). Add new phrasing here when a pack adds one. */
        val FORBIDDEN = listOf(
            "지루", "심심", "피곤", "졸리", "졸려", "배고", "배가 고", "무섭", "무서", "힘들", "짜증", "답답", "지쳤", "울지", "슬프",
            "기분이", "기다리기 힘", "많이 기다렸", "오래 기다렸",
            "아이 잔류", "잔류 감지", "사고 예방", "안전 보장", "안전을 지켜", "자동 복원", "자동으로 되돌", "자동으로 닫",
            "손을 내밀", "몸을 내밀", "고개를 내밀", "밖으로 손", "창밖으로", "운전 중", "달리는 동안",
            // 출발 금고: no child alone or in front, no walking orders, no safety verdicts, no promise to find the car
            "먼저 가", "혼자 가", "찾아 봐", "찾아봐", "따라가세요", "지금 건너", "안전합니다", "차를 찾아", "찾아 줄게", "정확한 위치",
        )
    }
}
