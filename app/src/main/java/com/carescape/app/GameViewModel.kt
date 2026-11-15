package com.carescape.app

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.carescape.core.GameEvent
import com.carescape.core.Gear
import com.carescape.core.GameUiState
import com.carescape.core.MissionKind
import com.carescape.core.Phase
import com.carescape.core.SfxKind
import com.carescape.core.SimVehicleGateway
import com.carescape.core.VehicleGateway
import com.carescape.core.VehicleState
import com.carescape.game.Earned
import com.carescape.game.EscapeGame
import com.carescape.game.Item
import com.carescape.game.Look
import com.carescape.game.ParkMemo
import com.carescape.game.TIMELAPSE
import com.carescape.game.Theme
import com.carescape.game.gated
import com.carescape.game.newlyOpened
import com.carescape.game.simCharge
import com.carescape.ui.BestRecord
import com.carescape.ui.ChargeLog
import com.carescape.ui.Progress
import com.carescape.ui.earned
import com.carescape.vehicle.DriveGuard
import com.carescape.vehicle.PleosVehicleGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/** Owns gateway, engine, DriveGuard and sound. Survives rotation and window resizes. */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModel(app: Application) : AndroidViewModel(app) {
    val game = EscapeGame()
    private val _gateway = MutableStateFlow<VehicleGateway>(if (BuildConfig.DEMO) SimVehicleGateway() else PleosVehicleGateway(app))
    val gateway: StateFlow<VehicleGateway> = _gateway
    val demo = BuildConfig.DEMO // release never touches the simulator: DriveGuard must only ever read the real car
    val timelapse = MutableStateFlow(false)

    val vehicle: StateFlow<VehicleState> =
        _gateway.flatMapLatest { it.state }.stateIn(viewModelScope, SharingStarted.Eagerly, _gateway.value.state.value)
    private val guard = DriveGuard(app, vehicle, viewModelScope)
    val blocked: StateFlow<Boolean> = guard.blocked

    private var tts: TextToSpeech? = null
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    private var loop: Job? = null

    // Best stars/time per episode and collected badges. Game module stays pure; only the app remembers.
    private val prefs = app.getSharedPreferences("progress", Context.MODE_PRIVATE)
    val progress = MutableStateFlow(loadProgress())
    private var recorded = false // this ending already saved
    private var started: Pair<Int, Float?>? = null // SoC and range when this episode began, for the 일지 row
    private var lastGear: Gear? = null // raw (not gated) gear before the latest change; outlives onStop/onStart
    private var savedPark: ParkMemo? = null // what prefs "park" holds now

    // 마이 차고. earned follows the saved progress; newOpened = what the play that just ended unlocked.
    private val _look = MutableStateFlow(Look.decode(prefs.getString("look", null)))
    val look: StateFlow<Look> = _look
    val earned: StateFlow<Earned> = progress.map(::earnedOf).stateIn(viewModelScope, SharingStarted.Eagerly, earnedOf(progress.value))
    val newOpened = MutableStateFlow<List<Item>>(emptyList())
    private var shownMode = vehicle.value.driveMode // last mode read while parked: the skin never changes under the drive cover
    /** Skin for the whole app: the drive mode's pick while 모드 따라가기 is on and the mode is known, else the fixed one. */
    val theme: StateFlow<Theme> = combine(_look, vehicle, blocked) { l, v, b ->
        if (!b) shownMode = v.driveMode
        l.shownTheme(shownMode)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _look.value.shownTheme(shownMode))
    private val _moodOn = MutableStateFlow(false)
    val moodOn: StateFlow<Boolean> = _moodOn // 무드등 preview lit now; the garage shows this, not its own guess

    init {
        game.nextEpisodeIndex = prefs.getInt("next_episode", 0)
        // 출발 금고 kept across a restart: a saved 암호 alone means "came back" (the engine raises the card once parked).
        savedPark = prefs.getString("park", null)?.let(ParkMemo::decode)
        game.parkMemo = savedPark
        tts = TextToSpeech(app) { ok ->
            if (ok == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN else Log.w(TAG, "TTS init failed: $ok")
        }
        viewModelScope.launch { _gateway.flatMapLatest { it.supportedInputs }.collect { game.setSupportedInputs(it) } }
        viewModelScope.launch { game.events.collect(::onEvent) }
        viewModelScope.launch { game.ui.collect(::record) }
        // Driving: silence the game master right away; the engine pauses and restores ambient on its next step.
        viewModelScope.launch { blocked.collect { if (it) { tts?.stop(); endMoodPreview(); step() } } }
        // Raw gear, not the gated one: while driving the engine only sees UNKNOWN, so it cannot tell D from a glitch.
        // UNKNOWN and N are skipped (start up, passing through), so D -> (app restart) -> P still counts as parked after a drive.
        viewModelScope.launch {
            vehicle.map { it.gear }.distinctUntilChanged().collect { g ->
                if (BuildConfig.DEBUG) Log.d(TAG, "park gear $g after $lastGear")
                when (g) {
                    Gear.DRIVE, Gear.REVERSE -> { game.onDriveAway(); lastGear = g; savePark() }
                    Gear.PARK -> { if (lastGear == Gear.DRIVE || lastGear == Gear.REVERSE) game.onParkedAfterDrive(); lastGear = g }
                    Gear.NEUTRAL, Gear.UNKNOWN -> Unit
                }
            }
        }
        // The OS covers the app in D and onStop stops the gateway, so the D reading itself can be missed.
        // The UX restriction listener lives with the ViewModel and still hears it: count it as driving off.
        viewModelScope.launch {
            guard.restricted.collect { r ->
                if (BuildConfig.DEBUG) Log.d(TAG, "park uxRestricted $r")
                if (r) { game.onDriveAway(); lastGear = Gear.DRIVE; savePark() }
            }
        }
    }

    private fun onEvent(e: GameEvent) {
        when (e) {
            is GameEvent.Speak -> if (!blocked.value) tts?.speak(e.text, TextToSpeech.QUEUE_FLUSH, null, "gm")
            is GameEvent.Sfx -> tone?.startTone(TONES.getValue(e.kind).first, TONES.getValue(e.kind).second)
            is GameEvent.Ambient -> _gateway.value.setAmbient(e.color, e.brightness)
            GameEvent.AmbientRestore -> _gateway.value.restoreAmbient()
            GameEvent.Silence -> tts?.stop()
        }
    }

    private fun loadProgress(): Progress {
        val best = prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith("best_")) return@mapNotNull null
            val (st, sec) = (v as? String)?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return@mapNotNull null
            k.removePrefix("best_") to BestRecord(st, sec)
        }.toMap()
        val badges = prefs.getStringSet("badges", emptySet()).orEmpty().mapNotNull { n -> MissionKind.entries.firstOrNull { it.name == n } }.toSet()
        val log = prefs.getString("log", "").orEmpty().lines().mapNotNull(ChargeLog::decode)
        return Progress(best, badges, log = log)
    }

    /** 마이 차고 pick (paint, wheel, stickers, theme, mode mapping, 모드 따라가기). Saved at once. */
    fun setLook(l: Look) {
        _look.value = l
        prefs.edit().putString("look", l.encode()).apply()
    }

    /** 무드등 미리 켜 보기: parked only (P, not blocked), same path as the puzzles. Returns false when not allowed now. */
    fun previewMood(rgb: Int): Boolean {
        if (blocked.value || vehicle.value.gear != Gear.PARK) return false
        _moodOn.value = true
        _gateway.value.setAmbient(rgb, MOOD_BRIGHTNESS)
        return true
    }

    /** Leaving the garage (or driving off): the car's own ambient comes back. No-op when nothing was previewed. */
    fun endMoodPreview() {
        if (!_moodOn.value) return
        _moodOn.value = false
        _gateway.value.restoreAmbient()
    }

    /** 가족 충전 일지 삭제 (the title's button). Records and badges stay. */
    fun clearLog() {
        prefs.edit().remove("log").apply()
        progress.value = progress.value.copy(log = emptyList())
    }

    /** Final ending (not the waiting room): save once, mark what is new for the ending screen. */
    private fun record(ui: GameUiState) {
        // Reset only for a new play: a drive pause on the ending screen must not re-record and lose the NEW marks.
        if (ui.phase == Phase.INTRO || ui.phase == Phase.TITLE) recorded = false
        if (ui.phase == Phase.TITLE) started = null
        if (ui.phase == Phase.INTRO && started == null) started = ui.socPercent.toInt() to vehicle.value.rangeMeters
        if (ui.phase != Phase.ENDING || ui.waitingForCharge) return
        if (recorded || ui.episodeId.isEmpty() || ui.stars == 0) return
        recorded = true
        val p = progress.value
        val old = p.best[ui.episodeId]
        val mine = BestRecord(ui.stars, ui.elapsedSec)
        val newBest = old == null || mine.stars > old.stars || (mine.stars == old.stars && mine.sec < old.sec)
        val best = if (newBest) p.best + (ui.episodeId to mine) else p.best
        val newBadges = ui.solvedKinds - p.badges
        val badges = p.badges + ui.solvedKinds
        // One 일지 row per ending: date, N화, stars, SoC start -> end, range gained (only when both ends were read).
        val (soc0, range0) = started ?: (ui.socPercent.toInt() to null)
        val range1 = vehicle.value.rangeMeters
        val km = if (range0 != null && range1 != null) ((range1 - range0) / 1000f).toInt().coerceAtLeast(0) else null
        val today = LocalDate.now().let { "${it.monthValue}/${it.dayOfMonth}" }
        // ponytail: whole log in one prefs string, ~30 bytes a row; move to a file if families ever reach thousands of charges
        val log = p.log + ChargeLog(today, ui.episodeNumber, ui.stars, soc0, ui.socPercent.toInt(), km)
        prefs.edit().apply {
            if (newBest) putString("best_${ui.episodeId}", "${mine.stars},${mine.sec}")
            putStringSet("badges", badges.map { it.name }.toSet())
            putInt("next_episode", game.nextEpisodeIndex)
            putString("log", log.joinToString("\n") { it.encode() })
        }.apply()
        newOpened.value = newlyOpened(earnedOf(p), earnedOf(Progress(best, badges)))
        progress.value = Progress(best, badges, newBadges, newBest, log)
    }

    private fun step() {
        game.onVehicle(gated(_gateway.value.state.value, blocked.value), SystemClock.elapsedRealtime())
        savePark()
    }

    /** prefs "park" follows the engine's memo: written when it changes (save, lock), removed when it is gone (drive). */
    private fun savePark() {
        val m = game.parkMemo
        if (m == savedPark) return
        savedPark = m
        if (BuildConfig.DEBUG) Log.d(TAG, "park memo ${m?.encode()}") // debug and car builds only: a parking spot is personal
        prefs.edit().apply { if (m == null) remove("park") else putString("park", m.encode()) }.apply()
    }

    /** Sim only: the charger. Runs on the timed tick, never from the state collector (it would retrigger itself). */
    private var lastCharge = 0L
    private fun charge() {
        val now = SystemClock.elapsedRealtime()
        val sim = _gateway.value as? SimVehicleGateway
        if (sim != null && lastCharge > 0) sim.update { simCharge(it, (now - lastCharge).coerceAtMost(1_000), if (timelapse.value) TIMELAPSE else 1f) }
        lastCharge = now
    }

    /** Activity onStart. */
    fun resume() {
        _gateway.value.start()
        if (loop?.isActive == true) return
        lastCharge = 0
        loop = viewModelScope.launch {
            launch { vehicle.collect { step() } } // react to inputs at once (Morse timing)
            while (isActive) { charge(); step(); delay(TICK_MS) }
        }
    }

    /** Activity onStop: no ticking, no sound, car lights back to normal. */
    fun pause() {
        savePark()
        _moodOn.value = false // restoreAmbient below covers it
        loop?.cancel()
        loop = null
        tts?.stop()
        _gateway.value.restoreAmbient()
        _gateway.value.stop()
    }

    /** CAR_SPEED / CAR_ENERGY just granted: CarPropertyManager callbacks registered before the grant were refused, so rebind. */
    fun onCarPermissionsGranted() {
        val g = _gateway.value
        if (g !is PleosVehicleGateway || loop == null) return
        g.stop()
        g.start()
    }

    override fun onCleared() {
        pause()
        guard.release()
        tts?.shutdown()
        tone?.release()
    }

    private fun earnedOf(p: Progress) = p.earned // same sums the garage screen shows

    companion object {
        private const val TAG = "CarEscape"
        private const val MOOD_BRIGHTNESS = 70
        const val TICK_MS = 200L // engine needs ~200 ms steps for holds and Morse timing
        private val TONES = mapOf(
            SfxKind.TICK to (ToneGenerator.TONE_PROP_BEEP to 60),
            SfxKind.CORRECT to (ToneGenerator.TONE_PROP_ACK to 220),
            SfxKind.WRONG to (ToneGenerator.TONE_PROP_NACK to 260),
            SfxKind.UNLOCK to (ToneGenerator.TONE_PROP_BEEP2 to 200),
            SfxKind.ENDING to (ToneGenerator.TONE_PROP_PROMPT to 600),
        )
    }
}
