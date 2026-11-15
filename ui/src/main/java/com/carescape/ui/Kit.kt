package com.carescape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carescape.core.CarSeat
import com.carescape.core.VehicleInput
import com.carescape.game.Theme
import kotlin.math.max
import kotlin.math.min

/** One theme pack's color tokens. top = start of the background gradient. Every text pair is >= 4.5:1 on Bg, BgDeep and both surfaces. */
class Palette(
    val bg: Color, val bgDeep: Color, val surface: Color, val surface2: Color, val line: Color,
    val text: Color, val sub: Color, val faint: Color, val gold: Color, val amber: Color, val onGold: Color, val top: Color,
)

/** Night cabin palette (밤하늘, the default): deep navy, warm gold/amber accents. */
private val NIGHT = Palette(
    Color(0xFF0A111E), Color(0xFF05080F), Color(0xFF131C2C), Color(0xFF1C2739), Color(0x40E9DCC4),
    Color(0xFFF4F1EA), Color(0xFFB7C0CE), Color(0xFF8590A3), Color(0xFFE8B962), Color(0xFFF2994A), Color(0xFF1A1305), Color(0xFF0E1728),
)

fun paletteOf(t: Theme): Palette = when (t) {
    Theme.NIGHT_SKY -> NIGHT
    Theme.FOREST_CAMP -> Palette(
        Color(0xFF0B1A12), Color(0xFF050D08), Color(0xFF12261A), Color(0xFF1A3324), Color(0x40D8EBC9),
        Color(0xFFF1F5EC), Color(0xFFB6CBB9), Color(0xFF86A08C), Color(0xFF7FD98F), Color(0xFFF2B84A), Color(0xFF0A1A0E), Color(0xFF12281B),
    )
    Theme.RACING -> Palette(
        Color(0xFF150C0C), Color(0xFF0A0505), Color(0xFF231515), Color(0xFF301C1C), Color(0x40F2D0C8),
        Color(0xFFF7F0EE), Color(0xFFD0BAB6), Color(0xFFA48D89), Color(0xFFFF6A4D), Color(0xFFFFB13D), Color(0xFF1A0503), Color(0xFF241010),
    )
    Theme.UNDERSEA -> Palette(
        Color(0xFF061620), Color(0xFF020A10), Color(0xFF0C2230), Color(0xFF13303F), Color(0x40C4E6F0),
        Color(0xFFEEF8FB), Color(0xFFAFCAD4), Color(0xFF7F9DA9), Color(0xFF4FD2F0), Color(0xFF7BE0C4), Color(0xFF03141C), Color(0xFF0B2A3C),
    )
    Theme.SPACE_STATION -> Palette(
        Color(0xFF0D0B1E), Color(0xFF06050F), Color(0xFF17142E), Color(0xFF221E3E), Color(0x40D9D4F2),
        Color(0xFFF3F1FA), Color(0xFFBDB8D6), Color(0xFF8E89AB), Color(0xFFA99BFF), Color(0xFF6FE3E8), Color(0xFF0E0A24), Color(0xFF15122E),
    )
    Theme.STRAWBERRY_MILK -> Palette(
        Color(0xFFFFF4F7), Color(0xFFFCE6ED), Color(0xFFFFFFFF), Color(0xFFFBE9EF), Color(0x4D8A3A55),
        Color(0xFF3A1A26), Color(0xFF6B4150), Color(0xFF7E5464), Color(0xFFC2185B), Color(0xFF9C3D78), Color(0xFFFFFFFF), Color(0xFFFFFAFC),
    )
}

/**
 * Current skin (카카오톡 테마처럼). :app sets [theme] from the ViewModel, snapshot tests set it directly.
 * Every token is read through it, so switching the theme recomposes every screen with no call-site changes.
 */
