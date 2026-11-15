package com.carescape.game

import com.carescape.core.CarWindow
import com.carescape.core.VehicleInput
import com.carescape.game.PuzzleSpec.Belts
import com.carescape.game.PuzzleSpec.Chain
import com.carescape.game.PuzzleSpec.Memory
import com.carescape.game.PuzzleSpec.Morse
import com.carescape.game.PuzzleSpec.SeatHeat
import com.carescape.game.PuzzleSpec.Signals
import com.carescape.game.PuzzleSpec.SteeringHeat
import com.carescape.game.PuzzleSpec.Twin
import com.carescape.game.PuzzleSpec.Wiper
import com.carescape.game.PuzzleSpec.Window

object Episodes {
    /**
     * 1화. Turn signals and wipers left out on purpose: at a charger the blinkers confuse the queue and a wiper
     * on dry glass wears the blade. Both come back in later episodes (2화 3화 4화).
     */
    val map15 = Episode(
        id = "map-15",
        title = "꼬마 탐정과 충전소 지도",
        minutes = 15,
        intro = "충전소에 보물 지도가 숨어 있대요. 우리 차가 단서를 쥐고 있어요.",
        outro = "보물 지도를 모두 모았어요.",
        color = 0x3D7BFF,
        puzzles = listOf(
            Puzzle("지도 첫 조각이 창문 금고에 잠겨 있어요.", "찰칵! 지도 첫 조각을 찾았어요.", Window(50), 0x3D7BFF),
            Puzzle("지도 한 조각이 핸들 안에서 꽁꽁 얼었어요.", "핸들이 따뜻해지자 지도 조각이 녹아 나왔어요.", SteeringHeat(1), 0xFF5C8A),
            Puzzle("작은 등대가 불빛 신호를 기다려요.", "등대가 깜빡! 또 한 조각이 나왔어요.", Morse(".-"), 0x9B6BFF),
            Puzzle("마지막 조각은 따뜻한 자리를 좋아해요.", "지도가 완성됐어요! 보물은 바로 우리 가족이에요.", SeatHeat(2), 0xFF7A45),
        ),
        twists = 1,
        teaser = "다음 충전 때, 우리 차는 우주선이 돼요.",
        avoid = setOf(VehicleInput.TURN_SIGNAL, VehicleInput.WIPER),
    )

    /** IONIQ 9 sized family co-op: front-row belts (rear belts may hold child seats), two-window and relay windows. ~20 min (10 to 80 fast charge). */
    val family20 = Episode(
        id = "family-20",
        title = "아이오닉 9 우주선 출발 준비",
        minutes = 20,
        intro = "우리 아이오닉 9가 오늘은 우주선이에요! 충전하는 동안 온 가족이 힘을 모아 출발 준비를 해요.",
        outro = "출발 준비 완료! 온 가족이 한 팀이었어요.",
        color = 0x2B59C3,
        puzzles = listOf(
            Puzzle("첫 점검은 좌석이에요. 우주선 벨트가 느슨해졌대요.", "찰칵찰칵! 좌석 점검 끝이에요.", Belts(), 0x3D7BFF),
            Puzzle("양쪽 날개 창문이 같은 높이여야 균형이 맞아요.", "날개가 반듯해졌어요!", Twin(), 0x2EC4B6),
            Puzzle("관제탑이 깜빡이 신호를 잠깐 보여 주고 숨겨요.", "관제탑이 좋대요! 신호를 기억했어요.", Memory(listOf("L", "R", "L")), 0xFFB020),
            Puzzle("앞자리부터 뒷자리까지 창문으로 별가루를 받아요.", "별가루가 가득 모였어요!", Chain(listOf(Window(50), Window(50, area = CarWindow.ROW_1_RIGHT), Window(50, area = CarWindow.ROW_2_RIGHT))), 0x9B6BFF),
            Puzzle("마지막 점검! 앞자리 벨트를 찰칵 소리로 확인해요.", "모두 준비됐어요! 카운트다운 시작!", Belts(ordered = true), 0xFF7A45),
        ),
        twists = 2,
        recap = "지난 충전 때 우리는 충전소 지도를 완성했어요.",
        teaser = "다음 충전 때, 별빛 우체부가 찾아와요.",
    )

