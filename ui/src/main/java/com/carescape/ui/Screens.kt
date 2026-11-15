@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.carescape.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.foundation.layout.FlowRow
import com.carescape.core.GameUiState
import com.carescape.core.MissionKind
import com.carescape.core.ParkingAnchor
import com.carescape.core.Phase
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import com.carescape.game.Item
import com.carescape.game.Look

/** Everything the screens can ask the game to do. Map onto EscapeGameController in :app. */
data class GameActions(
    val onStart: () -> Unit = {}, // TITLE -> startMatching()
    val onPickEpisode: (String) -> Unit = {}, // MATCHING -> startEpisode(id)
    val onIntroContinue: () -> Unit = {}, // INTRO -> first puzzle
    val onHint: () -> Unit = {}, // requestHint()
    val onSkip: () -> Unit = {}, // skipPuzzle()
    val onBackToTitle: () -> Unit = {}, // backToTitle()
    val onDaily: (String) -> Unit = {}, // MATCHING in 오늘의 금고 mode -> startDaily(id, epochDay)
    val onResume: () -> Unit = {}, // PAUSED_DOOR, doors closed -> resumeAfterDoor()
    val onReadyStart: () -> Unit = {}, // TITLE "N화가 준비됐어요" card -> startNext()
    val onReadyDismiss: () -> Unit = {}, // card closed with one tap -> dismissReady()
    val onClearLog: () -> Unit = {}, // 가족 충전 일지 삭제 (app)
    // 출발 금고
    val onParkStay: () -> Unit = {}, // 갈림길 "차에서 기다려요" -> stayInCar()
    val onParkSave: (ParkingAnchor) -> Unit = {}, // "맞아요" -> saveAnchor()
    val onParkSkip: () -> Unit = {}, // 건너뛰기 -> skipAnchor()
    val onReturnOpen: () -> Unit = {}, // title chip -> openReturn()
    val onReturnReveal: () -> Unit = {}, // 정답 보기 -> revealReturn()
    val onReturnClose: () -> Unit = {}, // 출발 준비 -> closeReturn()
    // 마이 차고
    val onLook: (Look) -> Unit = {}, // any garage pick -> setLook()
    val onMoodPreview: (Int) -> Boolean = { false }, // 무드등 미리 켜 보기 -> previewMood(), false = not parked
    val onMoodEnd: () -> Unit = {}, // garage left -> endMoodPreview()
)

/** Best result of one episode: more stars wins, then less time. */
data class BestRecord(val stars: Int, val sec: Int)

/** Saved by :app. newBadges / newBest describe the play that just ended. */
data class Progress(
    val best: Map<String, BestRecord> = emptyMap(),
    val badges: Set<MissionKind> = emptySet(),
    val newBadges: Set<MissionKind> = emptySet(),
    val newBest: Boolean = false,
    val log: List<ChargeLog> = emptyList(), // oldest first
)

/** One row of the 가족 충전 일지, kept on the device only. km = range gained this charge, null = unknown. */
data class ChargeLog(val date: String, val episode: Int, val stars: Int, val socFrom: Int, val socTo: Int, val km: Int? = null) {
    fun encode() = listOf(date, episode, stars, socFrom, socTo, km ?: "").joinToString("|")
    val line get() = "$date · ${episode}화 · ${"★".repeat(stars)} · $socFrom→$socTo%" + (km?.let { " · +${it}km" } ?: "")

    companion object {
        fun decode(s: String): ChargeLog? {
            val p = s.split("|").takeIf { it.size == 6 } ?: return null
            val n = p.subList(1, 5).map { it.toIntOrNull() ?: return null }
            return ChargeLog(p[0], n[0], n[1], n[2], n[3], p[5].toIntOrNull())
        }
    }
}

/** "우리 가족의 N번째 충전 모험" (1 reads 첫 번째). */
fun adventure(n: Int) = "우리 가족의 ${if (n == 1) "첫 번째" else "${n}번째"} 충전 모험"

private fun clock(sec: Int) = "${sec / 60}분 ${sec % 60}초"

/** Episode card shown on the matching screen. ids must match the :game episode ids. */
data class EpisodeOption(
    val id: String, val title: String, val minutes: Int, val blurb: String, val puzzles: Int,
    val inputs: List<VehicleInput> = emptyList(), // car parts the episode uses, shown as chips
)