object C {
    var theme by mutableStateOf(Theme.NIGHT_SKY)
    private val p get() = paletteOf(theme)
    val Bg get() = p.bg
    val BgDeep get() = p.bgDeep
    val Surface get() = p.surface
    val Surface2 get() = p.surface2
    val Line get() = p.line
    val Text get() = p.text
    val Sub get() = p.sub
    val Faint get() = p.faint
    val Gold get() = p.gold
    val Amber get() = p.amber
    val OnGold get() = p.onGold
}

/**
 * Scale relative to the 17" window (1680x1184 land / 840x1184 port).
 * compact = very wide window (width > 1.8x height, i.e. Connect-WS 1640x810): height-bound scaling would shrink it to
 * ~0.68 with half the width empty, so it scales against a 760dp reference height and screens drop decorative parts.
 * WF 1256x800 and S12.9 1274x855 (ratio ~1.5) keep the normal layout: it fits at their size with room to spare.
 */
class Dims(val s: Float, val land: Boolean, val w: Dp, val h: Dp, val compact: Boolean = false) {
    fun dp(v: Number) = (v.toFloat() * s).dp
    fun sp(v: Number) = max(16f, v.toFloat() * s).sp // Pleos: >= 18px real; 16sp = 20px at 200dpi
    val touch get() = max(76f, 96f * s).dp // Pleos recommended touch size
    val pad get() = dp(56)
}

val LocalDims = staticCompositionLocalOf { Dims(1f, true, 1680.dp, 1184.dp) }

@Composable
fun Scaled(content: @Composable BoxScope.() -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val land = maxWidth > maxHeight
    val compact = land && maxWidth > maxHeight * 1.8f
    val s = min(maxWidth.value / (if (land) 1680f else 840f), maxHeight.value / (if (compact) 760f else 1184f)).coerceIn(0.34f, 1.2f)
    CompositionLocalProvider(LocalDims provides Dims(s, land, maxWidth, maxHeight, compact)) { Box(Modifier.fillMaxSize(), content = content) }
}

/** App background: the theme's gradient, its corner glow, and a light motif drawn behind (nothing for 밤하늘). */
fun Modifier.nightBackground() = drawBehind {
    drawRect(Brush.verticalGradient(listOf(paletteOf(C.theme).top, C.BgDeep)))
    drawCircle(
        Brush.radialGradient(listOf(C.Amber.copy(alpha = 0.10f), Color.Transparent), Offset(size.width * 0.1f, 0f), size.maxDimension * 0.6f),
        size.maxDimension * 0.6f, Offset(size.width * 0.1f, 0f),
    )
    val w = size.width
    val h = size.height
    val m = size.minDimension
    when (C.theme) {
        Theme.NIGHT_SKY -> Unit
        // Pine row along the bottom.
        Theme.FOREST_CAMP -> repeat(9) { i ->
            val x = w * (i + 0.5f) / 9f
            val th = m * (0.16f + 0.06f * (i % 3))
            drawPath(Path().apply { moveTo(x, h - th); lineTo(x + th * 0.32f, h); lineTo(x - th * 0.32f, h); close() }, C.Gold.copy(alpha = 0.07f))
        }
        // Speed stripes, top right.
        Theme.RACING -> repeat(5) { i ->
            val y = h * (0.08f + 0.05f * i)
            drawLine(C.Gold.copy(alpha = 0.10f - 0.015f * i), Offset(w * (0.55f + 0.05f * i), y), Offset(w * 0.98f, y), m * 0.012f, StrokeCap.Round)
        }
        // Rising bubbles.
        Theme.UNDERSEA -> repeat(12) { i ->
            val x = w * ((i * 0.37f + 0.11f) % 1f)
            val y = h * ((i * 0.53f + 0.2f) % 1f)
            drawCircle(C.Gold.copy(alpha = 0.10f), m * (0.008f + 0.004f * (i % 4)), Offset(x, y), style = Stroke(m * 0.003f))
        }
        // Stars and a ringed planet.
        Theme.SPACE_STATION -> {
            repeat(24) { i -> drawCircle(C.Text.copy(alpha = 0.10f + 0.05f * (i % 3)), m * 0.0025f, Offset(w * ((i * 0.618f) % 1f), h * ((i * 0.381f + 0.05f) % 1f))) }
            val c = Offset(w * 0.9f, h * 0.18f)
            drawCircle(C.Gold.copy(alpha = 0.10f), m * 0.06f, c)
            drawOval(C.Amber.copy(alpha = 0.12f), Offset(c.x - m * 0.11f, c.y - m * 0.025f), Size(m * 0.22f, m * 0.05f), style = Stroke(m * 0.004f))
        }
        // Soft polka dots.
        Theme.STRAWBERRY_MILK -> repeat(18) { i ->
            drawCircle(C.Gold.copy(alpha = 0.06f), m * 0.012f, Offset(w * ((i * 0.618f + 0.03f) % 1f), h * ((i * 0.29f + 0.07f) % 1f)))
        }
    }
}