    val letter30 = Episode(
        id = "letter-30",
        title = "별빛 우체부의 잃어버린 편지",
        minutes = 30,
        intro = "별빛 우체부가 편지 여섯 장을 차 안에 떨어뜨렸어요. 함께 찾아 줄래요?",
        outro = "편지 여섯 장이 모두 주인을 찾았어요.",
        color = 0x5B5BD6,
        puzzles = listOf(
            Puzzle("첫 편지는 차가운 의자 밑에서 떨고 있어요.", "따뜻해지자 편지가 나왔어요!", SeatHeat(2), 0xFF7A45),
            Puzzle("편지 한 장이 창문 틈에 끼어 있어요.", "창문 틈에서 편지를 꺼냈어요.", Window(30), 0x3D7BFF),
            Puzzle("우체부의 등불이 암호를 깜빡여요.", "암호가 풀렸어요! 편지를 한 장 더 찾았어요.", Morse("-.-"), 0x9B6BFF),
            Puzzle("편지에 오른쪽, 왼쪽 길이 그려져 있어요.", "길을 따라가니 편지가 또 있어요.", Signals(listOf("R", "R", "L")), 0xFFB020),
            Puzzle("편지 한 장이 핸들 품에서 잠들었어요.", "핸들이 따뜻해지자 편지가 깨어났어요.", SteeringHeat(1), 0xFF5C8A),
            Puzzle("마지막 편지는 앞유리 너머로 날아갔어요.", "와이퍼가 편지를 쓸어 왔어요!", Wiper(3), 0x2EC4B6),
        ),
        twists = 3,
        recap = "지난 충전 때 우리 차는 우주선이 되어 출발 준비를 마쳤어요.",
        teaser = "다음 충전 때, 4화가 열려요.",
    )

    /** 4화, season 1 finale. Continues 3화 (the letters reached the moon). 2nd row window stays at half or below (reroll). */
    val moon25 = Episode(
        id = "moon-25",
        title = "별빛 우체부와 달님의 답장",
        minutes = 25,
        intro = "지난번 편지를 받은 달님이 답장을 보냈대요. 별빛 우체부와 함께 답장 다섯 장을 모아 볼까요?",
        outro = "달님의 답장 다섯 장을 모두 모았어요. 시즌 1 이야기는 여기까지예요.",
        color = 0x3A4FA8,
        puzzles = listOf(
            Puzzle("첫 답장은 핸들 품에서 쿨쿨 자고 있어요.", "핸들이 따뜻해지자 첫 답장이 기지개를 켰어요.", SteeringHeat(1), 0xFF5C8A),
            Puzzle("달님이 2열 창문에 답장 신호를 비춰요.", "2열 창문이 신호를 받았어요! 답장이 한 장 더 모였어요.", Window(50, area = CarWindow.ROW_2_RIGHT), 0x3D7BFF),
            Puzzle("우체부의 등불이 달님의 암호를 깜빡여요.", "암호가 풀렸어요! 답장이 또 한 장 나왔어요.", Morse("-.."), 0x9B6BFF),
            Puzzle("답장 봉투에 왼쪽, 오른쪽 별길이 그려져 있어요.", "별길 끝에서 답장을 찾았어요.", Signals(listOf("L", "R", "R")), 0xFFB020),
            Puzzle("마지막 답장은 포근한 의자 위에 내려앉았어요.", "답장 다섯 장이 모두 모였어요! 달님이 방긋 웃어요.", SeatHeat(1), 0xFF7A45),
        ),
        twists = 2,
        recap = "지난 충전 때 편지 여섯 장이 모두 주인을 찾았어요.",
        teaser = "시즌 2에서 또 만나요.",
    )

    /** Season 1 in order: index + 1 = N화. */
    val all = listOf(map15, family20, letter30, moon25)
}
