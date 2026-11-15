package com.carescape.game

import com.carescape.core.CarSeat
import com.carescape.core.CarWindow
import com.carescape.core.MissionKind
import com.carescape.core.PuzzleVisual
import com.carescape.core.TurnSignal
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import kotlin.random.Random

/** What the car must do. Numbers are calibration knobs: tune per car after emulator checks. */
sealed interface PuzzleSpec {
    /** Main input (UI icon, substitution balance). */
    val input: VehicleInput
    /** Every input the puzzle reads. All must be supported or the puzzle gets swapped. */
    val inputs: Set<VehicleInput> get() = setOf(input)

    /** area null = driver window. Targets stay well short of fully open. */
    data class Window(val target: Int, val tolerance: Int = 10, val holdMs: Long = 2_000, val area: CarWindow? = null) : PuzzleSpec {
        override val input get() = windowInput(area)
    }
    /** "." short (< 0.6 s on), "-" long. */
    data class Morse(val pattern: String, val longMs: Long = 600, val gapResetMs: Long = 2_500) : PuzzleSpec {
        override val input get() = VehicleInput.READING_LIGHT
    }
    /** "L" / "R" in order. */
    data class Signals(val target: List<String>) : PuzzleSpec {
        override val input get() = VehicleInput.TURN_SIGNAL
    }
    data class SteeringHeat(val level: Int, val max: Int = 2, val holdMs: Long = 1_500) : PuzzleSpec {
        override val input get() = VehicleInput.STEERING_HEAT
    }
    data class SeatHeat(val level: Int, val max: Int = 3, val holdMs: Long = 1_500) : PuzzleSpec {
        override val input get() = VehicleInput.SEAT_HEAT
    }
    /** Count of on then off cycles, judged after doneMs of quiet. Max 3 (dry glass). */
    data class Wiper(val times: Int, val doneMs: Long = 2_000) : PuzzleSpec {
        override val input get() = VehicleInput.WIPER
    }
    /**
     * 기억 금고: signals shown for revealMs, then hidden; inputs during the reveal do not count.
     * The first 6 s cover the spoken instructions, so the hiding never starts mid-sentence.
     */
    data class Memory(val target: List<String>, val revealMs: Long = 8_000L + 700L * target.size) : PuzzleSpec {
        override val input get() = VehicleInput.TURN_SIGNAL
    }
    /** 수수께끼 금고: [inner] with its number hidden behind a riddle. answer == inner.number(). */
    data class Riddle(val inner: PuzzleSpec, val question: String, val math: String, val answer: Int) : PuzzleSpec {
        override val input get() = inner.input
        override val inputs get() = inner.inputs
    }
    /** 연쇄 금고: steps in a fixed order. Each step starts from the state the previous one left. */
    data class Chain(val steps: List<PuzzleSpec>) : PuzzleSpec {
        override val input get() = steps.first().input
        override val inputs get() = steps.flatMap { it.inputs }.toSet()
    }
    /**
     * 찰칵: belts go unbuckled -> buckled. seats empty = any [count] different front seats.
     * ordered = exactly that order (relay), a wrong seat restarts.
     */
    data class Belts(val seats: List<CarSeat> = emptyList(), val count: Int = 1, val ordered: Boolean = false) : PuzzleSpec {
        val need get() = if (seats.isEmpty()) count else seats.size
        override val input get() = VehicleInput.SEATBELT
    }
    /** 쌍둥이 창문: driver and [area] window within tolerance of each other, both inside min..max, held. */
    data class Twin(val area: CarWindow = CarWindow.ROW_1_RIGHT, val tolerance: Int = 10, val min: Int = 20, val max: Int = 80, val holdMs: Long = 2_000) : PuzzleSpec {
        override val input get() = windowInput(area)
        override val inputs get() = setOf(VehicleInput.WINDOW, input)
    }
}

private fun windowInput(area: CarWindow?) = when (area) {
    null -> VehicleInput.WINDOW
    CarWindow.ROW_1_RIGHT -> VehicleInput.PASSENGER_WINDOW
    else -> VehicleInput.REAR_WINDOW
}

/** timedSec != null: TIMED twist (번개 금고), solve within it for a lightning badge. Never fails the puzzle. */
data class Puzzle(val story: String, val reward: String, val spec: PuzzleSpec, val color: Int, val timedSec: Int? = null) {
    val title get() = spec.title()
    val prompt get() = spec.prompt()
    val hints get() = spec.hints()
}

/**
 * twists = how many twists each play adds. fixed = never randomized (tests).
 * recap = "지난 화 한 줄" said before the intro when the family finished the episode before (empty = first episode).
 * teaser = "다음 화 예고" added to the ending when this episode is finished.
 * avoid = inputs this episode never uses, not even through twists or substitution (1화: no turn signals or dry wipers at the charger).
 */
data class Episode(
    val id: String,
    val title: String,
    val minutes: Int,
    val intro: String,
    val outro: String,
    val color: Int,
    val puzzles: List<Puzzle>,
    val twists: Int = 0,
    val fixed: Boolean = false,
    val recap: String = "",
    val teaser: String = "",
    val avoid: Set<VehicleInput> = emptySet(),
)