/** Previews and snapshots only; :app builds the real list from the game's episodes. */
val DefaultEpisodes = listOf(
    EpisodeOption("map-15", "꼬마 탐정과 충전소 지도", 15, "충전소에 보물 지도가 숨어 있대요. 우리 차가 단서를 쥐고 있어요.", 4,
        listOf(VehicleInput.WINDOW, VehicleInput.STEERING_HEAT, VehicleInput.READING_LIGHT, VehicleInput.SEAT_HEAT)),
    EpisodeOption("family-20", "아이오닉 9 우주선 출발 준비", 20, "충전하는 동안 온 가족이 힘을 모아 출발 준비를 해요.", 5,
        listOf(VehicleInput.SEATBELT, VehicleInput.WINDOW, VehicleInput.PASSENGER_WINDOW, VehicleInput.TURN_SIGNAL, VehicleInput.REAR_WINDOW)),
    EpisodeOption("letter-30", "별빛 우체부의 잃어버린 편지", 30, "별빛 우체부가 편지 여섯 장을 차 안에 떨어뜨렸어요.", 6,
        listOf(VehicleInput.SEAT_HEAT, VehicleInput.WINDOW, VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL, VehicleInput.STEERING_HEAT, VehicleInput.WIPER)),
    EpisodeOption("moon-25", "별빛 우체부와 달님의 답장", 25, "지난번 편지를 받은 달님이 답장을 보냈대요.", 5,
        listOf(VehicleInput.STEERING_HEAT, VehicleInput.REAR_WINDOW, VehicleInput.READING_LIGHT, VehicleInput.TURN_SIGNAL, VehicleInput.SEAT_HEAT)),
)

/**
 * Root. With the demo panel on, the game gets its own (smaller) window next to the panel instead of being
 * covered: side by side in landscape, a bottom sheet in portrait. Scaled re-measures the game part.
 */
@Composable
fun CarEscapeApp(
    ui: GameUiState,
    vehicle: VehicleState,
    actions: GameActions,
    demo: DemoControls,
    showDemo: Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier,
    episodes: List<EpisodeOption> = DefaultEpisodes,
    progress: Progress = Progress(),
    daily: Boolean = false, // initial 오늘의 금고 mode (snapshots); the title buttons set it
    parkDraft: ParkDraft = ParkDraft(), // snapshots only: 갈림길 card mid entry
    look: Look = Look(),
    newOpened: List<Item> = emptyList(), // garage items the play that just ended unlocked
    garageTab: GarageTab? = null, // snapshots only: start inside 마이 차고 on this tab
    moodOn: Boolean = false, // 무드등 preview is on right now (the app's state, not the button's)
) = BoxWithConstraints(modifier.fillMaxSize().nightBackground()) {
    var open by rememberSaveable { mutableStateOf(demo.initiallyOpen) }
    var dailyMode by rememberSaveable { mutableStateOf(daily) }
    var garage by rememberSaveable { mutableStateOf(garageTab != null) }
    // 오늘의 금고 is picked on the title, so the episode cards know which start to call.
    val acts = actions.copy(
        onStart = { dailyMode = false; actions.onStart() },
        onPickEpisode = { id -> if (dailyMode) actions.onDaily(id) else actions.onPickEpisode(id) },
    )
    val today = { dailyMode = true; actions.onStart() }
    val game = @Composable { m: Modifier ->
        Box(m) {
            Scaled {
                // 마이 차고 opens from the title only, so no puzzle's ambient light can meet the garage preview.
                if (garage && ui.phase == Phase.TITLE && !blocked) GarageScreen(look, progress.earned, vehicle.driveMode, acts, { garage = false }, garageTab ?: GarageTab.PAINT, moodOn)
                else Game(ui, vehicle, acts, today, episodes, blocked, progress, dailyMode, parkDraft, look, newOpened) { garage = true }
            }
        }
    }
    val land = maxWidth > maxHeight
    val sideW = min(360.dp, maxWidth * 0.3f)
    val sheetH = maxHeight * 0.4f
    when {
        !showDemo -> game(Modifier.fillMaxSize())
        // Demo panel stays reachable while blocked: in a demo it is the only way to shift back to P.
        land -> Row(Modifier.fillMaxSize()) {
            game(Modifier.weight(1f).fillMaxHeight())
            DemoPanel(vehicle, demo, open, { open = it }, Modifier.fillMaxHeight().width(if (open) sideW else 64.dp))
        }
        else -> Column(Modifier.fillMaxSize()) {
            game(Modifier.weight(1f).fillMaxWidth())
            DemoPanel(vehicle, demo, open, { open = it }, Modifier.fillMaxWidth().height(if (open) sheetH else 64.dp))
        }
    }
}

@Composable
private fun Game(
    ui: GameUiState, vehicle: VehicleState, actions: GameActions, onToday: () -> Unit, episodes: List<EpisodeOption>,
    blocked: Boolean, progress: Progress, daily: Boolean, parkDraft: ParkDraft, look: Look, newOpened: List<Item>, onGarage: () -> Unit,
) {
    if (blocked || ui.phase == Phase.PAUSED_DRIVING) return PausedDrivingOverlay()
    when (ui.phase) {
        Phase.TITLE -> TitleScreen(ui, actions.onStart, onToday, episodes, progress, actions, parkDraft, look, onGarage)
        Phase.MATCHING -> MatchingScreen(ui, episodes, actions.onPickEpisode, daily, progress)
        Phase.INTRO -> IntroScreen(ui, actions.onIntroContinue)
        Phase.PUZZLE -> PuzzleScreen(ui, vehicle, actions)
        Phase.SOLVED -> SolvedScreen(ui)
        Phase.ENDING -> EndingScreen(ui, actions.onBackToTitle, progress, look, newOpened)
        Phase.PAUSED_DRIVING -> Unit
        Phase.PAUSED_DOOR -> DoorPauseScreen(ui.doorAsk, actions.onResume)
    }
}

