package com.carescape.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// Shared contract between vehicle/, game/, ui/ packages. Change only via integration step.

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT, HAZARD }

/** Car's drive mode (Pleos DrivingModeType). Read only: the app never sets it. */
enum class DriveMode { NORMAL, ECO, SPORT }

/** Car parts the game can read as puzzle input. */
enum class VehicleInput {
    WINDOW, READING_LIGHT, TURN_SIGNAL, STEERING_HEAT, SEAT_HEAT, WIPER,
    SEATBELT, // buckled state per CarSeat (CAR_SEATS)
    PASSENGER_WINDOW, // ROW_1_RIGHT window position
    REAR_WINDOW, // 2nd row windows; child lock may block them, so only used where an episode asks
}

/** Seats as Pleos SeatArea names. ROW_1_LEFT = driver (left hand drive). IONIQ 9: 6 or 7 seats. */
enum class CarSeat { ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT, ROW_3_LEFT, ROW_3_RIGHT }

/** Windows besides the driver's (driver stays in VehicleState.windowDriverPercent). */
enum class CarWindow { ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT }

/** Mission kinds for badges (first clear of each). badge = short Korean name. */
enum class MissionKind(val badge: String) {
    WINDOW("창문"), MORSE("등대"), SIGNALS("깜빡이"), STEERING_HEAT("따뜻한 핸들"), SEAT_HEAT("따뜻한 의자"),
    WIPER("와이퍼"), MEMORY("기억"), RIDDLE("수수께끼"), CHAIN("연쇄"), SEATBELT("찰칵"),
    TWIN_WINDOW("쌍둥이 창문"), LIGHTNING("번개"),
}

/** Snapshot of everything the game cares about. Percent values are 0..100. */
data class VehicleState(
    val gear: Gear = Gear.PARK,
    val speedKmh: Float = 0f,
    val socPercent: Float = 42f,
    val charging: Boolean = true,
    val chargeTargetPercent: Int = 80,
    val windowDriverPercent: Int = 0, // 0 = closed, 100 = fully open
    val readingLightOn: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val steeringHeatLevel: Int = 0, // 0 = off
    val seatHeatDriverLevel: Int = 0, // 0 = off
    val wiperOn: Boolean = false,
    val occupants: Int = 1,
    val windows: Map<CarWindow, Int> = emptyMap(), // 0..100 like the driver's, absent = unknown
    val belts: Set<CarSeat> = emptySet(), // buckled seats
    val occupied: Set<CarSeat> = emptySet(), // occupied seats, empty = unknown
    val beltSeats: Set<CarSeat> = emptySet(), // seats that report a belt-buckle state, empty = unknown
    val steeringHeatMax: Int = 2, // capability max level (raw max minus off)
    val seatHeatMax: Int = 3,
    // v3 additions. null / false = unknown or not reported: features that need them leave their line out.
    val chargePortOpen: Boolean? = null, // EV charge port (Pleos EvBattery)
    val rangeMeters: Float? = null, // remaining range (Pleos Odometer; Virtual Console card is in m)
    val doorOpen: Boolean = false, // any cabin door open (Pleos Door position)
    val rearWindowLocked: Boolean = false, // 2nd row window lock (child lock) on
    // fusion additions (출발 금고)
    val doorLocked: Boolean? = null, // true = every reported door lock area locked (Pleos Door lock), null = unknown
    val socKnown: Boolean = true, // false until the car reported a real SoC: socPercent is then only a placeholder
    // 마이 차고: skin follows the mode while parked. null = unknown (no capability, simulator until set).
    val driveMode: DriveMode? = null,
) {
    /** null = driver window. Unknown windows read 0 (closed). */
    fun windowPercent(area: CarWindow?): Int = if (area == null) windowDriverPercent else windows[area] ?: 0
}

/**
 * 주차 암호: what the grown-up typed at the car's side (pillar sign), never a measured position.
 * floor -6..-1 = 지하 N층, 0 = 지상. zone "" = no letter.
 */