/** One demo step: apply [change], then let [waitMs] pass while ticking the game. */
data class DemoStep(val waitMs: Long, val change: (VehicleState) -> VehicleState)

private fun sym(s: String) = if (s == "L") "왼쪽" else "오른쪽"
private fun syms(t: List<String>) = t.joinToString(", ") { sym(it) }
private fun morseWords(p: String) = p.map { if (it == '.') "짧게" else "길게" }.joinToString(", ")
private fun windowWords(t: Int) = when {
    t <= 35 -> "3분의 1쯤"
    t <= 60 -> "절반쯤"
    else -> "3분의 2쯤"
}
private fun windowWho(a: CarWindow?) = when (a) {
    null -> "운전석"
    CarWindow.ROW_1_RIGHT -> "조수석"
    CarWindow.ROW_2_LEFT -> "2열 왼쪽"
    CarWindow.ROW_2_RIGHT -> "2열 오른쪽"
}
fun seatName(s: CarSeat) = when (s) {
    CarSeat.ROW_1_LEFT -> "운전석"
    CarSeat.ROW_1_RIGHT -> "조수석"
    CarSeat.ROW_2_LEFT -> "2열 왼쪽"
    CarSeat.ROW_2_CENTER -> "2열 가운데"
    CarSeat.ROW_2_RIGHT -> "2열 오른쪽"
    CarSeat.ROW_3_LEFT -> "3열 왼쪽"
    CarSeat.ROW_3_RIGHT -> "3열 오른쪽"
}
private fun seatList(s: List<CarSeat>) = s.joinToString(", ") { seatName(it) }

fun PuzzleSpec.title(): String = when (this) {
    is PuzzleSpec.Window -> if (area == null) "창문 금고" else "${windowWho(area)} 창문"
    is PuzzleSpec.Morse -> "독서등 등대"
    is PuzzleSpec.Signals -> "깜빡이 암호"
    is PuzzleSpec.SteeringHeat -> "따뜻한 핸들"
    is PuzzleSpec.SeatHeat -> "따뜻한 의자"
    is PuzzleSpec.Wiper -> "와이퍼 인사"
    is PuzzleSpec.Memory -> "기억 금고"
    is PuzzleSpec.Riddle -> "수수께끼 금고"
    is PuzzleSpec.Chain -> if (steps.all { it is PuzzleSpec.Window }) "릴레이 창문" else "연쇄 금고"
    is PuzzleSpec.Belts -> when {
        ordered -> "찰칵 릴레이"
        seats.size > 1 -> "모두 찰칵"
        else -> "찰칵 금고"
    }
    is PuzzleSpec.Twin -> "쌍둥이 창문"
}

fun PuzzleSpec.prompt(): String = when (this) {
    is PuzzleSpec.Window -> "${windowWho(area)} 창문을 ${windowWords(target)} 내려 보세요"
    is PuzzleSpec.Morse -> "독서등을 ${morseWords(pattern)} 순서로 켰다 꺼 보세요"
    is PuzzleSpec.Signals -> "방향지시등을 ${syms(target)} 순서로 켜 보세요"
    is PuzzleSpec.SteeringHeat -> "핸들 열선을 ${level}단으로 맞춰 보세요"
    is PuzzleSpec.SeatHeat -> "운전석 시트 열선을 ${level}단으로 맞춰 보세요"
    is PuzzleSpec.Wiper -> "와이퍼를 한 번만 닦기로 톡, ${times}번 움직여 보세요"
    is PuzzleSpec.Memory -> "깜빡이 순서를 잘 보고 외워요. 사라지면 똑같이 켜 보세요"
    // The riddle card shows the question; the prompt keeps only the action (TTS speaks both).
    is PuzzleSpec.Riddle -> "답을 찾으면 ${clue(inner).first}${clue(inner).second}"
    is PuzzleSpec.Chain -> "차례대로 해 보세요. " + steps.mapIndexed { i, st -> "${ORD.getOrElse(i) { "${i + 1}번" }} ${st.chip()}" }.joinToString(", ")
    is PuzzleSpec.Belts -> when {
        seats.isEmpty() -> "앞자리에서 ${count}명이 벨트를 풀었다가 다시 찰칵 채워 보세요"
        ordered -> "${seatList(seats)} 순서로 한 명씩 벨트를 다시 찰칵 채워 보세요"
        seats.size == 1 -> "${seatName(seats[0])} 자리만 벨트를 풀었다가 다시 찰칵 채워 보세요"
        else -> "${seatList(seats)} 모두 벨트를 풀었다가 다시 찰칵 채워 보세요"
    }
    is PuzzleSpec.Twin -> "운전석과 ${windowWho(area)} 창문을 똑같은 높이로 내려 보세요"
}

