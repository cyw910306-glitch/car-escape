package com.carescape.ui

import androidx.compose.runtime.Composable
import com.carescape.core.CarSeat
import com.carescape.core.CarWindow
import com.carescape.core.GameUiState
import com.carescape.core.MissionKind
import com.carescape.core.Phase
import com.carescape.core.PuzzleVisual
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScreensTest(private val screen: PleosScreen) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = PleosScreen.entries.toList()
    }

    @get:Rule val paparazzi = pleosPaparazzi(screen)

    private val base = GameUiState(
        episodeTitle = "멈춰 선 차의 비밀", puzzleIndex = 0, puzzleCount = 4, chapterLabel = "1 / 4",
        socPercent = 42f, targetPercent = 80, chargeEtaMinutes = 34, episodeMinutes = 30,
        story = "충전기가 꽂힌 순간, 계기판에 낯선 메시지가 떴습니다. 이 차에 잠긴 금고를 열어야 합니다.",
    )

    private fun puzzle(title: String, prompt: String, visual: PuzzleVisual, input: VehicleInput, hint: String? = null) =
        base.copy(phase = Phase.PUZZLE, puzzleTitle = title, prompt = prompt, visual = visual, requiredInput = input, hintText = hint, hintsUsed = if (hint != null) 1 else 0)

    private fun shot(name: String, ui: GameUiState, demoOpen: Boolean = false, blocked: Boolean = false, progress: Progress = Progress(), daily: Boolean = false) =
        paparazzi.snapshot(name = "${screen.name}_$name") { App(ui, demoOpen, blocked, progress, daily) }

    @Composable
    private fun App(ui: GameUiState, demoOpen: Boolean, blocked: Boolean, progress: Progress, daily: Boolean) = CarEscapeApp(
        ui, VehicleState(windowDriverPercent = 35, seatHeatDriverLevel = 2, belts = setOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_2_LEFT), windows = mapOf(CarWindow.ROW_1_RIGHT to 20)),
        GameActions(), DemoControls(initiallyOpen = demoOpen), showDemo = demoOpen, blocked = blocked, progress = progress, daily = daily,
    )

    private val saved = Progress(
        best = mapOf("map-15" to BestRecord(3, 412), "family-20" to BestRecord(2, 1015), "letter-30" to BestRecord(1, 1720)),
        badges = setOf(MissionKind.WINDOW, MissionKind.MEMORY, MissionKind.SEATBELT, MissionKind.LIGHTNING),
    )
    private val log = listOf(
        ChargeLog("10/18", 1, 3, 31, 80, 152), ChargeLog("10/21", 2, 2, 44, 80, 118), ChargeLog("10/25", 3, 3, 40, 80, null),
    )
    private val family = base.copy(episodeTitle = "아이오닉 9 우주선 출발 준비", episodeId = "family-20", puzzleCount = 5, chapterLabel = "2 / 5", puzzleIndex = 1)
    private fun v2(title: String, prompt: String, visual: PuzzleVisual, input: VehicleInput, bonus: Int? = null) =
        family.copy(phase = Phase.PUZZLE, puzzleTitle = title, prompt = prompt, visual = visual, requiredInput = input, bonusSecLeft = bonus, bonusSecTotal = bonus?.let { 60 })

    @Test fun title() = shot("title", base)
    @Test fun matching() = shot("matching", base.copy(phase = Phase.MATCHING))
    @Test fun intro() = shot("intro", base.copy(phase = Phase.INTRO))
    @Test fun dial() = shot("dial", puzzle("금고 다이얼", "운전석 창문을 내려 다이얼을 금색 구간에 맞추세요", PuzzleVisual.Dial(60, 35, 6), VehicleInput.WINDOW))
    @Test fun morse() = shot("morse", puzzle("깜빡이는 램프", "독서등을 짧게, 길게 눌러 신호를 보내세요", PuzzleVisual.Morse("-.-", "-."), VehicleInput.READING_LIGHT, "선은 1초 넘게 누르고 있으면 됩니다"))
    @Test fun sequence() = shot("sequence", puzzle("갈림길", "화살표 순서대로 깜빡이를 켜세요", PuzzleVisual.Sequence(listOf("L", "R", "R", "L", "R"), listOf("L", "R")), VehicleInput.TURN_SIGNAL))
    @Test fun level() = shot("level", puzzle("얼어붙은 자물쇠", "시트 열선을 목표 단계까지 올리세요", PuzzleVisual.Level(3, 2, 3), VehicleInput.SEAT_HEAT))
    @Test fun solved() = shot("solved", puzzle("금고 다이얼", "운전석 창문을 내려 다이얼을 금색 구간에 맞추세요", PuzzleVisual.Dial(60, 61, 6), VehicleInput.WINDOW).copy(phase = Phase.SOLVED))
    @Test fun ending() = shot("ending", base.copy(phase = Phase.ENDING, elapsedSec = 1874, hintsUsed = 2, endingText = "금고 안에는 가족 사진 한 장과 메모가 있었습니다. 충전이 끝났어요, 이제 출발할 시간입니다."))
    @Test fun endingWaiting() = shot("ending_waiting", base.copy(phase = Phase.ENDING, waitingForCharge = true, socPercent = 63f, chargeEtaMinutes = 12, endingText = "모든 퍼즐을 풀었어요! 충전이 끝나면 엔딩이 열려요."))
    @Test fun dialZero() = shot("dial_zero", puzzle("창문 금고", "운전석 창문을 절반쯤 내려 보세요", PuzzleVisual.Dial(50, 0, 6), VehicleInput.WINDOW))
    @Test fun paused() = shot("paused", puzzle("금고 다이얼", "x", PuzzleVisual.Dial(60, 35, 6), VehicleInput.WINDOW), blocked = true)
    @Test fun titleRecords() = shot("title_records", base, progress = saved)
    @Test fun matchingDaily() = shot("matching_daily", base.copy(phase = Phase.MATCHING), progress = saved, daily = true)
    @Test fun memoryShow() = shot("memory_show", v2("기억 금고", "깜빡이 순서를 잘 보고 외워요. 사라지면 똑같이 켜 보세요", PuzzleVisual.Memory(listOf("L", "R", "L", "R"), emptyList(), 2400), VehicleInput.TURN_SIGNAL))
    @Test fun memoryHidden() = shot("memory_hidden", v2("기억 금고", "깜빡이 순서를 잘 보고 외워요. 사라지면 똑같이 켜 보세요", PuzzleVisual.Memory(listOf("?", "?", "?", "?"), listOf("L", "R"), 0), VehicleInput.TURN_SIGNAL, bonus = 37))
    @Test fun riddle() = shot("riddle", v2("수수께끼 금고", "답을 찾으면 바람이 들어오는 운전석 문을 10칸 중 그 칸만큼 내려 보세요", PuzzleVisual.Riddle("사과 4개 중에 1개를 먹으면 몇 개가 남을까요?", 2, 10), VehicleInput.WINDOW))
    @Test fun chain() = shot("chain", v2("연쇄 금고", "차례대로 해 보세요. 첫째 시트 열선 2단, 둘째 깜빡이 4번, 셋째 운전석 창문 절반쯤", PuzzleVisual.Chain(listOf("시트 열선 2단", "깜빡이 4번", "운전석 창문 절반쯤"), 1, PuzzleVisual.Sequence(listOf("L", "R", "L", "R"), listOf("L"))), VehicleInput.TURN_SIGNAL, bonus = 0))
    @Test fun seats() = shot("seats", v2("찰칵 릴레이", "운전석, 조수석 순서로 한 명씩 벨트를 다시 찰칵 채워 보세요",
        PuzzleVisual.Seats(listOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_1_RIGHT), 2, listOf(CarSeat.ROW_1_LEFT), setOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_2_LEFT), true), VehicleInput.SEATBELT, bonus = 52))
    @Test fun twin() = shot("twin", v2("쌍둥이 창문", "운전석과 조수석 창문을 똑같은 높이로 내려 보세요", PuzzleVisual.Twin(35, 20, 10, 20, 80), VehicleInput.PASSENGER_WINDOW))
    @Test fun endingStars() = shot("ending_stars", family.copy(phase = Phase.ENDING, elapsedSec = 1015, hintsUsed = 1, stars = 2, summary = "힌트 1번, 건너뛰기 0번, 번개 1개",
        // longest real ending ("charge stopped" + range + teaser) with the 원래대로 출발 list still open
        endingText = "충전이 멈췄어요. 지금 배터리 63%. 이제 312km 갈 수 있어요. 퍼즐 5개 중 5개 완료! 출발 준비 완료! 온 가족이 한 팀이었어요. 다음 충전 때, 별빛 우체부가 찾아와요.",
        restoreLeft = listOf("운전석 창문", "2열 오른쪽 창문", "시트 열선", "독서등"), episodeNumber = 2),
        progress = saved.copy(newBest = true, newBadges = setOf(MissionKind.TWIN_WINDOW, MissionKind.LIGHTNING), log = log))
    @Test fun endingReadyToGo() = shot("ending_ready", family.copy(phase = Phase.ENDING, elapsedSec = 1015, stars = 3, summary = "힌트 0번, 건너뛰기 0번, 번개 0개",
        endingText = "80% 충전 완료! 이제 312km 갈 수 있어요. 출발 준비 완료! 온 가족이 한 팀이었어요. 엔딩 크레딧과 함께 출발하세요. 다음 충전 때, 별빛 우체부가 찾아와요.",
        restoreLeft = emptyList(), episodeNumber = 2), progress = saved.copy(log = log))
    @Test fun titleReadyCard() = shot("title_ready", base.copy(readyEpisode = 4), progress = saved.copy(log = log))
    @Test fun titleLog() = shot("title_log", base, progress = saved.copy(log = log))
    @Test fun introRecap() = shot("intro_recap", base.copy(phase = Phase.INTRO, episodeTitle = "별빛 우체부와 달님의 답장", episodeNumber = 4, puzzleCount = 5,
        recap = "지난 충전 때 편지 여섯 장이 모두 주인을 찾았어요.", story = "지난번 편지를 받은 달님이 답장을 보냈대요. 별빛 우체부와 함께 답장 다섯 장을 모아 볼까요?"))
    @Test fun doorOpen() = shot("door_open", base.copy(phase = Phase.PAUSED_DOOR))
    @Test fun doorAsk() = shot("door_ask", base.copy(phase = Phase.PAUSED_DOOR, doorAsk = true))
    @Test fun demoBelts() = shot("demo_belts", v2("찰칵 금고", "조수석 자리만 벨트를 풀었다가 다시 찰칵 채워 보세요",
        PuzzleVisual.Seats(listOf(CarSeat.ROW_1_RIGHT), 1, emptyList(), setOf(CarSeat.ROW_1_LEFT), false), VehicleInput.SEATBELT), demoOpen = true)
    @Test fun demo() = shot("demo", puzzle("금고 다이얼", "운전석 창문을 내려 다이얼을 금색 구간에 맞추세요", PuzzleVisual.Dial(60, 35, 6), VehicleInput.WINDOW), demoOpen = true)
}
