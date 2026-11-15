package com.carescape.game

import com.carescape.core.CarWindow
import com.carescape.core.EscapeGameController
import com.carescape.core.GameEvent
import com.carescape.core.GameUiState
import com.carescape.core.MissionKind
import com.carescape.core.Gear
import com.carescape.core.ParkingAnchor
import com.carescape.core.Phase
import com.carescape.core.SfxKind
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Synchronous game engine. Call [onVehicle] on every vehicle change AND on a steady tick
 * (about every 200 ms): holds, Morse timing and phase timers advance only inside it.
 * Time comes only from nowMs. Every start re-rolls the puzzles with [random] (tests pass Random(seed)).
 */
class EscapeGame(
    val episodes: List<Episode> = Episodes.all,
    private var supported: Set<VehicleInput> = VehicleInput.entries.toSet(),
    private val random: Random = Random.Default,
) : EscapeGameController {
    private val _ui = MutableStateFlow(GameUiState())
    override val ui: StateFlow<GameUiState> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GameEvent> = _events.asSharedFlow()
    internal val log = mutableListOf<GameEvent>() // ponytail: test tap, cleared on backToTitle

    val clock = ChargeClock()
    private var vs = VehicleState()
    private var lastNow: Long? = null
    private var phase = Phase.TITLE
    private var pausedFrom: Phase? = null
    private var episode: Episode? = null
    private var puzzles: List<Puzzle> = emptyList()
    private var index = 0
    private var run: PuzzleRun? = null
    private var hintsUsed = 0
    private var elapsedMs = 0L
    private var phaseAt = 0L
    private var story = ""
    private var endingText = ""
    private var waitingForCharge = false
    private var daily = false
    private var hintsTotal = 0
    private var skips = 0
    private var lightning = 0
    private var bonusUntil: Long? = null // elapsedMs deadline of a TIMED twist
    private val solvedKinds = mutableSetOf<MissionKind>()
    private var stars = 0
    private var summary = ""
    private var replugBy: Long? = null // set after a drive: a missing charger is not "charge stopped" until this passes
    private var startVs: VehicleState? = null // car at episode start, for 원래대로 출발. Memory only, dropped with the episode.
    private var restoreDone = false // "출발 준비 완료" already said
    private var nextIndex = 0 // serial: episodes[nextIndex] is the next 화
    private var readyCard = false // TITLE: port opened, "N화가 준비됐어요"
    private var lastPort: Boolean? = null
    private var doorFrom: Phase? = null
    private var doorAsk = false
    private var recap = ""
    private var introMs = INTRO_MS
    // 출발 금고. ponytail: a car without door lock data opens the return card only on relaunch or the title chip;
    // guessing "left" from a second door open/close is skipped on purpose (false alarms with kids getting in and out).
    private var memo: ParkMemo? = null
    private var forkArmed = false // parked after a drive: 갈림길 once for this stop
    private var lastLocked: Boolean? = null
    private var doorSeen = false // away: a door opened with the doors unlocked
    private var returnShown = false // the card was already raised for this away
    private var returnCard = false
    private var reveal = false

    /** 주차 암호 + 떠남 values. The app saves it and puts it back on launch; set = "came back" (다시 켰다 = 다녀왔다). */
    var parkMemo: ParkMemo?
        get() = memo
        set(v) {
            memo = v?.copy(away = true)
            doorSeen = v != null
            returnShown = false
            render()
        }

    /** Serial progress (충전 1회 = 1화): index of the next 화. The app saves it and sets it back on launch. */
    var nextEpisodeIndex: Int
        get() = nextIndex
        set(v) { nextIndex = if (episodes.isEmpty()) 0 else v.mod(episodes.size); render() }

    /** Applies to the next startEpisode. */
    fun setSupportedInputs(inputs: Set<VehicleInput>) { supported = inputs }

    /** Episode that fits the charge ETA: the longest one that fits, else the shortest. Unknown ETA: shortest. */
    fun recommend(): Episode {
        val eta = eta()
        val byLen = episodes.sortedBy { it.minutes }
        return eta?.let { e -> byLen.lastOrNull { it.minutes <= e } } ?: byLen.first()
    }

    /** Puzzles of the running episode after substitution. */
    fun currentPuzzles(): List<Puzzle> = puzzles

    /** State changes that solve puzzle [puzzleIndex] of the running episode (demo panel, tests). */
    fun demoSolution(puzzleIndex: Int): List<DemoStep> = puzzles.getOrNull(puzzleIndex)?.spec?.demo().orEmpty()

    private fun eta() = clock.etaMinutes(vs.socPercent, vs.chargeTargetPercent, vs.charging)
    private fun chargeDone() = vs.socPercent >= vs.chargeTargetPercent

    private fun emit(e: GameEvent) { log += e; _events.tryEmit(e) }
    private fun speak(t: String) = emit(GameEvent.Speak(t))
    private fun sfx(k: SfxKind) = emit(GameEvent.Sfx(k))
    private fun ambient(c: Int, b: Int) = emit(GameEvent.Ambient(c, b))

    override fun onVehicle(state: VehicleState, nowMs: Long) {
        val gap = lastNow?.let { nowMs - it } ?: 0L
        val dt = gap.coerceIn(0, 5_000) // cap: app was backgrounded
        if (gap > 5_000) bonusUntil = null // the lightning clock missed real time: no badge this puzzle
        lastNow = nowMs
        vs = state
        if (state.charging) { clock.add(nowMs, state.socPercent); replugBy = null } else clock.reset()
        // Port closed -> open while the title is up: offer the next 화. Unknown (null) never triggers it.
        val port = state.chargePortOpen
        // A kept 주차 암호 means the family chose to leave: plugging in on the way out does not offer a 화.
        if (lastPort == false && port == true && phase == Phase.TITLE && memo == null) readyCard = true
        if (port != null) lastPort = port
        if (port == true) forkArmed = false // charging: the N화 card takes this stop
        // 떠남 = the doors locked after a 암호 was saved. Values at that moment replace the ones from saving.
        val locked = state.doorLocked
        memo?.let { m ->
            if (locked == true && lastLocked != true) {
                memo = m.copy(away = true, soc = socNow() ?: m.soc, rangeM = state.rangeMeters ?: m.rangeM)
                doorSeen = false
                returnShown = false
            }
        }
        lastLocked = locked

        val driving = state.gear != Gear.PARK || state.speedKmh > 0.5f
        if (driving) {
            if (phase != Phase.PAUSED_DRIVING) {
                pausedFrom = phase
                phase = Phase.PAUSED_DRIVING
                emit(GameEvent.AmbientRestore)
            }
            return render()
        }
        if (phase == Phase.PAUSED_DRIVING) {
            phase = pausedFrom ?: Phase.TITLE
            pausedFrom = null
            // Decision: unplugging is normal when the car leaves, so while driving we only stay paused
            // (no ending, no TTS that distracts the driver). Back in P the driver may be moving to
            // another stall, so give REPLUG_MS to plug in again before calling it "charge stopped".
            if (!state.charging) replugBy = nowMs + REPLUG_MS
            if (phase == Phase.PUZZLE) {
                val p = puzzles[index]
                run = PuzzleRun(p.spec, state) // re-baseline: signals used while driving must not count
                ambient(p.color, 60)
            }
            return render()
        }
        // Door open: voice and timers stop. Doors never mean "got out" or "who is where", only "wait".
        if (phase == Phase.PAUSED_DOOR) {
            if (state.doorOpen) doorAsk = false
            else if (!doorAsk) { doorAsk = true; speak(RESUME_ASK) }
            return render()
        }
        if (state.doorOpen && (phase == Phase.INTRO || phase == Phase.PUZZLE || phase == Phase.SOLVED)) {
            doorFrom = phase
            phase = Phase.PAUSED_DOOR
            doorAsk = false
            emit(GameEvent.Silence)
            return render()
        }

        // 다시 탔어요: away, then unlocked, a door opened, and every door is closed again. Parked title only.
        if (memo?.away == true && !returnShown && state.doorLocked != true) {
            if (state.doorOpen) doorSeen = true
            else if (doorSeen && phase == Phase.TITLE) raiseReturn()
        }

        if (phase == Phase.INTRO || phase == Phase.PUZZLE || phase == Phase.SOLVED) {
            elapsedMs += dt
            if (chargeDone()) { finish(); return render() }
        }
        when (phase) {
            Phase.INTRO -> if (elapsedMs - phaseAt >= introMs) beginPuzzle()
            Phase.PUZZLE -> when (run?.step(state, nowMs)) {
                PuzzleRun.Out.TICK -> sfx(SfxKind.TICK)
                PuzzleRun.Out.WRONG -> sfx(SfxKind.WRONG)
                PuzzleRun.Out.SOLVED -> solved()
                PuzzleRun.Out.TIRED -> tired()
                else -> {}
            }
            Phase.SOLVED -> if (elapsedMs - phaseAt >= SOLVED_MS) next()
            Phase.ENDING -> if (waitingForCharge && (chargeDone() || chargeStopped())) finish()
                else if (!waitingForCharge && !restoreDone && restoreLeft()?.isEmpty() == true) { restoreDone = true; speak(READY_TO_GO) }
            else -> {}
        }
        render()
    }

    override fun startMatching() {
        readyCard = false
        forkArmed = false
        returnCard = false
        phase = Phase.MATCHING
        speak("남은 충전 시간에 딱 맞는 이야기를 골랐어요.")
        render()
    }

    override fun startEpisode(episodeId: String) = startEpisode(episodeId, null)

    override fun startDaily(episodeId: String, epochDay: Long) = startEpisode(episodeId, epochDay)

    /** seed != null: same puzzles for the same seed (오늘의 금고 uses epochDay). */
    fun startEpisode(episodeId: String, seed: Long?) {
        val ep = episodes.firstOrNull { it.id == episodeId } ?: return
        episode = ep
        daily = seed != null
        val beltOk = if (vs.beltSeats.isEmpty()) vs.occupied else vs.occupied intersect vs.beltSeats
        // Child lock on: 2nd row window missions go to a front window (substitute()).
        val usable = supported - ep.avoid - (if (vs.rearWindowLocked) setOf(VehicleInput.REAR_WINDOW) else emptySet())
        puzzles = substitute(roll(ep, seed?.let { Random(it) } ?: random, beltOk, vs.occupants), usable)
            .map { it.copy(spec = it.spec.fit(vs)) }
        val n = episodes.indexOf(ep)
        recap = if (n > 0 && n == nextIndex) ep.recap else "" // only when the family finished the 화 before
        introMs = if (recap.isEmpty()) INTRO_MS else INTRO_MS + RECAP_MS
        startVs = vs
        restoreDone = false
        readyCard = false
        forkArmed = false
        returnCard = false
        doorFrom = null
        doorAsk = false
        hintsTotal = 0
        skips = 0
        lightning = 0
        bonusUntil = null
        solvedKinds.clear()
        stars = 0
        summary = ""
        index = 0
        run = null
        elapsedMs = 0
        phaseAt = 0
        waitingForCharge = false
        endingText = ""
        replugBy = null
        story = ep.intro
        phase = Phase.INTRO
        ambient(ep.color, 70)
        speak("$recap 오늘은 ${n + 1}화예요. ${ep.intro}".trim())
        render()
    }

    /** "N화가 준비됐어요" card: start that 화. */
    fun startNext() { if (episodes.isNotEmpty()) startEpisode(episodes[nextIndex].id) }

    /** Close the card with one tap; other episodes stay one more tap away (시작). */
    fun dismissReady() { readyCard = false; render() }

    // ---------- 출발 금고 ----------

    /** App: raw gear went D/R -> P. Offers the 갈림길 when the title was up and nothing is saved yet. */
    fun onParkedAfterDrive() {
        val base = if (phase == Phase.PAUSED_DRIVING) pausedFrom ?: Phase.TITLE else phase
        forkArmed = base == Phase.TITLE && memo == null
        render()
    }

    /** App: raw gear D or R. Leaving: the 암호 and the 떠남 values are gone, every parking card closes. */
    fun onDriveAway() {
        memo = null
        forkArmed = false
        doorSeen = false
        returnShown = false
        returnCard = false
        reveal = false
        render()
    }

    /** "차에서 기다려요": close the 갈림길 for this stop. */
    fun stayInCar() { forkArmed = false; render() }

    /** "건너뛰기": nothing is saved. */
    fun skipAnchor() { forkArmed = false; render() }

    /** "맞아요" after the grown-up checked the 암호. Parked title only (never while driving). */
    fun saveAnchor(a: ParkingAnchor) {
        if (!parkedTitle()) return
        memo = ParkMemo(a, socNow(), vs.rangeMeters, away = false)
        forkArmed = false
        doorSeen = false
        returnShown = false
        val said = a.spoken()
        speak("오늘의 주차 암호는 $said${isWord(said)}. 다 같이 한 번 따라 말해요.")
        render()
    }

    /** Title chip "주차 암호 보관 중": open the return card by hand (cars without lock data, or the family wants it now). */
    fun openReturn() { if (parkedTitle() && memo != null) raiseReturn() }

    /** "정답 보기". */
    fun revealReturn() {
        val m = memo ?: return
        if (!parkedTitle() || !returnCard) return
        reveal = true
        val said = m.anchor.spoken()
        val away = awayLine(m)
        speak("정답은 $said${wasWord(said)}. $away".trim().replace("km", "킬로미터").replace("%", "퍼센트"))
        render()
    }

    /** "출발 준비": close the card. The 암호 stays until the car drives off. */
    fun closeReturn() { returnCard = false; reveal = false; render() }

    private fun parkedTitle() = phase == Phase.TITLE && vs.gear == Gear.PARK && vs.speedKmh <= 0.5f

    private fun raiseReturn() {
        returnShown = true
        returnCard = true
        reveal = false
        readyCard = false
        speak(RETURN_ASK)
        render()
    }

    private fun socNow() = if (vs.socKnown) vs.socPercent.roundToInt() else null

    /** Charge gained while away (only a real rise of 1 point or more) and the range now. Unknown parts are left out. */
    private fun awayLine(m: ParkMemo): String {
        val now = socNow()
        val charged = if (m.soc != null && now != null && now - m.soc >= 1) "우리가 없는 동안 ${m.soc}%에서 ${now}%까지 충전했어요." else ""
        return listOf(charged, kmLine()).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun kmLine() = vs.rangeMeters?.takeIf { it > 0f }?.let { "이제 ${(it / 1000f).roundToInt()}km 갈 수 있어요." }.orEmpty()

    /** Doors closed and the family tapped "이어서 하기": back where the door stopped us, inputs re-baselined. */
    fun resumeAfterDoor() {
        if (phase != Phase.PAUSED_DOOR || !doorAsk) return
        phase = doorFrom ?: Phase.TITLE
        doorFrom = null
        doorAsk = false
        if (phase == Phase.PUZZLE) {
            val p = puzzles[index]
            run = PuzzleRun(p.spec, vs) // whatever got bumped on the way out must not count
            ambient(p.color, 60)
            speak("${p.story} ${p.prompt}.")
        }
        render()
    }

    /** INTRO -> first puzzle right away (UI "계속" button). Without it INTRO advances after INTRO_MS. */
    fun skipIntro() {
        if (phase != Phase.INTRO) return
        beginPuzzle()
        render()
    }

    override fun requestHint() {
        if (phase != Phase.PUZZLE || hintsUsed >= 3) return
        hintsUsed++
        hintsTotal++
        speak(puzzles[index].hints[hintsUsed - 1])
        render()
    }

    override fun skipPuzzle() {
        if (phase != Phase.PUZZLE) return
        skips++
        sfx(SfxKind.UNLOCK)
        next()
        render()
    }

    override fun backToTitle() {
        phase = Phase.TITLE
        pausedFrom = null
        doorFrom = null
        startVs = null
        readyCard = false
        episode = null
        puzzles = emptyList()
        run = null
        log.clear()
        emit(GameEvent.AmbientRestore)
        render()
    }

    private fun beginPuzzle() {
        val p = puzzles[index]
        phase = Phase.PUZZLE
        phaseAt = elapsedMs
        hintsUsed = 0
        story = p.story
        run = PuzzleRun(p.spec, vs)
        bonusUntil = p.timedSec?.let { elapsedMs + it * 1000L }
        ambient(p.color, 60)
        val timer = p.timedSec?.let { " 번개 금고예요! ${it}초 안에 풀면 번개 배지를 받아요." }.orEmpty()
        val question = (p.spec as? PuzzleSpec.Riddle)?.let { "${it.question} " }.orEmpty()
        val wiggle = if (run?.needsWiggle == true) " $WIGGLE" else ""
        speak("${p.story} $question${p.prompt}.$wiggle$timer")
    }

    private fun solved() {
        val p = puzzles[index]
        phase = Phase.SOLVED
        phaseAt = elapsedMs
        story = p.reward
        run = null
        solvedKinds += p.spec.kind()
        val fast = bonusUntil?.let { elapsedMs <= it } == true
        bonusUntil = null
        if (fast) { lightning++; solvedKinds += MissionKind.LIGHTNING }
        sfx(SfxKind.CORRECT)
        ambient(GREEN, 90)
        speak(if (fast) "번개 배지! ${p.reward}" else p.reward)
    }

    /** Wiper wear cap: move on without more wipes. Counts as a skip, no badge. */
    private fun tired() {
        skips++
        phase = Phase.SOLVED
        phaseAt = elapsedMs
        story = "와이퍼가 쉬어야 해요. 이 금고는 열어 둘게요."
        run = null
        bonusUntil = null
        sfx(SfxKind.UNLOCK)
        speak(story)
    }

    private fun next() {
        index++
        if (index < puzzles.size) return beginPuzzle()
        run = null
        bonusUntil = null
        if (chargeDone() || chargeStopped()) return finish()
        score(0)
        phase = Phase.ENDING
        waitingForCharge = true
        endingText = "모든 퍼즐을 풀었어요! 충전이 끝나면 엔딩이 열려요."
        ambient(WAIT_BLUE, 40)
        speak(endingText)
    }

    private fun chargeStopped() = !vs.charging && replugBy.let { it == null || (lastNow ?: 0) >= it }

    /**
     * Charge reached the target: real ending, or bonus ending with the rest listed.
     * Charger came off before the target (only reachable with every puzzle solved): "charge stopped" ending.
     */
    private fun finish() {
        val ep = episode ?: return
        val left = puzzles.drop(index).filterIndexed { i, _ -> !(i == 0 && phase == Phase.SOLVED) }
        val target = vs.chargeTargetPercent
        val soc = vs.socPercent.toInt()
        val solvedLine = "퍼즐 ${puzzles.size}개 중 ${puzzles.size - left.size}개 완료"
        // 원래대로 출발: name only what differs from the start. The app never moves anything back itself.
        val diff = restoreLeft().orEmpty()
        restoreDone = diff.isEmpty()
        val tidy = if (diff.isEmpty()) "" else " 마지막 미션, 원래대로 출발! 시작 때와 달라진 곳을 돌려 놓아요. ${diff.joinToString(", ")}."
        val km = kmLine().let { if (it.isEmpty()) "" else " $it" }
        val n = episodes.indexOf(ep)
        val finished = left.isEmpty() // 충전 1회 = 1화: the next 화 opens only when this one was finished
        if (finished && n >= 0) nextIndex = (n + 1) % episodes.size
        val teaser = if (finished && ep.teaser.isNotEmpty()) " ${ep.teaser}" else ""
        var spoken: String? = null
        endingText = if (!chargeDone()) {
            val bye = teaser.ifEmpty { " 다음 충전 때 또 만나요." }
            spoken = "충전이 멈췄어요. 지금 배터리는 ${soc}퍼센트예요.$km $solvedLine! ${ep.outro}$bye"
            "충전이 멈췄어요. 지금 배터리 ${soc}%.$km $solvedLine! ${ep.outro}$bye"
        } else if (left.isEmpty()) {
            "${target}% 충전 완료!$km ${ep.outro} 엔딩 크레딧과 함께 출발하세요.$teaser"
        } else {
            "보너스 엔딩! 충전이 먼저 ${target}%에 도착했어요.$km 남은 퍼즐 ${left.size}개: " +
                left.joinToString(", ") { it.title } + ". 다음 충전 때 다시 만나요."
        }
        phase = Phase.ENDING
        waitingForCharge = false
        run = null
        bonusUntil = null
        score(left.size)
        sfx(SfxKind.ENDING)
        ambient(GOLD, 100)
        speak((spoken ?: endingText).replace("km", "킬로미터") + tidy)
    }

    /** Parts that differ from the episode start; only parts read at the start are compared. null = no episode. */
    private fun restoreLeft(): List<String>? {
        val s0 = startVs ?: return null
        val out = mutableListOf<String>()
        fun moved(a: Int, b: Int) = kotlin.math.abs(a - b) > WINDOW_SLACK
        if (VehicleInput.WINDOW in supported && moved(vs.windowDriverPercent, s0.windowDriverPercent)) out += "운전석 창문"
        for (w in CarWindow.entries) {
            val a = s0.windows[w] ?: continue // unknown at the start: not compared
            if (moved(vs.windows[w] ?: a, a)) out += WINDOW_NAMES.getValue(w)
        }
        if (VehicleInput.SEAT_HEAT in supported && vs.seatHeatDriverLevel != s0.seatHeatDriverLevel) out += "시트 열선"
        if (VehicleInput.STEERING_HEAT in supported && vs.steeringHeatLevel != s0.steeringHeatLevel) out += "핸들 열선"
        if (VehicleInput.READING_LIGHT in supported && vs.readingLightOn != s0.readingLightOn) out += "독서등"
        if (VehicleInput.WIPER in supported && vs.wiperOn != s0.wiperOn) out += "와이퍼"
        return out
    }

    /** Unsolved puzzles at the ending count as skips. */
    private fun score(unsolved: Int) {
        stars = Companion.stars(hintsTotal, skips + unsolved, puzzles.size)
        summary = "힌트 ${hintsTotal}번, 건너뛰기 ${skips + unsolved}번, 번개 ${lightning}개"
    }

    private fun render() {
        val ep = episode ?: if (phase == Phase.MATCHING) recommend() else null
        val p = puzzles.getOrNull(index)
        _ui.value = GameUiState(
            phase = phase,
            episodeTitle = ep?.title.orEmpty(),
            chapterLabel = if (p != null) "${index + 1} / ${puzzles.size}" else "",
            puzzleIndex = index,
            puzzleCount = puzzles.size,
            puzzleTitle = p?.title.orEmpty(),
            story = if (episode == null) "" else if (phase == Phase.PUZZLE && run?.needsWiggle == true) WIGGLE else story,
            prompt = p?.prompt.orEmpty(),
            visual = run?.visual(vs, lastNow ?: 0),
            requiredInput = run?.input ?: p?.spec?.input,
            hintText = p?.hints?.getOrNull(hintsUsed - 1),
            hintsUsed = hintsUsed,
            socPercent = vs.socPercent,
            targetPercent = vs.chargeTargetPercent,
            chargeEtaMinutes = eta(),
            episodeMinutes = ep?.minutes ?: 0,
            elapsedSec = (elapsedMs / 1000).toInt(),
            endingText = endingText,
            waitingForCharge = phase == Phase.ENDING && waitingForCharge,
            episodeId = ep?.id.orEmpty(),
            daily = episode != null && daily,
            missionKind = p?.spec?.kind(),
            bonusSecTotal = p?.timedSec?.takeIf { bonusUntil != null && phase == Phase.PUZZLE },
            bonusSecLeft = bonusUntil?.takeIf { phase == Phase.PUZZLE }?.let { ((it - elapsedMs + 999) / 1000).coerceAtLeast(0).toInt() },
            lightning = lightning,
            solvedKinds = solvedKinds.toSet(),
            stars = if (phase == Phase.ENDING) stars else 0,
            summary = if (phase == Phase.ENDING) summary else "",
            episodeNumber = ep?.let { episodes.indexOf(it) + 1 } ?: 0,
            recap = if (phase == Phase.INTRO) recap else "",
            readyEpisode = if (phase == Phase.TITLE && readyCard && episodes.isNotEmpty()) nextIndex + 1 else 0,
            doorAsk = phase == Phase.PAUSED_DOOR && doorAsk,
            restoreLeft = if (phase == Phase.ENDING && !waitingForCharge) restoreLeft() else null,
            parkFork = phase == Phase.TITLE && forkArmed && memo == null && vs.chargePortOpen != true && !readyCard,
            parkAnchor = memo?.anchor?.label().orEmpty(),
            returnCard = phase == Phase.TITLE && returnCard && memo != null,
            returnReveal = phase == Phase.TITLE && returnCard && reveal && memo != null,
            awayLine = memo?.takeIf { phase == Phase.TITLE && returnCard && reveal }?.let(::awayLine).orEmpty(),
        )
    }

    companion object {
        /** 3 = no hint and no skip, 2 = no skip and at most one hint per puzzle on average, else 1. */
        fun stars(hints: Int, skips: Int, puzzles: Int): Int = when {
            skips == 0 && hints == 0 -> 3
            skips == 0 && hints <= puzzles -> 2
            else -> 1
        }

        const val WIGGLE = "지금은 이미 맞춰져 있어요. 한 번 움직였다가 다시 맞춰 보세요."
        const val INTRO_MS = 5_000L
        const val SOLVED_MS = 3_000L
        const val REPLUG_MS = 60_000L
        const val RECAP_MS = 4_000L // the "지난 화 한 줄" needs a few more seconds before the first puzzle
        const val RESUME_ASK = "이어서 할까요?"
        const val READY_TO_GO = "출발 준비 완료!"
        const val RETURN_ASK = "다시 탔어요. 우리 차 주차 암호, 누가 기억해요?"
        private const val WINDOW_SLACK = 5 // percent; raw window steps can be coarse
        private val WINDOW_NAMES = mapOf(CarWindow.ROW_1_RIGHT to "조수석 창문", CarWindow.ROW_2_LEFT to "2열 왼쪽 창문", CarWindow.ROW_2_RIGHT to "2열 오른쪽 창문")
        private const val GREEN = 0x3CD070
        private const val GOLD = 0xFFC845
        private const val WAIT_BLUE = 0x4A7BD0
    }
}