/** Short step label for chain chips. */
fun PuzzleSpec.chip(): String = when (this) {
    is PuzzleSpec.Window -> "${windowWho(area)} 창문 ${windowWords(target)}"
    is PuzzleSpec.Signals -> if (target.size == 1) "${sym(target[0])} 깜빡이" else "깜빡이 ${target.size}번"
    is PuzzleSpec.SteeringHeat -> "핸들 열선 ${level}단"
    is PuzzleSpec.SeatHeat -> "시트 열선 ${level}단"
    is PuzzleSpec.Wiper -> "와이퍼 ${times}번"
    is PuzzleSpec.Morse -> "독서등 신호 ${pattern.length}번"
    else -> title()
}
private val ORD = listOf("첫째", "둘째", "셋째", "넷째")

/** Riddle wording per inner spec: (who + how, nudge hint). */
private fun clue(inner: PuzzleSpec): Pair<String, String> = when (inner) {
    is PuzzleSpec.Wiper -> "앞유리를 닦는 친구를 " to "그 수만큼 톡톡 움직여 보세요"
    is PuzzleSpec.SeatHeat -> "운전석 엉덩이를 데워 주는 친구를 " to "그 단계로 맞춰 보세요"
    is PuzzleSpec.SteeringHeat -> "손을 데워 주는 동그란 친구를 " to "그 단계로 맞춰 보세요"
    else -> "바람이 들어오는 운전석 문을 " to "10칸 중 그 칸만큼 내려 보세요"
}
private fun nudge(inner: PuzzleSpec) = when (inner) {
    is PuzzleSpec.Wiper -> "앞유리를 닦는 친구는 바로 와이퍼예요."
    is PuzzleSpec.SeatHeat -> "엉덩이를 데워 주는 친구는 바로 시트 열선이에요."
    is PuzzleSpec.SteeringHeat -> "손을 데워 주는 동그란 친구는 바로 핸들 열선이에요."
    else -> "바람이 들어오는 문은 바로 운전석 창문이에요."
}

fun PuzzleSpec.hints(): List<String> = when (this) {
    is PuzzleSpec.Window -> listOf(
        if (area == null) "창문 버튼을 짧게 여러 번 눌러 조금씩 움직여 보세요." else "${windowWho(area)}에 앉은 친구가 창문 버튼을 짧게 여러 번 눌러요.",
        "바늘이 초록 칸에 들어가면 손을 떼고 기다려요.",
        "정답: ${windowWho(area)} 창문을 ${target}% 근처에 두고 2초 기다리기.",
    )
    is PuzzleSpec.Morse -> listOf(
        "짧게는 톡 켰다 끄기, 길게는 하나 둘 세고 끄기.",
        "중간에 오래 쉬면 처음부터 다시 시작해요.",
        "정답: ${morseWords(pattern)}.",
    )
    is PuzzleSpec.Signals -> listOf(
        "방향지시등 레버를 위아래로 움직여요.",
        "같은 방향을 또 하려면 가운데로 돌렸다가 다시 켜요.",
        "정답: ${syms(target)}.",
    )
    is PuzzleSpec.SteeringHeat -> listOf(
        "핸들 열선 버튼은 공조 화면이나 핸들 근처에 있어요.",
        "버튼을 누를 때마다 한 단씩 바뀌어요.",
        "정답: ${level}단에서 잠깐 기다리기.",
    )
    is PuzzleSpec.SeatHeat -> listOf(
        "시트 열선 버튼은 공조 화면에 있어요.",
        "버튼을 누를 때마다 한 단씩 바뀌어요.",
        "정답: 운전석 ${level}단에서 잠깐 기다리기.",
    )
    is PuzzleSpec.Wiper -> listOf(
        "와이퍼 레버를 한 번만 닦기 쪽으로 톡 움직였다가 놓아요.",
        "다 하고 2초 기다리면 개수를 세요.",
        "정답: 한 번만 닦기 ${times}번.",
    )
    is PuzzleSpec.Memory -> listOf(
        "왼쪽, 오른쪽을 소리 내어 따라 말하면 잘 외워져요.",
        "첫 번째는 ${sym(target.first())}이에요.",
        "정답: ${syms(target)}.",
    )
    is PuzzleSpec.Riddle -> listOf(nudge(inner), "$math, 답은 ${answer}!",
        if (inner is PuzzleSpec.Window) "정답: 운전석 창문 10칸 중 ${answer}칸." else "정답: ${inner.prompt()}.")
    is PuzzleSpec.Chain -> listOf(
        "칩 하나씩 차례로 해요. 칩에 불이 들어오면 다음 칩으로 가요.",
        "순서를 건너뛰면 다음 칩으로 넘어가지 않아요.",
        "정답: ${steps.joinToString(", 그다음 ") { it.prompt() }}.",
    )
    is PuzzleSpec.Belts -> listOf(
        "벨트를 풀었다가 다시 찰칵 끼우면 한 번으로 세요.",
        if (ordered) "적힌 순서대로 해요. 순서가 틀리면 처음부터 다시 해요." else "한 사람씩 천천히 해도 괜찮아요.",
        if (seats.isEmpty()) "정답: 앞자리 ${count}곳에서 찰칵." else "정답: ${seatList(seats)} 찰칵.",
    )
    is PuzzleSpec.Twin -> listOf(
        "${windowWho(area)} 친구와 운전석이 같이 창문 버튼을 눌러요.",
        "두 바늘이 가까워지면 손을 떼고 기다려요. 끝까지 열거나 닫으면 안 돼요.",
        "정답: 두 창문 모두 절반쯤 내리고 2초 기다리기.",
    )
}