@Composable
fun TitleScreen(
    ui: GameUiState, onStart: () -> Unit, onToday: () -> Unit = {}, episodes: List<EpisodeOption> = DefaultEpisodes, progress: Progress = Progress(),
    actions: GameActions = GameActions(),
    parkDraft: ParkDraft = ParkDraft(), // snapshots start the 갈림길 card mid entry
    look: Look = Look(),
    onGarage: () -> Unit = {},
) {
    val d = LocalDims.current
    var draft by remember(ui.parkFork) { mutableStateOf(parkDraft) }
    if (ui.parkFork && draft.step == ParkStep.ENTRY) return AnchorEntry(draft, { draft = it }, actions.onParkSkip)
    // Landscape with height to spare: the car is the hero under the title. Compact and portrait keep the small car on top.
    val hero = d.land && !d.compact
    val brand = @Composable {
        Column(horizontalAlignment = if (d.land) Alignment.Start else Alignment.CenterHorizontally) {
            // The family's own car (마이 차고) where the logo was; tap it or the button to decorate.
            if (!hero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Portrait: 288 wide = the old logo's 120 high, the column has no height to spare.
                    CarAvatar(look, Modifier.width(d.dp(if (d.compact) 300 else 288)).clickable(role = Role.Button, onClick = onGarage))
                    Spacer(Modifier.width(d.dp(24)))
                    BigButton("꾸미기", onGarage, primary = false)
                }
                Spacer(Modifier.height(d.dp(if (d.compact) 16 else 36)))
            }
            Txt("카 이스케이프", 96f, weight = FontWeight.Bold, maxLines = 1)
            Txt("아이오닉 9 가족 방탈출", 32f, color = C.Gold, weight = FontWeight.SemiBold)
            // Ambient-light strip: the same glow the cabin gets during the game.
            Box(Modifier.padding(vertical = d.dp(24)).width(d.dp(360)).height(d.dp(6)).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color.Transparent, C.Gold, C.Amber, Color(0xFF9B6BFF), Color.Transparent))))
            Txt("충전하는 동안, 우리 차가 방탈출 게임이 됩니다", 32f, color = C.Sub, align = if (d.land) null else TextAlign.Center)
            if (hero) {
                Spacer(Modifier.height(d.dp(40)))
                CarAvatar(look, Modifier.width(d.dp(720)).clickable(role = Role.Button, onClick = onGarage))
                Spacer(Modifier.height(d.dp(28)))
                BigButton("꾸미기", onGarage, primary = false)
            }
        }
    }
    val panel = @Composable { m: Modifier ->
        Panel(m) {
            Column(Modifier.padding(d.dp(44))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SocRing(ui.socPercent, ui.targetPercent, d.dp(170))
                    Spacer(Modifier.width(d.dp(36)))
                    Column {
                        Txt("현재 ${ui.socPercent.toInt()}% → 목표 ${ui.targetPercent}%", 30f, weight = FontWeight.SemiBold)
                        Spacer(Modifier.height(d.dp(8)))
                        Txt("예상 남은 시간 ${etaText(ui.chargeEtaMinutes)}", 28f, color = C.Sub)
                    }
                }
                Spacer(Modifier.height(d.dp(if (d.compact) 24 else 40)))
                if (ui.parkAnchor.isNotEmpty()) {
                    ParkChip(ui.parkAnchor, actions.onReturnOpen)
                    Spacer(Modifier.height(d.dp(20)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(d.dp(20))) {
                    BigButton("시작", onStart, Modifier.weight(1f))
                    BigButton("오늘의 금고", onToday, Modifier.weight(1f), primary = false)
                }
                val recs = episodes.mapNotNull { e -> progress.best[e.id]?.let { e to it } }
                if (recs.isNotEmpty() || progress.badges.isNotEmpty()) {
                    Spacer(Modifier.height(d.dp(if (d.compact) 16 else 28)))
                    Txt("최고 기록", 24f, color = C.Sub, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(d.dp(8)))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(d.dp(12)), verticalArrangement = Arrangement.spacedBy(d.dp(10))) {
                        recs.forEach { (e, r) -> RecordChip("${e.minutes}분", r) }
                        if (progress.badges.isNotEmpty()) Pill("배지 ${progress.badges.size} / ${MissionKind.entries.size}", C.Amber)
                    }
                }
                Spacer(Modifier.height(d.dp(if (d.compact) 16 else 28)))
                ChargeLogView(progress.log, actions.onClearLog)
            }
        }
    }
    // One card at a time: back in the car > N화 ready > parked fork > the usual panel.
    val right: @Composable (Modifier) -> Unit = when {
        ui.returnCard -> { m -> ReturnCard(ui, actions, m) }
        ui.readyEpisode > 0 -> { m -> ReadyCard(ui, episodes, actions, m) }
        ui.parkFork && draft.step == ParkStep.CONFIRM -> { m -> AnchorConfirm(draft, { draft = it }, actions.onParkSave, m) }
        ui.parkFork -> { m -> ParkForkCard(actions, { draft = draft.copy(step = ParkStep.ENTRY) }, m) }
        else -> panel
    }
    if (d.land) Row(Modifier.fillMaxSize().padding(d.pad), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1.1f)) { brand() }
        Spacer(Modifier.width(d.dp(64)))
        right(Modifier.weight(0.9f))
    } else Column(
        Modifier.fillMaxSize().padding(d.pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        brand()
        Spacer(Modifier.height(d.dp(72)))
        right(Modifier.fillMaxWidth())
    }
}

