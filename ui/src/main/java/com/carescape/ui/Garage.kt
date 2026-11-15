@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.carescape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carescape.core.DriveMode
import com.carescape.core.MissionKind
import com.carescape.core.VehicleInput
import com.carescape.game.DEFAULT_MODE_THEMES
import com.carescape.game.Earned
import com.carescape.game.Item
import com.carescape.game.Look
import com.carescape.game.Paint
import com.carescape.game.Sticker
import com.carescape.game.StickerSlot
import com.carescape.game.Theme
import com.carescape.game.Wheel
import kotlin.math.cos
import kotlin.math.sin

/** 마이 차고 tabs. */
enum class GarageTab(val label: String) { PAINT("페인트"), WHEEL("휠"), STICKER("스티커"), THEME("테마"), DRIVE("드라이브 모드") }

val DriveMode.label
    get() = when (this) {
        DriveMode.NORMAL -> "노멀"
        DriveMode.ECO -> "에코"
        DriveMode.SPORT -> "스포츠"
    }

private val StickerSlot.label
    get() = when (this) {
        StickerSlot.FRONT_DOOR -> "앞문"
        StickerSlot.REAR_DOOR -> "뒷문"
        StickerSlot.TRUNK -> "트렁크"
    }

/** "새 페인트 · 숲 초록" on the ending. */
val Item.kindLabel
    get() = when (this) {
        is Paint -> "페인트"
        is Wheel -> "휠"
        is Sticker -> "스티커"
        is Theme -> "테마"
    }

/** Same sums as the app saves: best stars per episode, badges, episodes finished. */
val Progress.earned get() = Earned(best.values.sumOf { it.stars }, badges, best.size)

private fun rgb(c: Int) = Color(c or 0xFF000000.toInt())

/** Sticker art: bright disc, white pictogram. Kinds without a car part get a star or a bolt. */
private val STICKER_COLORS = listOf(
    0x2F80ED, 0xF2994A, 0x27AE60, 0xEB5757, 0xE0559B, 0x2D9CDB, 0x9B51E0, 0x219653, 0x828282, 0xD35400, 0x16A085, 0xF2C94C,
)

private val MissionKind.icon
    get() = when (this) {
        MissionKind.WINDOW -> VehicleInput.WINDOW
        MissionKind.MORSE -> VehicleInput.READING_LIGHT
        MissionKind.SIGNALS -> VehicleInput.TURN_SIGNAL
        MissionKind.STEERING_HEAT -> VehicleInput.STEERING_HEAT
        MissionKind.SEAT_HEAT -> VehicleInput.SEAT_HEAT
        MissionKind.WIPER -> VehicleInput.WIPER
        MissionKind.SEATBELT -> VehicleInput.SEATBELT
        MissionKind.TWIN_WINDOW -> VehicleInput.PASSENGER_WINDOW
        MissionKind.MEMORY, MissionKind.RIDDLE, MissionKind.CHAIN, MissionKind.LIGHTNING -> null
    }

@Composable
fun StickerBadge(kind: MissionKind, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(50)).background(rgb(STICKER_COLORS[kind.ordinal])).border(size * 0.06f, Color.White, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        kind.icon?.let { InputIcon(it, size * 0.62f, Color.White) } ?: Canvas(Modifier.size(size * 0.56f)) {
            if (kind == MissionKind.LIGHTNING) {
                val w = this.size.width
                drawPath(Path().apply {
                    moveTo(w * 0.58f, 0f); lineTo(w * 0.18f, w * 0.56f); lineTo(w * 0.48f, w * 0.56f)
                    lineTo(w * 0.38f, w); lineTo(w * 0.84f, w * 0.4f); lineTo(w * 0.54f, w * 0.4f); close()
                }, Color.White)
            } else drawPath(star(center, this.size.width / 2f, 0.45f), Color.White)
        }
    }
}