fun PuzzleSpec.kind(): MissionKind = when (this) {
    is PuzzleSpec.Window -> MissionKind.WINDOW
    is PuzzleSpec.Morse -> MissionKind.MORSE
    is PuzzleSpec.Signals -> MissionKind.SIGNALS
    is PuzzleSpec.SteeringHeat -> MissionKind.STEERING_HEAT
    is PuzzleSpec.SeatHeat -> MissionKind.SEAT_HEAT
    is PuzzleSpec.Wiper -> MissionKind.WIPER
    is PuzzleSpec.Memory -> MissionKind.MEMORY
    is PuzzleSpec.Riddle -> MissionKind.RIDDLE
    is PuzzleSpec.Chain -> MissionKind.CHAIN
    is PuzzleSpec.Belts -> MissionKind.SEATBELT
    is PuzzleSpec.Twin -> MissionKind.TWIN_WINDOW
}

/** The number a riddle can hide, null = not riddle-able. Window answers in tenths (30% = 3칸). */
fun PuzzleSpec.number(): Int? = when (this) {
    is PuzzleSpec.Wiper -> times
    is PuzzleSpec.SeatHeat -> level
    is PuzzleSpec.SteeringHeat -> level
    is PuzzleSpec.Window -> if (area == null) target / 10 else null
    else -> null
}

/** Default spec per input, used when the car lacks the original puzzle's input. */
fun defaultSpec(input: VehicleInput): PuzzleSpec = when (input) {
    VehicleInput.WINDOW -> PuzzleSpec.Window(50)
    VehicleInput.READING_LIGHT -> PuzzleSpec.Morse(".-")
    VehicleInput.TURN_SIGNAL -> PuzzleSpec.Signals(listOf("L", "R", "L"))
    VehicleInput.STEERING_HEAT -> PuzzleSpec.SteeringHeat(1)
    VehicleInput.SEAT_HEAT -> PuzzleSpec.SeatHeat(2)
    VehicleInput.WIPER -> PuzzleSpec.Wiper(2)
    VehicleInput.SEATBELT -> PuzzleSpec.Belts(count = 1)
    VehicleInput.PASSENGER_WINDOW -> PuzzleSpec.Window(50, area = CarWindow.ROW_1_RIGHT)
    VehicleInput.REAR_WINDOW -> PuzzleSpec.Window(50, area = CarWindow.ROW_2_RIGHT)
}

/**
 * Swap puzzles whose inputs the car lacks for the least used supported input (ties: enum order).
 * A chain keeps its supported steps, a lone 2nd row window moves to a front window. REAR_WINDOW is never picked as a swap (child lock).
 * Story and reward stay, only the action changes. No supported inputs at all: keep as is (skip still works).
 */
fun substitute(puzzles: List<Puzzle>, supported: Set<VehicleInput>): List<Puzzle> {
    val picks = VehicleInput.entries.filter { it in supported && it != VehicleInput.REAR_WINDOW }
    if (picks.isEmpty()) return puzzles
    val ok = { s: PuzzleSpec -> s.inputs.all { it in supported } }
    val used = puzzles.map { it.spec.input }.filter { it in supported }.groupingBy { it }.eachCount().toMutableMap()
    return puzzles.map { p ->
        val spec = p.spec
        when {
            ok(spec) -> p
            spec is PuzzleSpec.Chain && spec.steps.any(ok) ->
                p.copy(spec = spec.steps.filter(ok).let { it.singleOrNull() ?: PuzzleSpec.Chain(it) })
            // 2nd row window missing or child-locked: the same window mission moves to a front window.
            spec is PuzzleSpec.Window && spec.input == VehicleInput.REAR_WINDOW &&
                (VehicleInput.PASSENGER_WINDOW in supported || VehicleInput.WINDOW in supported) ->
                p.copy(spec = spec.copy(area = if (VehicleInput.PASSENGER_WINDOW in supported) CarWindow.ROW_1_RIGHT else null))
            else -> {
                // a second wiper puzzle only when nothing else is left (dry-glass wear)
                val pick = picks.filter { it != VehicleInput.WIPER || (used[it] ?: 0) == 0 }.ifEmpty { picks }.minBy { used[it] ?: 0 }
                used[pick] = (used[pick] ?: 0) + 1
                p.copy(spec = defaultSpec(pick))
            }
        }
    }
}

// ---- randomization ----

private val WINDOW_TARGETS = listOf(30, 50, 70)
private val ITEMS = listOf("풍선" to "개가 날아가면", "사과" to "개를 먹으면", "별" to "개가 숨으면", "쿠키" to "개를 먹으면")
/** Belt missions stay in the front row: a rear belt may hold a child car seat, and unbuckling it loosens the seat. */
private val FRONT = listOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_1_RIGHT)
private fun lr(rnd: Random, n: Int) = List(n) { if (rnd.nextBoolean()) "L" else "R" }