/** Charge port just opened: one card for the next 화. One tap starts it or closes it; other episodes stay behind 시작. */
@Composable
private fun ReadyCard(ui: GameUiState, episodes: List<EpisodeOption>, actions: GameActions, modifier: Modifier) {
    val d = LocalDims.current
    val n = ui.readyEpisode
    Panel(modifier, highlight = true) {
        Column(Modifier.padding(d.dp(44))) {
            Pill("충전구 열림")
            Spacer(Modifier.height(d.dp(20)))
            Txt("${n}화가 준비됐어요", 60f, weight = FontWeight.Bold, maxLines = 1)
            ui.chargeEtaMinutes?.let {
                Spacer(Modifier.height(d.dp(8)))
                Txt("남은 충전 약 ${it}분", 30f, color = C.Gold, weight = FontWeight.SemiBold, maxLines = 1)
            }
            episodes.getOrNull(n - 1)?.let {
                Spacer(Modifier.height(d.dp(16)))
                Txt(it.title, 34f, color = C.Sub, maxLines = 2)
            }
            Spacer(Modifier.height(d.dp(if (d.compact) 24 else 40)))
            Row(horizontalArrangement = Arrangement.spacedBy(d.dp(20))) {
                BigButton("${n}화 시작", actions.onReadyStart, Modifier.weight(1f))
                BigButton("닫기", actions.onReadyDismiss, Modifier.weight(1f), primary = false)
            }
        }
    }
}

/** 가족 충전 일지: count, the latest rows, and a delete button that asks for a second tap. */
@Composable
private fun ChargeLogView(log: List<ChargeLog>, onClear: () -> Unit) {
    val d = LocalDims.current
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(adventure(log.size + 1), 26f, color = C.Gold, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        if (log.isNotEmpty()) {
            Spacer(Modifier.width(d.dp(16)))
            BigButton(if (confirm) "한 번 더 누르면 지워요" else "일지 지우기", { if (confirm) { confirm = false; onClear() } else confirm = true }, primary = false)
        }
    }
    if (!d.compact) log.takeLast(3).asReversed().forEach {
        Spacer(Modifier.height(d.dp(6)))
        Txt(it.line, 22f, color = C.Sub, maxLines = 1)
    }
}

/** Same rule as EscapeGame.recommend(): the longest episode that fits the ETA, else the shortest. */
private fun recommended(episodes: List<EpisodeOption>, eta: Int?): EpisodeOption? {
    val byLen = episodes.sortedBy { it.minutes }
    return eta?.let { e -> byLen.lastOrNull { it.minutes <= e } } ?: byLen.firstOrNull()
}