private fun star(c: Offset, r: Float, inner: Float) = Path().apply {
    for (k in 0 until 10) {
        val a = Math.toRadians(-90.0 + k * 36.0)
        val rr = if (k % 2 == 0) r else r * inner
        val x = c.x + rr * cos(a).toFloat()
        val y = c.y + rr * sin(a).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** One wheel: tire plus the rim design. */
private fun DrawScope.wheel(type: Wheel, c: Offset, r: Float) {
    drawCircle(Color(0xFF15181E), r, c)
    val rim = r * 0.68f
    when (type) {
        Wheel.BASIC -> {
            drawCircle(Color(0xFFB9C0CA), rim, c)
            repeat(5) { k ->
                val a = Math.toRadians(-90.0 + k * 72.0)
                drawCircle(Color(0xFF3A404A), rim * 0.24f, Offset(c.x + rim * 0.58f * cos(a).toFloat(), c.y + rim * 0.58f * sin(a).toFloat()))
            }
            drawCircle(Color(0xFF5A616C), rim * 0.22f, c)
        }
        Wheel.STAR -> {
            drawCircle(Color(0xFF2A2F38), rim, c)
            drawPath(star(c, rim * 0.9f, 0.42f), Color(0xFFFFD25A))
        }
        Wheel.PINWHEEL -> {
            drawCircle(Color(0xFFF4F1EA), rim, c)
            listOf(0xFFEB5757, 0xFFF2C94C, 0xFF2F80ED, 0xFF27AE60).forEachIndexed { k, col ->
                drawArc(Color(col), k * 90f, 62f, true, Offset(c.x - rim * 0.88f, c.y - rim * 0.88f), Size(rim * 1.76f, rim * 1.76f))
            }
            drawCircle(Color(0xFF3A404A), rim * 0.16f, c)
        }
        Wheel.GOLD -> {
            drawCircle(Color(0xFFD9B04C), rim, c)
            repeat(10) { k ->
                val a = Math.toRadians(k * 36.0)
                drawLine(Color(0xFF8A6A1E), c, Offset(c.x + rim * 0.92f * cos(a).toFloat(), c.y + rim * 0.92f * sin(a).toFloat()), rim * 0.08f, StrokeCap.Round)
            }
            drawCircle(Color(0xFFFFE6A0), rim * 0.24f, c)
        }
    }
}

/** Where each sticker sits on the side render (fractions of width / height), from tools/ioniq9/out/sticker_slots.json. */
private val SLOT_AT = mapOf(StickerSlot.FRONT_DOOR to Offset(0.6127f, 0.5818f), StickerSlot.REAR_DOOR to Offset(0.3873f, 0.5818f), StickerSlot.TRUNK to Offset(0.1145f, 0.5f))

private val Wheel.base
    get() = when (this) {
        Wheel.BASIC -> R.drawable.ioniq9_side_base_basic
        Wheel.STAR -> R.drawable.ioniq9_side_base_star
        Wheel.PINWHEEL -> R.drawable.ioniq9_side_base_pinwheel
        Wheel.GOLD -> R.drawable.ioniq9_side_base_gold
    }

/**
 * The family's IONIQ 9 from the side, nose to the right: our own Blender render (tools/ioniq9) in white paint with the
 * picked wheels, then the paint mask recolored on top (mask x paint / 0.70, the white base's brightness), stickers on
 * the three slots. Any width, height follows (2.5 : 1, the render's 1200x480).
 */
@Composable
fun CarAvatar(look: Look, modifier: Modifier = Modifier) = BoxWithConstraints(modifier.aspectRatio(2.5f)) {
    val c = rgb(look.paint.rgb)
    val k = 1f / 0.70f
    Image(painterResource(look.wheel.base), null, Modifier.fillMaxSize())
    Image(
        painterResource(R.drawable.ioniq9_side_paint_mask), null, Modifier.fillMaxSize(),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(c.red * k, 0f, 0f, 0f, 0f, 0f, c.green * k, 0f, 0f, 0f, 0f, 0f, c.blue * k, 0f, 0f, 0f, 0f, 0f, 1f, 0f))),
    )
    val sz = maxHeight * 0.2f
    look.stickers.forEach { (slot, kind) ->
        val at = SLOT_AT.getValue(slot)
        Box(Modifier.offset(maxWidth * at.x - sz / 2, maxHeight * at.y - sz / 2)) { StickerBadge(kind, sz) }
    }
}