/** Same kind, fresh numbers. seats = occupied seats (empty = unknown), people = occupant count. */
internal fun PuzzleSpec.reroll(rnd: Random, seats: Set<CarSeat>, people: Int): PuzzleSpec = when (this) {
    // 2nd row: a child may be alone at that switch, so half open at most
    is PuzzleSpec.Window -> copy(target = WINDOW_TARGETS.random(rnd).let { if (area == CarWindow.ROW_2_LEFT || area == CarWindow.ROW_2_RIGHT) minOf(it, 50) else it })
    is PuzzleSpec.Morse -> copy(pattern = String(CharArray(rnd.nextInt(2, 5)) { if (rnd.nextBoolean()) '.' else '-' }))
    is PuzzleSpec.Signals -> copy(target = lr(rnd, rnd.nextInt(3, 6)))
    is PuzzleSpec.Memory -> PuzzleSpec.Memory(lr(rnd, rnd.nextInt(3, 6)))
    is PuzzleSpec.SteeringHeat -> copy(level = rnd.nextInt(1, max + 1))
    is PuzzleSpec.SeatHeat -> copy(level = rnd.nextInt(1, max + 1))
    is PuzzleSpec.Wiper -> copy(times = rnd.nextInt(1, 4))
    is PuzzleSpec.Twin -> this
    is PuzzleSpec.Chain -> copy(steps = steps.map { it.reroll(rnd, seats, people) })
    is PuzzleSpec.Riddle -> riddle(inner.reroll(rnd, seats, people), rnd)
    is PuzzleSpec.Belts -> {
        // Same draws on every path: 오늘의 금고 must not shift with who sits where.
        val all = rnd.nextBoolean()
        val pick = rnd.nextInt(FRONT.size)
        val occ = FRONT.filter { it in seats } // enum order = row order
        when {
            occ.size >= 2 && (ordered || all) -> PuzzleSpec.Belts(occ, ordered = ordered)
            occ.isNotEmpty() -> PuzzleSpec.Belts(listOf(occ[pick % occ.size]))
            else -> PuzzleSpec.Belts(count = people.coerceIn(1, FRONT.size)) // occupancy unknown: any front seats
        }
    }
}

/** Hides [inner]'s number behind a 6 to 10 year old level sum. Not riddle-able: inner unchanged. */
internal fun riddle(inner: PuzzleSpec, rnd: Random): PuzzleSpec {
    val n = inner.number() ?: return inner
    val (item, gone) = ITEMS.random(rnd)
    // Window answers in 칸 = (pct + 5) / 10 on screen: tolerance 4 accepts only the answer's cell.
    val win = if (inner is PuzzleSpec.Window) inner.copy(tolerance = 4) else inner
    return if (n >= 2 && rnd.nextBoolean()) {
        val a = rnd.nextInt(1, n)
        PuzzleSpec.Riddle(win, "$item ${a}개에 ${n - a}개를 더하면 몇 개일까요?", "$a 더하기 ${n - a}", n)
    } else {
        val b = rnd.nextInt(1, 3)
        PuzzleSpec.Riddle(win, "$item ${n + b}개 중에 $b$gone 몇 개가 남을까요?", "${n + b} 빼기 $b", n)
    }
}

private enum class Twist { MEMORY, RIDDLE, CHAIN, TIMED }

private fun Twist.fits(p: Puzzle) = when (this) {
    Twist.MEMORY -> p.spec is PuzzleSpec.Signals
    Twist.RIDDLE -> p.spec.number() != null
    Twist.CHAIN -> p.spec is PuzzleSpec.Window || p.spec is PuzzleSpec.Signals || p.spec is PuzzleSpec.SeatHeat ||
        p.spec is PuzzleSpec.SteeringHeat || p.spec is PuzzleSpec.Morse
    Twist.TIMED -> p.timedSec == null
}

private fun Twist.apply(p: Puzzle, rnd: Random, avoid: Set<VehicleInput>): Puzzle = when (this) {
    Twist.MEMORY -> p.copy(spec = PuzzleSpec.Memory((p.spec as PuzzleSpec.Signals).target))
    Twist.RIDDLE -> p.copy(spec = riddle(p.spec, rnd))
    Twist.CHAIN -> {
        // no wiper steps: wipes stay capped at 3 per puzzle
        val extras = listOf(
            PuzzleSpec.Signals(lr(rnd, 1)),
            PuzzleSpec.SeatHeat(rnd.nextInt(1, 4)),
            PuzzleSpec.SteeringHeat(rnd.nextInt(1, 3)),
            PuzzleSpec.Window(WINDOW_TARGETS.random(rnd)),
        ).filter { it.input != p.spec.input && it.input !in avoid }.shuffled(rnd).take(rnd.nextInt(1, 3))
        p.copy(spec = PuzzleSpec.Chain((listOf(p.spec) + extras).shuffled(rnd)))
    }
    Twist.TIMED -> p.copy(timedSec = when (p.spec) {
        is PuzzleSpec.Chain -> 90
        is PuzzleSpec.Riddle -> 75
        is PuzzleSpec.Memory, is PuzzleSpec.Belts, is PuzzleSpec.Twin -> 60
        else -> 45
    })
}