/** Break Korean at word/phrase boundaries instead of mid-word. */
/** Asta Sans: the Pleos system UI font (typography guide). Static instances so every weight renders everywhere. */
val Asta = FontFamily(
    Font(R.font.asta_sans_regular, FontWeight.Normal),
    Font(R.font.asta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.asta_sans_bold, FontWeight.Bold),
)

private val KoText = TextStyle(
    fontFamily = Asta, localeList = LocaleList("ko"), lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
)

@Composable
fun Txt(
    text: String, size: Float, modifier: Modifier = Modifier, color: Color = C.Text,
    weight: FontWeight = FontWeight.Normal, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE,
) {
    val d = LocalDims.current
    val fs = d.sp(size)
    Text(
        text, modifier, color = color, fontSize = fs, lineHeight = fs * 1.35f, fontWeight = weight,
        textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = KoText,
    )
}

/** Pleos Surface: a rounded card (r32, like the guide's popup/menu radius). highlight = picked. */
@Composable
fun Panel(modifier: Modifier = Modifier, highlight: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val d = LocalDims.current
    val shape = RoundedCornerShape(d.dp(32))
    Box(
        modifier.clip(shape).background(C.Surface).border(if (highlight) 3.dp else 1.dp, if (highlight) C.Gold else C.Line, shape),
        content = content,
    )
}

/**
 * Pleos Basic button, size L: 100 high, r32, label Body L (30). primary = Filled (theme accent), else the basic
 * translucent fill (5~10% of the text color). Pressed darkens / deepens the fill, disabled is an outline with a 32% label.
 */
@Composable
fun BigButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val d = LocalDims.current
    val shape = RoundedCornerShape(d.dp(32))
    val press = remember { MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val bg = when {
        !enabled -> Modifier.border(1.dp, C.Line, shape)
        primary -> Modifier.background(if (down) lerp(C.Gold, Color.Black, 0.18f) else C.Gold)
        else -> Modifier.background(C.Text.copy(alpha = if (down) 0.18f else 0.09f))
    }
    val fg = when {
        !enabled -> C.Text.copy(alpha = 0.32f)
        primary -> C.OnGold
        else -> C.Text
    }
    Box(
        modifier.heightIn(min = maxOf(d.touch, d.dp(100))).clip(shape).then(bg)
            .clickable(press, null, enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = d.dp(40)),
        contentAlignment = Alignment.Center,
    ) { Txt(label, 30f, color = fg, weight = FontWeight.SemiBold, maxLines = 1) }
}