/** Theme preview tile: its background, a surface card and the accent. */
@Composable
private fun ThemeSwatch(t: Theme, size: Dp) = Canvas(Modifier.size(size)) {
    val p = paletteOf(t)
    val s = this.size.width
    drawRoundRect(Brush.verticalGradient(listOf(p.top, p.bgDeep)), cornerRadius = CornerRadius(s * 0.18f))
    drawRoundRect(p.surface, Offset(s * 0.14f, s * 0.42f), Size(s * 0.72f, s * 0.42f), CornerRadius(s * 0.08f))
    drawCircle(p.gold, s * 0.12f, Offset(s * 0.3f, s * 0.24f))
    drawRoundRect(Brush.horizontalGradient(listOf(p.gold, p.amber)), Offset(s * 0.24f, s * 0.6f), Size(s * 0.52f, s * 0.1f), CornerRadius(s * 0.05f))
    drawRoundRect(p.line, cornerRadius = CornerRadius(s * 0.18f), style = Stroke(s * 0.03f))
}

@Composable
private fun LockGlyph(size: Dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    drawCircle(C.Surface.copy(alpha = 0.85f), w * 0.56f) // readable over any swatch, light or dark
    drawArc(C.Text, 180f, 180f, false, Offset(w * 0.28f, w * 0.1f), Size(w * 0.44f, w * 0.5f), style = Stroke(w * 0.1f))
    drawRoundRect(C.Text, Offset(w * 0.16f, w * 0.38f), Size(w * 0.68f, w * 0.52f), CornerRadius(w * 0.1f))
    drawCircle(C.Surface, w * 0.07f, Offset(w * 0.5f, w * 0.6f))
}

/** One pickable item. Locked: dimmed art, a padlock, and the one line that says how to open it. */
@Composable
private fun OptionCard(item: Item?, label: String, on: Boolean, earned: Earned, modifier: Modifier, onClick: () -> Unit, art: @Composable (Dp) -> Unit) {
    val d = LocalDims.current
    val open = item?.open(earned) ?: true
    Panel(modifier.heightIn(min = d.touch).clickable(enabled = open, role = Role.RadioButton, onClick = onClick), highlight = on) {
        Row(Modifier.padding(d.dp(18)), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.alpha(if (open) 1f else 0.35f)) { art(d.dp(72)) }
                if (!open) LockGlyph(d.dp(40))
            }
            Spacer(Modifier.width(d.dp(16)))
            Column(Modifier.weight(1f)) {
                Txt(label, 26f, color = if (open) C.Text else C.Sub, weight = FontWeight.SemiBold, maxLines = 1)
                if (!open) Txt(item!!.need.hint, 20f, color = C.Sub, maxLines = 2)
            }
        }
    }
}

/** Two cards a row, equal heights. */
@Composable
private fun Grid(cells: List<@Composable (Modifier) -> Unit>) {
    val d = LocalDims.current
    Column(verticalArrangement = Arrangement.spacedBy(d.dp(16))) {
        cells.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.dp(16))) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    val d = LocalDims.current
    Spacer(Modifier.height(d.dp(20)))
    Txt(text, 24f, color = C.Sub, weight = FontWeight.SemiBold, maxLines = 1)
    Spacer(Modifier.height(d.dp(10)))
}

/**
 * 마이 차고: left (or top) the car as it looks now, right (or below) the tabs. Every pick is saved at once through onLook.
 * Leaving the screen ends a 무드등 preview (onMoodEnd), so the cabin light never stays changed.
 */