/** Puzzles for one play: middle slots shuffled (story moves with its puzzle), numbers re-rolled, ep.twists twists. */
internal fun roll(ep: Episode, rnd: Random, seats: Set<CarSeat> = emptySet(), people: Int = 1): List<Puzzle> {
    if (ep.fixed) return ep.puzzles
    val ps = ep.puzzles
    val order = if (ps.size > 2) listOf(ps.first()) + ps.subList(1, ps.size - 1).shuffled(rnd) + ps.last() else ps
    val out = order.map { it.copy(spec = it.spec.reroll(rnd, seats, people)) }.toMutableList()
    val twisted = mutableSetOf<Int>()
    // TIMED last: its time depends on the final kind (a later CHAIN or RIDDLE needs more)
    for (t in Twist.entries.shuffled(rnd).take(ep.twists).sortedBy { it == Twist.TIMED }) {
        val i = out.indices.shuffled(rnd).firstOrNull { (t == Twist.TIMED || it !in twisted) && t.fits(out[it]) } ?: continue
        out[i] = t.apply(out[i], rnd, ep.avoid)
        if (t != Twist.TIMED) twisted += i
    }
    return out
}

/** Heat levels within this car's capability max. A riddle whose answer moved is asked again. */
internal fun PuzzleSpec.fit(vs: VehicleState): PuzzleSpec = when (this) {
    is PuzzleSpec.SteeringHeat -> vs.steeringHeatMax.coerceAtLeast(1).let { m -> copy(level = level.coerceAtMost(m), max = m) }
    is PuzzleSpec.SeatHeat -> vs.seatHeatMax.coerceAtLeast(1).let { m -> copy(level = level.coerceAtMost(m), max = m) }
    is PuzzleSpec.Chain -> copy(steps = steps.map { it.fit(vs) })
    is PuzzleSpec.Riddle -> inner.fit(vs).let { if (it.number() == answer) copy(inner = it) else riddle(it, Random(answer)) }
    else -> this
}

private fun VehicleState.withWindow(area: CarWindow?, v: Int) =
    if (area == null) copy(windowDriverPercent = v) else copy(windows = windows + (area to v))

fun PuzzleSpec.demo(): List<DemoStep> = when (this) {
    is PuzzleSpec.Window -> listOf(
        DemoStep(300) { it.withWindow(area, if (target > 50) 0 else 100) },
        DemoStep(holdMs + 300) { it.withWindow(area, target) },
    )
    is PuzzleSpec.Morse -> listOf(DemoStep(gapResetMs + 100) { it.copy(readingLightOn = false) }) +
        pattern.flatMap { c ->
            listOf(
                DemoStep(if (c == '.') 200 else longMs + 300) { it.copy(readingLightOn = true) },
                DemoStep(300) { it.copy(readingLightOn = false) },
            )
        }
    is PuzzleSpec.Signals -> listOf(DemoStep(300) { it.copy(turnSignal = TurnSignal.OFF) }) +
        target.flatMap { s ->
            val sig = if (s == "L") TurnSignal.LEFT else TurnSignal.RIGHT
            listOf(DemoStep(300) { it.copy(turnSignal = sig) }, DemoStep(300) { it.copy(turnSignal = TurnSignal.OFF) })
        }
    is PuzzleSpec.SteeringHeat -> listOf(
        DemoStep(300) { it.copy(steeringHeatLevel = if (level == max) 0 else max) },
        DemoStep(holdMs + 300) { it.copy(steeringHeatLevel = level) },
    )
    is PuzzleSpec.SeatHeat -> listOf(
        DemoStep(300) { it.copy(seatHeatDriverLevel = if (level == max) 0 else max) },
        DemoStep(holdMs + 300) { it.copy(seatHeatDriverLevel = level) },
    )
    is PuzzleSpec.Wiper -> listOf(DemoStep(doneMs + 300) { it.copy(wiperOn = false) }) +
        (1..times).flatMap {
            listOf(DemoStep(300) { s: VehicleState -> s.copy(wiperOn = true) }, DemoStep(300) { s: VehicleState -> s.copy(wiperOn = false) })
        } + DemoStep(doneMs + 300) { it }
    is PuzzleSpec.Memory -> listOf(DemoStep(revealMs + 300) { it.copy(turnSignal = TurnSignal.OFF) }) + PuzzleSpec.Signals(target).demo()
    is PuzzleSpec.Riddle -> inner.demo()
    is PuzzleSpec.Chain -> steps.flatMap { it.demo() }
    is PuzzleSpec.Belts -> {
        val order = seats.ifEmpty { CarSeat.entries.take(count) }
        listOf(DemoStep(300) { it.copy(belts = it.belts - order.toSet()) }) +
            order.map { seat -> DemoStep(300) { s: VehicleState -> s.copy(belts = s.belts + seat) } }
    }
    is PuzzleSpec.Twin -> listOf(
        DemoStep(300) { it.withWindow(null, 0).withWindow(area, 0) },
        DemoStep(holdMs + 300) { it.withWindow(null, 50).withWindow(area, 50) },
    )
}