/** Pleos indicator: a borderless tinted label, not tappable. */
@Composable
fun Pill(text: String, color: Color = C.Gold) {
    val d = LocalDims.current
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = d.dp(20), vertical = d.dp(8))) {
        Txt(text, 22f, color = color, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Pleos Tabs (Segmented, Box type): a 100-high r32 tray at 5~6%, the picked segment a filled r24 block inset 8,
 * 2x36 dividers between unpicked segments. fitted = equal widths across the row; otherwise each fits its label and the
 * tray scrolls sideways when it runs out of room.
 */
@Composable
fun <T> Segmented(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, fitted: Boolean = true) {
    val d = LocalDims.current
    val tray = @Composable { m: Modifier ->
        Row(m.height(maxOf(d.touch, d.dp(100))).clip(RoundedCornerShape(d.dp(32))).background(C.Text.copy(alpha = 0.06f)), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, o ->
                val on = o == selected
                if (i > 0) Box(Modifier.width(2.dp).height(d.dp(36)).background(if (on || options[i - 1] == selected) Color.Transparent else C.Text.copy(alpha = 0.12f)))
                Box(
                    (if (fitted) Modifier.weight(1f) else Modifier).fillMaxHeight().selectable(on, role = Role.Tab) { onSelect(o) }.padding(d.dp(8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        (if (fitted) Modifier.fillMaxWidth() else Modifier).fillMaxHeight().clip(RoundedCornerShape(d.dp(24)))
                            .background(if (on) C.Gold else Color.Transparent).padding(horizontal = d.dp(if (fitted) 12 else 32)),
                        contentAlignment = Alignment.Center,
                    ) { Txt(label(o), 26f, color = if (on) C.OnGold else C.Text, weight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    }
    if (fitted) tray(modifier.fillMaxWidth()) else Box(modifier.horizontalScroll(rememberScrollState())) { tray(Modifier) }
}

/** Pleos Switch as on the device (capture 09): 80x48 pill track, round 32 knob inset 8. Drawing only; the row around it is the toggleable target. */
@Composable
fun Toggle(on: Boolean) {
    val d = LocalDims.current
    Box(
        Modifier.size(d.dp(80), d.dp(48)).clip(CircleShape).background(if (on) C.Gold else C.Text.copy(alpha = 0.22f)).padding(d.dp(8)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(d.dp(32)).clip(CircleShape).background(if (on) Color.White else C.Sub)) }
}

/** Pleos Toast: 80 high, r16, #131417 at 84% with white text in any theme, at most 792 wide. */
@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    Box(
        modifier.heightIn(min = d.dp(80)).widthIn(max = d.dp(792)).clip(RoundedCornerShape(d.dp(16))).background(Color(0xD6131417))
            .padding(horizontal = d.dp(40), vertical = d.dp(12)),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 26f, color = Color.White, align = TextAlign.Center, maxLines = 2) }
}

/** Fills the largest centered square, for round visuals. */
@Composable
fun SquareBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(min(maxWidth.value, maxHeight.value).dp), content = content)
    }

fun etaText(m: Int?) = m?.let { "약 ${it}분" } ?: "계산 중"

val VehicleInput.label
    get() = when (this) {
        VehicleInput.WINDOW -> "운전석 창문"
        VehicleInput.READING_LIGHT -> "독서등"
        VehicleInput.TURN_SIGNAL -> "깜빡이"
        VehicleInput.STEERING_HEAT -> "핸들 열선"
        VehicleInput.SEAT_HEAT -> "시트 열선"
        VehicleInput.WIPER -> "와이퍼"
        VehicleInput.SEATBELT -> "안전벨트"
        VehicleInput.PASSENGER_WINDOW -> "조수석 창문"
        VehicleInput.REAR_WINDOW -> "2열 창문"
    }

val VehicleInput.isWindow get() = this == VehicleInput.WINDOW || this == VehicleInput.PASSENGER_WINDOW || this == VehicleInput.REAR_WINDOW

/** Short name for a dial readout ("창문 35%"). */
val VehicleInput.short get() = if (isWindow) "창문" else label

/** Same words as the game's seatName (ui does not depend on :game). */
val CarSeat.label
    get() = when (this) {
        CarSeat.ROW_1_LEFT -> "운전석"
        CarSeat.ROW_1_RIGHT -> "조수석"
        CarSeat.ROW_2_LEFT -> "2열 왼쪽"
        CarSeat.ROW_2_CENTER -> "2열 가운데"
        CarSeat.ROW_2_RIGHT -> "2열 오른쪽"
        CarSeat.ROW_3_LEFT -> "3열 왼쪽"
        CarSeat.ROW_3_RIGHT -> "3열 오른쪽"
    }