@Composable
fun GarageScreen(look: Look, earned: Earned, mode: DriveMode?, actions: GameActions, onBack: () -> Unit, startTab: GarageTab = GarageTab.PAINT, moodOn: Boolean = false) {
    val d = LocalDims.current
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val onMoodEnd = actions.onMoodEnd
    DisposableEffect(Unit) { onDispose(onMoodEnd) }
    val top = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigButton("‹ 돌아가기", onBack, primary = false)
            Spacer(Modifier.width(d.dp(28)))
            Txt("우리 차 꾸미기", 52f, weight = FontWeight.Bold, maxLines = 1)
        }
    }
    val car = @Composable { m: Modifier ->
        Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CarAvatar(look, Modifier.fillMaxWidth())
            if (!d.compact) {
                Spacer(Modifier.height(d.dp(20)))
                Txt("${look.paint.label} · ${look.wheel.label} · 스티커 ${look.stickers.size}개", 26f, color = C.Sub, align = TextAlign.Center, maxLines = 2)
            }
        }
    }
    val picker = @Composable { m: Modifier ->
        Column(m) {
            Segmented(GarageTab.entries, tab, { it.label }, { tab = it }, fitted = false)
            Spacer(Modifier.height(d.dp(20)))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val set = actions.onLook
                when (tab) {
                    GarageTab.PAINT -> Grid(Paint.entries.map { p -> { m -> OptionCard(p, p.label, look.paint == p, earned, m, { set(look.copy(paint = p)) }) { Swatch(rgb(p.rgb), it) } } })
                    GarageTab.WHEEL -> Grid(Wheel.entries.map { w -> { m -> OptionCard(w, w.label, look.wheel == w, earned, m, { set(look.copy(wheel = w)) }) { WheelArt(w, it) } } })
                    GarageTab.STICKER -> StickerTab(look, earned, set)
                    GarageTab.THEME -> {
                        val shown = look.shownTheme(mode)
                        if (look.followMode && mode != null) {
                            Txt("지금은 주행 모드에 맞춰 바뀌어요. 여기서 고르면 그 테마로 고정돼요", 22f, color = C.Sub)
                            Spacer(Modifier.height(d.dp(16)))
                        }
                        Grid(Theme.entries.map { t -> { m -> OptionCard(t, t.label, shown == t, earned, m, { set(look.copy(theme = t, followMode = false)) }) { ThemeSwatch(t, it) } } })
                    }
                    GarageTab.DRIVE -> DriveTab(look, earned, mode, actions, moodOn)
                }
            }
        }
    }
    if (d.land) Row(Modifier.fillMaxSize().padding(d.pad)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            top()
            car(Modifier.weight(1f).fillMaxWidth())
        }
        Spacer(Modifier.width(d.dp(48)))
        picker(Modifier.weight(1f).fillMaxHeight())
    } else Column(Modifier.fillMaxSize().padding(d.pad), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) { top() }
        Spacer(Modifier.height(d.dp(24)))
        car(Modifier.widthIn(max = d.dp(640)).fillMaxWidth())
        Spacer(Modifier.height(d.dp(28)))
        picker(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun Swatch(c: Color, size: Dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(c).border(2.dp, C.Line, RoundedCornerShape(50)))

@Composable
private fun WheelArt(w: Wheel, size: Dp) = Canvas(Modifier.size(size)) { wheel(w, center, this.size.width / 2f) }

@Composable
private fun StickerTab(look: Look, earned: Earned, set: (Look) -> Unit) {
    val d = LocalDims.current
    var slot by rememberSaveable { mutableStateOf(StickerSlot.FRONT_DOOR) }
    Txt("붙일 곳을 고르고, 스티커를 눌러요", 24f, color = C.Sub, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(d.dp(10)))
    Segmented(StickerSlot.entries, slot, { it.label }, { slot = it })
    Spacer(Modifier.height(d.dp(20)))
    val off: @Composable (Modifier) -> Unit = { m ->
        OptionCard(null, "떼기", look.stickers[slot] == null, earned, m, { set(look.copy(stickers = look.stickers - slot)) }) {
            Box(Modifier.size(it).border(2.dp, C.Faint, RoundedCornerShape(50)))
        }
    }
    Grid(listOf(off) + com.carescape.game.STICKERS.map { s ->
        { m -> OptionCard(s, s.label, look.stickers[slot] == s.kind, earned, m, { set(look.copy(stickers = look.stickers + (slot to s.kind))) }) { StickerBadge(s.kind, it) } }
    })
}

/**
 * 드라이브 모드: the app only reads the car's mode (Pleos DrivingMode has no set). Each mode gets a skin and a 무드등 color;
 * 모드 따라가기 switches the skin while parked. The light preview goes through the game's own ambient path, parked only.
 */
@Composable
private fun DriveTab(look: Look, earned: Earned, mode: DriveMode?, actions: GameActions, moodOn: Boolean) {
    val d = LocalDims.current
    var edit by rememberSaveable { mutableStateOf(mode ?: DriveMode.NORMAL) }
    var refused by remember { mutableStateOf(false) } // last 미리 켜 보기 tap was not in P
    val set = actions.onLook
    // moodOn comes from the app, so a light ended elsewhere (onStop, driving off) never leaves this saying "켰어요".
    val retint = { c: Int -> refused = false; if (moodOn) actions.onMoodPreview(c) }
    val preview = @Composable {
        BigButton(if (moodOn) "무드등 끄기" else "무드등 미리 켜 보기", {
            if (moodOn) actions.onMoodEnd() else refused = !actions.onMoodPreview(look.moodOf(edit))
        }, Modifier.fillMaxWidth(), primary = false)
        Spacer(Modifier.height(d.dp(10)))
        Txt(
            when {
                moodOn -> "무드등을 켰어요. 차고에서 나가면 원래대로 돌아와요"
                refused -> "기어가 P일 때만 켤 수 있어요"
                else -> "차가 서 있을 때(P)만 켜져요"
            }, 22f, color = if (moodOn || refused) C.Gold else C.Sub,
        )
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(d.dp(24)), verticalAlignment = Alignment.CenterVertically) {
            Txt("지금 주행 모드", 26f, color = C.Sub, maxLines = 1, modifier = Modifier.weight(1f))
            Pill(mode?.let { "${it.label} 모드" } ?: "모드 정보 없음", if (mode != null) C.Gold else C.Faint)
        }
    }
    Spacer(Modifier.height(d.dp(16)))
    Panel(Modifier.fillMaxWidth().heightIn(min = d.touch).toggleable(look.followMode, role = Role.Switch) { set(look.copy(followMode = it)) }) {
        Row(Modifier.padding(d.dp(24)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("모드 따라가기", 30f, weight = FontWeight.SemiBold, maxLines = 1)
                Txt(
                    when {
                        !look.followMode -> "테마 탭에서 고른 테마로 고정돼요"
                        mode == null -> "모드 정보가 없어서 고른 테마로 보여요"
                        else -> "차가 서 있을 때 모드에 맞춰 화면이 바뀌어요"
                    }, 22f, color = C.Sub, maxLines = 2,
                )
            }
            Spacer(Modifier.width(d.dp(16)))
            Toggle(look.followMode)
        }
    }
    Label("꾸밀 모드")
    Segmented(DriveMode.entries, edit, { m -> if (m == mode) "${m.label} · 지금" else m.label }, { m -> edit = m; retint(look.moodOf(m)) })
    // Connect-WS: the main action of this tab right under the mode chips, not below the fold.
    if (d.compact) { Spacer(Modifier.height(d.dp(16))); preview() }
    Label("${edit.label} 모드 화면: ${look.themeOf(edit).label}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(d.dp(12)), verticalArrangement = Arrangement.spacedBy(d.dp(12))) {
        Theme.entries.forEach { t ->
            val open = t.open(earned)
            Pick(look.themeOf(edit) == t, open, {
                val l = look.copy(modeThemes = if (t == DEFAULT_MODE_THEMES[edit]) look.modeThemes - edit else look.modeThemes + (edit to t))
                set(l)
                retint(l.moodOf(edit))
            }) { ThemeSwatch(t, it) }
        }
    }
    Label("${edit.label} 모드 무드등")
    val mood = look.moodOf(edit)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(d.dp(12)), verticalArrangement = Arrangement.spacedBy(d.dp(12))) {
        Theme.entries.map { it.mood }.distinct().forEach { c ->
            Pick(mood == c, true, {
                set(look.copy(modeMoods = if (c == look.themeOf(edit).mood) look.modeMoods - edit else look.modeMoods + (edit to c)))
                retint(c)
            }) { s -> Box(Modifier.size(s * 0.7f).clip(RoundedCornerShape(50)).background(Brush.radialGradient(listOf(Color.White, rgb(c), rgb(c).copy(alpha = 0.4f))))) }
        }
    }
    if (!d.compact) { Spacer(Modifier.height(d.dp(24))); preview() }
}

/** Square pick button at touch size, ring when picked. */
@Composable
private fun Pick(on: Boolean, enabled: Boolean, onClick: () -> Unit, art: @Composable (Dp) -> Unit) {
    val d = LocalDims.current
    val s = maxOf(d.touch, d.dp(96))
    val shape = RoundedCornerShape(d.dp(20))
    Box(
        Modifier.size(s).clip(shape).background(C.Surface2).border(if (on) 3.dp else 1.dp, if (on) C.Gold else C.Line, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.alpha(if (enabled) 1f else 0.4f)) { art(s * 0.72f) }
        if (!enabled) LockGlyph(s * 0.4f)
    }
}