/** Live progress of one puzzle. The start state is the baseline: inputs already active then never count. */
/** Dry-glass wipes per puzzle across retries (targets are at most 3). */
internal const val WIPE_CAP = 6

internal class PuzzleRun(private val spec: PuzzleSpec, start: VehicleState) {
    /** TIRED = wiper wear cap reached: the game moves on (counts as a skip). */
    enum class Out { NONE, TICK, WRONG, SOLVED, TIRED }

    private var prev = start
    private var armed = false
    private var holdSince: Long? = null
    private var entered = ""
    private val seq = mutableListOf<String>()
    private var pressAt: Long? = null
    private var lastActAt = 0L
    private var wipes = 0
    private var totalWipes = 0
    private var wiperArmed = false
    private val clicked = mutableListOf<CarSeat>()
    private var startAt: Long? = null // Memory reveal clock
    private var stepIdx = 0 // Chain
    /** Car part to show right now: a chain's active step, else the puzzle's main input. */
    val input: VehicleInput get() = (spec as? PuzzleSpec.Chain)?.steps?.getOrNull(stepIdx)?.input ?: spec.input
    /** Hold puzzle that began already on target: it only counts after leaving the target once. */
    val needsWiggle: Boolean get() = sub?.needsWiggle ?: !armed
    private var sub: PuzzleRun? = when (spec) { // Riddle / Chain delegate; Memory creates it after the reveal
        is PuzzleSpec.Riddle -> PuzzleRun(spec.inner, start)
        is PuzzleSpec.Chain -> PuzzleRun(spec.steps.first(), start)
        else -> null
    }

    init {
        armed = when (spec) {
            is PuzzleSpec.Window -> !inBand(spec, start)
            is PuzzleSpec.Twin -> !twinOk(spec, start)
            is PuzzleSpec.SteeringHeat -> start.steeringHeatLevel != spec.level
            is PuzzleSpec.SeatHeat -> start.seatHeatDriverLevel != spec.level
            else -> true
        }
    }

    private fun inBand(w: PuzzleSpec.Window, s: VehicleState) = kotlin.math.abs(s.windowPercent(w.area) - w.target) <= w.tolerance
    private fun twinOk(t: PuzzleSpec.Twin, s: VehicleState): Boolean {
        val a = s.windowDriverPercent
        val b = s.windowPercent(t.area)
        return a in t.min..t.max && b in t.min..t.max && kotlin.math.abs(a - b) <= t.tolerance
    }

    fun step(s: VehicleState, now: Long): Out = when (spec) {
        is PuzzleSpec.Window -> hold(inBand(spec, s), spec.holdMs, now)
        is PuzzleSpec.Twin -> hold(twinOk(spec, s), spec.holdMs, now)
        is PuzzleSpec.SteeringHeat -> hold(s.steeringHeatLevel == spec.level, spec.holdMs, now)
        is PuzzleSpec.SeatHeat -> hold(s.seatHeatDriverLevel == spec.level, spec.holdMs, now)
        is PuzzleSpec.Morse -> morse(spec, s, now)
        is PuzzleSpec.Signals -> signals(spec, s)
        is PuzzleSpec.Wiper -> wiper(spec, s, now)
        is PuzzleSpec.Belts -> belts(spec, s)
        is PuzzleSpec.Riddle -> sub!!.step(s, now)
        is PuzzleSpec.Memory -> memory(spec, s, now)
        is PuzzleSpec.Chain -> chain(spec, s, now)
    }.also { prev = s }

    private fun hold(ok: Boolean, holdMs: Long, now: Long): Out {
        if (!ok) { armed = true; holdSince = null; return Out.NONE }
        if (!armed) return Out.NONE
        val since = holdSince ?: run { holdSince = now; return Out.TICK }
        return if (now - since >= holdMs) Out.SOLVED else Out.NONE
    }

    private fun check(done: Boolean, prefix: Boolean, reset: () -> Unit) = when {
        done -> Out.SOLVED
        prefix -> Out.TICK
        else -> { reset(); Out.WRONG }
    }

    private fun morse(m: PuzzleSpec.Morse, s: VehicleState, now: Long): Out {
        val on = s.readingLightOn
        if (!prev.readingLightOn && entered.isNotEmpty() && now - lastActAt >= m.gapResetMs) entered = ""
        if (on && !prev.readingLightOn) { pressAt = now; return Out.NONE }
        if (!on && prev.readingLightOn) {
            val at = pressAt ?: return Out.NONE // was on at baseline: ignore this release
            pressAt = null
            lastActAt = now
            entered += if (now - at < m.longMs) '.' else '-'
            return check(entered == m.pattern, m.pattern.startsWith(entered)) { entered = "" }
        }
        return Out.NONE
    }