data class ParkingAnchor(val floor: Int, val zone: String, val number: String) {
    private val floorName get() = if (floor < 0) "지하 ${-floor}층" else "지상"
    private val spot get() = listOf(zone, number).filter { it.isNotEmpty() }.joinToString(" ")
    /** "지하 4층 · C 12" */
    fun label() = listOf(floorName, spot).filter { it.isNotEmpty() }.joinToString(" · ")
    /** "지하 4층, C 12" (for TTS) */
    fun spoken() = listOf(floorName, spot).filter { it.isNotEmpty() }.joinToString(", ")
}

/** Source of vehicle data. Pleos SDK in the car, in-app simulator for demos. */
interface VehicleGateway {
    val state: StateFlow<VehicleState>
    /** Inputs this car actually supports (capability check). Puzzles needing others get swapped. */
    val supportedInputs: StateFlow<Set<VehicleInput>>
    fun start()
    fun stop()
    /** Best effort ambient light cue. color = 0xRRGGBB, brightness 0..100. */
    fun setAmbient(color: Int, brightness: Int)
    /** Put ambient light back to what it was before the game touched it. */
    fun restoreAmbient()
}

/** PAUSED_DOOR: a door opened mid episode; voice and timers stop until the family taps resume. */
enum class Phase { TITLE, MATCHING, INTRO, PUZZLE, SOLVED, ENDING, PAUSED_DRIVING, PAUSED_DOOR }

/** What the puzzle screen draws. */
sealed interface PuzzleVisual {
    /** Safe dial driven by a 0..100 value (e.g. window position). */
    data class Dial(val target: Int, val current: Int, val tolerance: Int) : PuzzleVisual
    /** Morse / long-short pattern, e.g. "-.-". entered grows as the player inputs. */
    data class Morse(val pattern: String, val entered: String) : PuzzleVisual
    /** Ordered symbols, e.g. ["L","R","R","L"]. */
    data class Sequence(val target: List<String>, val entered: List<String>) : PuzzleVisual
    /** Level gauge, e.g. heat level 0..max. */
    data class Level(val target: Int, val current: Int, val max: Int) : PuzzleVisual
    /** Sequence shown for revealLeftMs, then hidden: target becomes all "?" while revealLeftMs == 0. */
    data class Memory(val target: List<String>, val entered: List<String>, val revealLeftMs: Long) : PuzzleVisual
    /** Riddle text plus the live value the answer is set on (window in tenths, level, wipe count). */
    data class Riddle(val question: String, val current: Int, val max: Int) : PuzzleVisual
    /** Steps in order as chips; done = chips finished; current = the active step's own visual. */
    data class Chain(val chips: List<String>, val done: Int, val current: PuzzleVisual) : PuzzleVisual
    /** Belt clicks. target empty = any seats, need = clicks wanted, done = seats clicked so far. */
    data class Seats(val target: List<CarSeat>, val need: Int, val done: List<CarSeat>, val buckled: Set<CarSeat>, val ordered: Boolean) : PuzzleVisual
    /** Two windows that must match: driver (left) and the other one (right), 0..100. */
    data class Twin(val left: Int, val right: Int, val tolerance: Int, val min: Int, val max: Int) : PuzzleVisual
}