@Composable
fun MatchingScreen(
    ui: GameUiState, episodes: List<EpisodeOption>, onPick: (String) -> Unit, daily: Boolean = false, progress: Progress = Progress(),
) {
    val d = LocalDims.current
    val eta = ui.chargeEtaMinutes
    val rec = recommended(episodes, eta)
    Column(Modifier.fillMaxSize().padding(d.pad), verticalArrangement = Arrangement.Center) {
        if (daily) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("오늘의 금고", C.Amber)
                Spacer(Modifier.width(d.dp(16)))
                Txt("오늘은 온 가족이 같은 문제를 풀어요", 26f, color = C.Sub, maxLines = 1)
            }
            Spacer(Modifier.height(d.dp(16)))
        }
        Txt("충전 시간에 맞는 에피소드", 28f, color = C.Gold, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(d.dp(12)))
        Txt("남은 충전 시간 ${etaText(eta)}", 52f, weight = FontWeight.Bold)
        rec?.let { Txt("→ ${it.minutes}분 에피소드를 추천합니다", 40f, color = C.Gold, weight = FontWeight.SemiBold) }
        Spacer(Modifier.height(d.dp(if (d.compact) 24 else 40)))
        val card = @Composable { e: EpisodeOption, m: Modifier ->
            EpisodeCard(e, episodes.indexOf(e) + 1, e == rec, eta != null && e.minutes > eta, progress.best[e.id], m) { onPick(e.id) }
        }
        if (d.land) Row(horizontalArrangement = Arrangement.spacedBy(d.dp(32))) {
            episodes.forEach { card(it, Modifier.weight(1f)) }
        } else Column(verticalArrangement = Arrangement.spacedBy(d.dp(28))) {
            episodes.forEach { card(it, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun EpisodeCard(e: EpisodeOption, n: Int, recommended: Boolean, tooLong: Boolean, best: BestRecord?, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalDims.current
    val m = modifier.alpha(if (tooLong) 0.7f else 1f).clickable(role = Role.Button, onClick = onClick)
    // Portrait: three stacked full cards do not fit the height, so each is one row.
    if (!d.land) return Panel(m, highlight = recommended) {
        Row(Modifier.fillMaxWidth().padding(d.dp(32)), verticalAlignment = Alignment.CenterVertically) {
            Txt("${e.minutes}분", 60f, color = if (recommended) C.Gold else C.Text, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(d.dp(28)))
            Column(Modifier.weight(1f)) {
                Txt("${n}화 · ${e.title}", 34f, weight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(d.dp(8)))
                Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10)), verticalAlignment = Alignment.CenterVertically) {
                    if (recommended) Pill("추천")
                    if (tooLong) Pill("충전 시간보다 길어요", C.Faint)
                    else best?.let { RecordChip("최고", it) } ?: Txt("퍼즐 ${e.puzzles}개", 26f, color = C.Faint, maxLines = 1)
                }
            }
            Txt("›", 48f, color = if (recommended) C.Gold else C.Text, weight = FontWeight.Bold)
        }
    }
    Panel(m, highlight = recommended) {
        Column(Modifier.padding(d.dp(44))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("${e.minutes}분", 72f, color = if (recommended) C.Gold else C.Text, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(d.dp(20)))
                if (recommended) Pill("추천")
                if (tooLong) Pill("충전 시간보다 길어요", C.Faint)
            }
            Spacer(Modifier.height(d.dp(12)))
            best?.let { RecordChip("최고", it); Spacer(Modifier.height(d.dp(12))) }
            Txt("${n}화", 28f, color = C.Gold, weight = FontWeight.SemiBold, maxLines = 1)
            Txt(e.title, 40f, weight = FontWeight.SemiBold, maxLines = 2)
            if (!d.compact) {
                Spacer(Modifier.height(d.dp(8)))
                Txt(e.blurb, 28f, color = C.Sub, maxLines = 2)
            }
            if (e.inputs.isNotEmpty() && d.land && !d.compact) {
                Spacer(Modifier.height(d.dp(32)))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(d.dp(14)), verticalArrangement = Arrangement.spacedBy(d.dp(14))) {
                    e.inputs.forEach { i ->
                        Box(Modifier.clip(RoundedCornerShape(d.dp(16))).background(C.Surface2).padding(d.dp(12))) { InputIcon(i, d.dp(44), C.Sub) }
                    }
                }
            }
            Spacer(Modifier.height(d.dp(if (d.land && !d.compact) 48 else 24)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("퍼즐 ${e.puzzles}개", 26f, color = C.Faint, modifier = Modifier.weight(1f))
                Txt("선택하기 ›", 30f, color = if (recommended) C.Gold else C.Text, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun IntroScreen(ui: GameUiState, onContinue: () -> Unit) {
    val d = LocalDims.current
    Column(
        Modifier.fillMaxSize().padding(d.pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = d.dp(1100)), horizontalAlignment = Alignment.CenterHorizontally) {
            // 지난 화 한 줄 -> 오늘 화 제목
            if (ui.recap.isNotBlank()) {
                Txt("지난 화", 24f, color = C.Faint, weight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(d.dp(8)))
                Txt(ui.recap, 32f, color = C.Sub, align = TextAlign.Center)
                Spacer(Modifier.height(d.dp(28)))
            }
            Pill(ui.episodeTitle.ifBlank { "에피소드" }.let { if (ui.episodeNumber > 0) "${ui.episodeNumber}화 · $it" else it })
            Spacer(Modifier.height(d.dp(40)))
            Txt(ui.story, 52f, weight = FontWeight.SemiBold, align = TextAlign.Center)
            Spacer(Modifier.height(d.dp(24)))
            if (ui.puzzleCount > 0) Txt("잠금 ${ui.puzzleCount}개를 모두 풀면 탈출합니다", 30f, color = C.Sub, align = TextAlign.Center)
            Spacer(Modifier.height(d.dp(64)))
            BigButton("시작", onContinue, Modifier.widthIn(min = d.dp(420)))
        }
    }
}

@Composable
fun PuzzleScreen(ui: GameUiState, vehicle: VehicleState, actions: GameActions) {
    val d = LocalDims.current
    Column(Modifier.fillMaxSize().padding(d.pad)) {
        PuzzleTopBar(ui)
        Spacer(Modifier.height(d.dp(32)))
        if (d.land) Row(Modifier.weight(1f)) {
            PuzzleVisualView(ui.visual, ui.requiredInput, Modifier.weight(1f).fillMaxSize())
            Spacer(Modifier.width(d.dp(56)))
            Instructions(ui, vehicle, actions, Modifier.weight(1f).fillMaxSize())
        } else Column(Modifier.weight(1f)) {
            PuzzleVisualView(ui.visual, ui.requiredInput, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(d.dp(36)))
            Instructions(ui, vehicle, actions, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PuzzleTopBar(ui: GameUiState) {
    val d = LocalDims.current
    val chapter = @Composable {
        Txt(ui.chapterLabel.ifBlank { "${ui.puzzleIndex + 1} / ${ui.puzzleCount}" }, 28f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(d.dp(20)))
        repeat(ui.puzzleCount) { i ->
            Box(Modifier.padding(end = d.dp(10)).size(d.dp(14)).clip(RoundedCornerShape(50)).background(if (i <= ui.puzzleIndex) C.Gold else C.Faint.copy(alpha = 0.5f)))
        }
    }
    val progress = @Composable { m: Modifier ->
        // Compact (short wide window): chapter, dots and title share one line.
        if (d.compact) Row(m, verticalAlignment = Alignment.CenterVertically) {
            chapter()
            Spacer(Modifier.width(d.dp(10)))
            Txt(ui.puzzleTitle, 34f, weight = FontWeight.SemiBold, maxLines = 1)
        } else Column(m) {
            Row(verticalAlignment = Alignment.CenterVertically) { chapter() }
            Txt(ui.puzzleTitle, 34f, weight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    val charge = @Composable { m: Modifier ->
        Column(m) {
            Row {
                Txt("충전 ${ui.socPercent.toInt()}% → ${ui.targetPercent}%", 28f, color = C.Sub, maxLines = 1, modifier = Modifier.weight(1f))
                Txt(ui.chargeEtaMinutes?.let { "약 ${it}분 남음" } ?: "남은 시간 계산 중", 28f, color = C.Sub, maxLines = 1)
            }
            Spacer(Modifier.height(d.dp(10)))
            SocBar(ui.socPercent, ui.targetPercent, Modifier.fillMaxWidth().height(d.dp(14)))
        }
    }
    val ring = @Composable {
        ui.bonusSecLeft?.let { left ->
            val total = ui.bonusSecTotal ?: left
            Row(verticalAlignment = Alignment.CenterVertically) {
                BonusRing(left, total, d.dp(if (d.compact) 64 else 84))
                Spacer(Modifier.width(d.dp(12)))
                Txt(if (left > 0) "번개 금고" else "번개 시간 끝", 24f, color = if (left > 0) C.Amber else C.Faint, weight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
    if (d.land) Row(verticalAlignment = Alignment.CenterVertically) {
        progress(Modifier.weight(1f))
        if (ui.bonusSecLeft != null) { Spacer(Modifier.width(d.dp(24))); ring() }
        Spacer(Modifier.width(d.dp(40)))
        charge(Modifier.width(d.dp(if (d.compact) 760 else 560)))
    } else Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            progress(Modifier.weight(1f))
            ring()
        }
        Spacer(Modifier.height(d.dp(20)))
        charge(Modifier.fillMaxWidth())
    }
}

@Composable
private fun Instructions(ui: GameUiState, vehicle: VehicleState, actions: GameActions, modifier: Modifier) {
    val d = LocalDims.current
    Column(modifier, verticalArrangement = if (d.land) Arrangement.Center else Arrangement.Top) {
        ui.requiredInput?.let {
            // The car itself is the controller: show which part of it, and flash it when the car reports a change.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarMap(it, vehicle, d.dp(200))
                Spacer(Modifier.width(d.dp(24)))
                Column {
                    Txt(it.label, 32f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
                    if (!d.compact) Txt("우리 차의 이 부분을 움직이세요", 24f, color = C.Sub, maxLines = 1)
                }
            }
            Spacer(Modifier.height(d.dp(20)))
        }
        Txt(ui.prompt, 48f, weight = FontWeight.Bold, maxLines = 3)
        ui.hintText?.let {
            Spacer(Modifier.height(d.dp(24)))
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(d.dp(28))) {
                    Txt("힌트", 28f, color = C.Gold, weight = FontWeight.SemiBold)
                    Txt(it, 28f, color = C.Text, maxLines = 3)
                }
            }
        }
        Spacer(Modifier.height(d.dp(40)))
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(24))) {
            BigButton(if (ui.hintsUsed > 0) "힌트 (${ui.hintsUsed})" else "힌트", actions.onHint, Modifier.weight(1f), primary = false)
            BigButton("건너뛰기", actions.onSkip, Modifier.weight(1f), primary = false)
        }
    }
}

/** Climax: opaque, nothing of the puzzle underneath. */
@Composable
fun SolvedScreen(ui: GameUiState) {
    val d = LocalDims.current
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(1200)) }
    Box(Modifier.fillMaxSize().background(C.BgDeep), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r0 = size.minDimension * 0.34f
            drawCircle(Brush.radialGradient(listOf(C.Gold.copy(alpha = 0.18f), C.Gold.copy(alpha = 0.04f)), center, r0), r0)
            drawCircle(C.Gold, r0, style = Stroke(r0 * 0.05f))
            drawCircle(C.Gold.copy(alpha = (1f - p.value) * 0.8f), r0 * (1f + p.value * 1.4f), style = Stroke(r0 * 0.03f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OpenLock(d.dp(96))
            Spacer(Modifier.height(d.dp(16)))
            Txt("잠금 해제", 72f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
            Txt(ui.story.ifBlank { "다음 단서로 넘어갑니다" }, 30f, color = C.Sub, align = TextAlign.Center, maxLines = 3,
                modifier = Modifier.widthIn(max = minOf(d.w, d.h) * 0.5f)) // stays inside the ring
        }
    }
}

/** Padlock with the shackle swung open. */
@Composable
private fun OpenLock(size: androidx.compose.ui.unit.Dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val st = Stroke(w * 0.09f, cap = StrokeCap.Round)
    drawArc(C.Gold, 180f, 180f, false, Offset(w * 0.38f, w * 0.06f), Size(w * 0.44f, w * 0.44f), style = st)
    drawLine(C.Gold, Offset(w * 0.82f, w * 0.28f), Offset(w * 0.82f, w * 0.40f), st.width, StrokeCap.Round)
    drawRoundRect(C.Gold, Offset(w * 0.14f, w * 0.44f), Size(w * 0.62f, w * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f))
    drawCircle(C.BgDeep, w * 0.06f, Offset(w * 0.45f, w * 0.66f))
}

@Composable
fun EndingScreen(ui: GameUiState, onBack: () -> Unit, progress: Progress = Progress(), look: Look = Look(), newOpened: List<Item> = emptyList()) {
    val d = LocalDims.current
    val scored = !ui.waitingForCharge && ui.stars > 0
    val news = !ui.waitingForCharge && (progress.newBest || progress.newBadges.isNotEmpty() || newOpened.isNotEmpty())
    Column(
        Modifier.fillMaxSize().padding(d.pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
      // Scrolls when it does not fit (compact land); the button below stays full size on screen.
      Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        // Connect-WS with 마이 차고 news: that row takes the stars' place (별 N개 is in the stats), so all fits on first view.
        if (scored && !(d.compact && newOpened.isNotEmpty())) {
            Stars(ui.stars, d.dp(if (d.compact) 56 else 84))
            Spacer(Modifier.height(d.dp(if (d.compact) 12 else 20)))
        } else if (!d.compact) {
            LogoMark(d.dp(110))
            Spacer(Modifier.height(d.dp(28)))
        }
        // All puzzles done but the charge is not: a waiting room, not the ending.
        if (ui.waitingForCharge) Pill("퍼즐 완료 · 충전 중", C.Amber)
        else Row(horizontalArrangement = Arrangement.spacedBy(d.dp(12))) {
            Pill("탈출 성공")
            if (scored && progress.log.isNotEmpty()) Pill(adventure(progress.log.size), C.Amber)
        }
        Spacer(Modifier.height(d.dp(20)))
        Txt(ui.episodeTitle, 64f, weight = FontWeight.Bold, align = TextAlign.Center)
        Spacer(Modifier.height(d.dp(20)))
        Txt(if (d.compact) ui.endingText else ui.endingText.replace(Regex("([.!?]) "), "$1\n"), 32f, color = C.Sub, align = TextAlign.Center, modifier = Modifier.widthIn(max = d.dp(if (d.compact) 1300 else 900)))
        // The last mission sits right under the story, above the stats: it is what the family does next.
        if (!ui.waitingForCharge) ui.restoreLeft?.let {
            Spacer(Modifier.height(d.dp(if (d.compact) 16 else 28)))
            RestorePanel(it)
        }
        Spacer(Modifier.height(d.dp(if (d.compact) 24 else 48)))
        if (ui.waitingForCharge) Panel(Modifier.width(d.dp(624))) {
            Column(Modifier.fillMaxWidth().padding(d.dp(24))) {
                Row {
                    Txt("충전 ${ui.socPercent.toInt()}% → ${ui.targetPercent}%", 28f, color = C.Sub, maxLines = 1, modifier = Modifier.weight(1f))
                    ui.chargeEtaMinutes?.let { Txt("약 ${it}분 남음", 28f, color = C.Sub, maxLines = 1) }
                }
                Spacer(Modifier.height(d.dp(12)))
                SocBar(ui.socPercent, ui.targetPercent, Modifier.fillMaxWidth().height(d.dp(14)))
            }
        } else {
            // News first (new record, badges, what just opened in 마이 차고): on short windows the rest scrolls.
            if (news) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(d.dp(12), Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(d.dp(10)), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (progress.newBest) Pill("새 기록!", C.Amber)
                    progress.newBadges.sortedBy { it.ordinal }.forEach { Pill("새 배지 · ${it.badge}") }
                    // What just opened in 마이 차고: a Pleos toast line, the way the car announces a new thing.
                    if (newOpened.isNotEmpty()) Toast("마이 차고에 새로 열렸어요: " + newOpened.joinToString(", ") { "${it.kindLabel} ${it.label}" })
                }
                Spacer(Modifier.height(d.dp(if (d.compact) 16 else 28)))
            }
            val best = progress.best[ui.episodeId]
            // Our decorated car beside the stats; under them in portrait, where the row is too narrow.
            val car = @Composable { CarAvatar(look, Modifier.width(d.dp(if (d.land) 300 else 360))) }
            Row(horizontalArrangement = Arrangement.spacedBy(d.dp(24)), verticalAlignment = Alignment.CenterVertically) {
                Stat("걸린 시간", clock(ui.elapsedSec))
                if (best != null) Stat("최고 기록 · 별 ${best.stars}개", clock(best.sec)) else Stat("사용한 힌트", "${ui.hintsUsed}번")
                if (d.land) car()
            }
            if (ui.summary.isNotBlank()) {
                Spacer(Modifier.height(d.dp(16)))
                Txt(ui.summary, 26f, color = C.Sub, align = TextAlign.Center, maxLines = 1)
            }
            if (!d.land) {
                Spacer(Modifier.height(d.dp(16)))
                car()
            }
        }
      }
        Spacer(Modifier.height(d.dp(if (d.compact) 24 else 56)))
        BigButton("처음으로", onBack, Modifier.widthIn(min = d.dp(420)))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val d = LocalDims.current
    Panel(Modifier.width(d.dp(300))) {
        Column(Modifier.fillMaxWidth().padding(vertical = d.dp(24)), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(label, 26f, color = C.Sub, maxLines = 1)
            Txt(value, 44f, weight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** 원래대로 출발: what still differs from the start, live. The family moves it back; the app never does. */
@Composable
private fun RestorePanel(left: List<String>) {
    val d = LocalDims.current
    Panel(Modifier.widthIn(max = d.dp(900)), highlight = left.isEmpty()) {
        Column(Modifier.padding(horizontal = d.dp(32), vertical = d.dp(20)), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("마지막 미션 · 원래대로 출발", 26f, color = C.Gold, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(d.dp(10)))
            if (left.isEmpty()) Txt("출발 준비 완료", 40f, weight = FontWeight.Bold, maxLines = 1)
            else {
                Txt("시작 때와 달라진 곳을 돌려 놓아요", 26f, color = C.Sub, maxLines = 1)
                Spacer(Modifier.height(d.dp(12)))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(d.dp(12), Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(d.dp(10))) {
                    left.forEach { Pill(it, C.Amber) }
                }
            }
        }
    }
}

/** Door open: same opaque pause as driving, its own words. Doors are a pause signal only, nothing about who got out. */
@Composable
fun DoorPauseScreen(ask: Boolean, onResume: () -> Unit) {
    val d = LocalDims.current
    Box(Modifier.fillMaxSize().background(C.BgDeep).padding(d.pad), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(d.dp(if (d.compact) 120 else 168)).clip(RoundedCornerShape(50)).background(if (ask) C.Gold else C.Surface2), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(d.dp(16))) {
                    repeat(2) { Box(Modifier.width(d.dp(18)).height(d.dp(64)).clip(RoundedCornerShape(50)).background(if (ask) C.OnGold else C.Gold)) }
                }
            }
            Spacer(Modifier.height(d.dp(36)))
            Txt(if (ask) "이어서 할까요?" else "문이 열려 잠시 멈췄어요", 56f, weight = FontWeight.Bold, align = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(d.dp(16)))
            Txt(if (ask) "문이 모두 닫혔어요" else "모두 닫히면 이어서 할까요?", 36f, color = C.Sub, align = TextAlign.Center, maxLines = 1)
            if (ask) {
                Spacer(Modifier.height(d.dp(48)))
                BigButton("이어서 하기", onResume, Modifier.widthIn(min = d.dp(420)))
            }
        }
    }
}

/** Opaque, static, swallows every touch. Pleos: no game while the car moves. */
@Composable
fun PausedDrivingOverlay() {
    val d = LocalDims.current
    Box(
        Modifier.fillMaxSize().background(C.BgDeep).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }.padding(d.pad),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(d.dp(if (d.compact) 120 else 168)).clip(RoundedCornerShape(50)).background(C.Gold), contentAlignment = Alignment.Center) {
                Txt("P", 96f, color = C.OnGold, weight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.height(d.dp(36)))
            Txt("주행 중 일시정지", 56f, weight = FontWeight.Bold, align = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(d.dp(16)))
            Txt("기어를 P로 두면 이어집니다", 36f, color = C.Sub, align = TextAlign.Center, maxLines = 1)
        }
    }
}

/** n of 3 stars, drawn so they stay on-palette. */
@Composable
fun Stars(n: Int, size: androidx.compose.ui.unit.Dp) = Row(horizontalArrangement = Arrangement.spacedBy(size * 0.2f)) {
    repeat(3) { i ->
        Canvas(Modifier.size(size)) {
            val c = center
            val r = this.size.minDimension / 2f
            val p = androidx.compose.ui.graphics.Path()
            for (k in 0 until 10) {
                val a = Math.toRadians(-90.0 + k * 36.0)
                val rr = if (k % 2 == 0) r else r * 0.45f
                val x = c.x + rr * kotlin.math.cos(a).toFloat()
                val y = c.y + rr * kotlin.math.sin(a).toFloat()
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            if (i < n) drawPath(p, Brush.verticalGradient(listOf(C.Gold, C.Amber)))
            else drawPath(p, C.Faint, style = Stroke(r * 0.08f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** "15분 ★★☆ 4분 12초" as a chip. */
@Composable
fun RecordChip(label: String, r: BestRecord) {
    val d = LocalDims.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(C.Surface2).padding(horizontal = d.dp(16), vertical = d.dp(6)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 22f, color = C.Gold, weight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(d.dp(10)))
        Stars(r.stars, d.dp(20))
        Spacer(Modifier.width(d.dp(10)))
        Txt(clock(r.sec), 22f, color = C.Sub, maxLines = 1)
    }
}