    private fun signals(g: PuzzleSpec.Signals, s: VehicleState): Out {
        val sig = s.turnSignal
        if (sig == prev.turnSignal || (sig != TurnSignal.LEFT && sig != TurnSignal.RIGHT)) return Out.NONE
        val sym = if (sig == TurnSignal.LEFT) "L" else "R"
        seq += sym
        return check(seq == g.target, g.target.take(seq.size) == seq) { seq.clear() } // retry from the start
    }

    private fun wiper(w: PuzzleSpec.Wiper, s: VehicleState, now: Long): Out {
        if (!prev.wiperOn && !s.wiperOn && wipes > 0 && now - lastActAt >= w.doneMs) {
            val ok = wipes == w.times
            wipes = 0
            return if (ok) Out.SOLVED else wrongWipe()
        }
        if (s.wiperOn && !prev.wiperOn) { wiperArmed = true; return Out.NONE }
        if (!s.wiperOn && prev.wiperOn && wiperArmed) {
            wiperArmed = false
            wipes++
            totalWipes++
            lastActAt = now
            if (wipes > w.times) { wipes = 0; return wrongWipe() }
            return Out.TICK
        }
        return Out.NONE
    }

    private fun wrongWipe() = if (totalWipes >= WIPE_CAP) Out.TIRED else Out.WRONG

    /** Only fresh clicks (unbuckled -> buckled) count, so belts already on at baseline need a re-click. */
    private fun belts(b: PuzzleSpec.Belts, s: VehicleState): Out {
        var out = Out.NONE
        for (seat in (s.belts - prev.belts).sorted()) {
            when {
                b.seats.isEmpty() && seat !in FRONT -> continue // count mode: front seats only
            b.ordered -> if (b.seats.getOrNull(clicked.size) == seat) clicked += seat else {
                    clicked.clear()
                    if (b.seats.first() == seat) clicked += seat
                    out = Out.WRONG
                    continue
                }
                seat in clicked -> continue
                b.seats.isNotEmpty() && seat !in b.seats -> { out = Out.WRONG; continue }
                else -> clicked += seat
            }
            if (clicked.size >= b.need) return Out.SOLVED
            out = Out.TICK
        }
        return out
    }

    private fun memory(m: PuzzleSpec.Memory, s: VehicleState, now: Long): Out {
        val t0 = startAt ?: now.also { startAt = it }
        val run = sub ?: run {
            if (now - t0 >= m.revealMs) sub = PuzzleRun(PuzzleSpec.Signals(m.target), s) // baseline after the reveal
            return Out.NONE
        }
        return run.step(s, now)
    }

    private fun chain(c: PuzzleSpec.Chain, s: VehicleState, now: Long): Out {
        val o = sub!!.step(s, now)
        if (o != Out.SOLVED) return o
        if (++stepIdx == c.steps.size) return Out.SOLVED
        sub = PuzzleRun(c.steps[stepIdx], s)
        return Out.TICK
    }

    fun visual(s: VehicleState, now: Long): PuzzleVisual = when (spec) {
        is PuzzleSpec.Window -> PuzzleVisual.Dial(spec.target, s.windowPercent(spec.area), spec.tolerance)
        is PuzzleSpec.Morse -> PuzzleVisual.Morse(spec.pattern, entered)
        is PuzzleSpec.Signals -> PuzzleVisual.Sequence(spec.target, seq.toList())
        is PuzzleSpec.SteeringHeat -> PuzzleVisual.Level(spec.level, s.steeringHeatLevel, spec.max)
        is PuzzleSpec.SeatHeat -> PuzzleVisual.Level(spec.level, s.seatHeatDriverLevel, spec.max)
        is PuzzleSpec.Wiper -> PuzzleVisual.Level(spec.times, wipes, spec.times)
        is PuzzleSpec.Belts -> PuzzleVisual.Seats(spec.seats, spec.need, clicked.toList(), s.belts, spec.ordered)
        is PuzzleSpec.Twin -> PuzzleVisual.Twin(s.windowDriverPercent, s.windowPercent(spec.area), spec.tolerance, spec.min, spec.max)
        is PuzzleSpec.Chain -> PuzzleVisual.Chain(spec.steps.map { it.chip() }, stepIdx, sub!!.visual(s, now))
        is PuzzleSpec.Riddle -> when (val v = sub!!.visual(s, now)) {
            is PuzzleVisual.Dial -> PuzzleVisual.Riddle(spec.question, (v.current + 5) / 10, 10)
            is PuzzleVisual.Level -> PuzzleVisual.Riddle(spec.question, v.current, v.max)
            else -> PuzzleVisual.Riddle(spec.question, 0, spec.answer)
        }
        is PuzzleSpec.Memory -> {
            val left = if (sub == null) (spec.revealMs - (now - (startAt ?: now))).coerceAtLeast(0) else 0L
            val entered = (sub?.visual(s, now) as? PuzzleVisual.Sequence)?.entered.orEmpty()
            PuzzleVisual.Memory(if (left > 0) spec.target else spec.target.map { "?" }, entered, left)
        }
    }
}