data class GameUiState(
    val phase: Phase = Phase.TITLE,
    val episodeTitle: String = "",
    val chapterLabel: String = "", // e.g. "1 / 4"
    val puzzleIndex: Int = 0,
    val puzzleCount: Int = 0,
    val puzzleTitle: String = "",
    val story: String = "", // short narrative line
    val prompt: String = "", // what to do, plain words
    val visual: PuzzleVisual? = null,
    val requiredInput: VehicleInput? = null,
    val hintText: String? = null,
    val hintsUsed: Int = 0,
    val socPercent: Float = 0f,
    val targetPercent: Int = 80,
    val chargeEtaMinutes: Int? = null, // null = unknown yet
    val episodeMinutes: Int = 0,
    val elapsedSec: Int = 0,
    val endingText: String = "",
    /** ENDING phase only: every puzzle is solved but charging has not reached the target yet. */
    val waitingForCharge: Boolean = false,
    val episodeId: String = "",
    /** Started via startDaily (오늘의 금고). */
    val daily: Boolean = false,
    val missionKind: MissionKind? = null,
    /** TIMED twist (번개 금고): seconds left for the lightning badge, null = no timer on this puzzle. */
    val bonusSecLeft: Int? = null,
    val bonusSecTotal: Int? = null, // full lightning time of this puzzle (ring scale)
    val lightning: Int = 0, // lightning badges earned this play
    val solvedKinds: Set<MissionKind> = emptySet(), // this play; app merges into saved badges
    /** ENDING only: 1..3 from hints and skips, 0 before. */
    val stars: Int = 0,
    val summary: String = "", // ENDING only, e.g. "힌트 2번, 건너뛰기 0번, 번개 1개"
    /** 1-based episode number (N화) of the running episode, 0 = none. */
    val episodeNumber: Int = 0,
    /** INTRO only: last episode's one line, empty when this play does not follow it. */
    val recap: String = "",
    /** TITLE only: the charge port just opened, so "N화가 준비됐어요" for this N. 0 = no card. */
    val readyEpisode: Int = 0,
    /** PAUSED_DOOR only: false = a door is still open, true = all closed, ask "이어서 할까요?". */
    val doorAsk: Boolean = false,
    /** Final ending only: parts still different from the episode start (원래대로 출발). Empty = 출발 준비 완료, null = not shown. */
    val restoreLeft: List<String>? = null,
    /** TITLE only: parked right after a drive, ask "차에서 기다려요 / 다녀올게요" (갈림길). */
    val parkFork: Boolean = false,
    /** Saved 주차 암호 label, "" = none. */
    val parkAnchor: String = "",
    /** TITLE only: back in the car after leaving it locked (or the app came back with a saved 암호): "다시 탔어요". */
    val returnCard: Boolean = false,
    /** returnCard only: the grown-up tapped 정답 보기. */
    val returnReveal: Boolean = false,
    /** returnReveal only: charge gained while away and range now, "" = nothing known. */
    val awayLine: String = "",
)

sealed interface GameEvent {
    data class Speak(val text: String) : GameEvent
    data class Sfx(val kind: SfxKind) : GameEvent
    data class Ambient(val color: Int, val brightness: Int) : GameEvent
    data object AmbientRestore : GameEvent
    /** Stop the game master mid sentence (door opened). */
    data object Silence : GameEvent
}

enum class SfxKind { TICK, CORRECT, WRONG, UNLOCK, ENDING }

/** Game driver the UI talks to. */
interface EscapeGameController {
    val ui: StateFlow<GameUiState>
    val events: SharedFlow<GameEvent>
    fun onVehicle(state: VehicleState, nowMs: Long)
    fun startMatching()
    fun startEpisode(episodeId: String)
    /** 오늘의 금고: same puzzles for everyone on that day. epochDay from the app (LocalDate.toEpochDay). */
    fun startDaily(episodeId: String, epochDay: Long) = startEpisode(episodeId)
    fun requestHint()
    fun skipPuzzle()
    fun backToTitle()
}

/** In-memory gateway for demos and tests. Driven by the in-app demo panel or a time-lapse charger. */
class SimVehicleGateway(initial: VehicleState = VehicleState()) : VehicleGateway {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val state: StateFlow<VehicleState> = _state
    private val _supported = kotlinx.coroutines.flow.MutableStateFlow(VehicleInput.entries.toSet())
    override val supportedInputs: StateFlow<Set<VehicleInput>> = _supported
    var lastAmbient: Pair<Int, Int>? = null
        private set

    fun update(transform: (VehicleState) -> VehicleState) { _state.value = transform(_state.value) }
    fun setSupported(inputs: Set<VehicleInput>) { _supported.value = inputs }

    override fun start() {}
    override fun stop() {}
    override fun setAmbient(color: Int, brightness: Int) { lastAmbient = color to brightness }
    override fun restoreAmbient() { lastAmbient = null }
}
