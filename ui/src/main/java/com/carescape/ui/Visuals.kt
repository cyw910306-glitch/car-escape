package com.carescape.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.carescape.core.CarSeat
import com.carescape.core.CarWindow
import com.carescape.core.PuzzleVisual
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

@Composable
fun PuzzleVisualView(v: PuzzleVisual?, input: VehicleInput?, modifier: Modifier = Modifier): Unit = when (v) {
    is PuzzleVisual.Dial -> DialView(v, input?.short.orEmpty(), modifier)
    is PuzzleVisual.Morse -> MorseView(v, modifier)
    is PuzzleVisual.Sequence -> SequenceView(v, modifier)
    is PuzzleVisual.Level -> LevelView(v, input, modifier)
    is PuzzleVisual.Memory -> MemoryView(v, modifier)
    is PuzzleVisual.Riddle -> RiddleView(v, input, modifier)
    is PuzzleVisual.Chain -> ChainView(v, input, modifier)
    is PuzzleVisual.Seats -> SeatsView(v, modifier)
    is PuzzleVisual.Twin -> TwinView(v, input, modifier)
    null -> Box(modifier)
}

private fun angleOf(v: Float) = 135f + 270f * v.coerceIn(0f, 100f) / 100f
private fun DrawScope.polar(c: Offset, r: Float, deg: Float) =
    Offset(c.x + r * cos(deg * PI / 180).toFloat(), c.y + r * sin(deg * PI / 180).toFloat())

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, px: Float, color: Color, bold: Boolean = false) {
    val l = tm.measure(text, TextStyle(fontFamily = Asta, color = color, fontSize = (px / density / fontScale).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold))
    drawText(l, topLeft = Offset(at.x - l.size.width / 2f, at.y - l.size.height / 2f))
}

/** Safe dial. unit names what the number is (e.g. "창문"), shown as a percent. */
@Composable
fun DialView(v: PuzzleVisual.Dial, unit: String, modifier: Modifier = Modifier) {
    val ok = abs(v.current - v.target) <= v.tolerance
    val tm = rememberTextMeasurer()
    SquareBox(modifier) { BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth.value
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f * 0.96f
            drawCircle(Brush.radialGradient(listOf(C.Surface2, C.Surface, C.BgDeep), c, r), r, c)
            drawCircle(C.Line, r, c, style = Stroke(r * 0.012f))
            val tr = r * 0.80f
            val tw = r * 0.08f
            val arc = Size(tr * 2, tr * 2)
            val tl = Offset(c.x - tr, c.y - tr)
            drawArc(C.BgDeep, 135f, 270f, false, tl, arc, style = Stroke(tw, cap = StrokeCap.Round))
            val lo = (v.target - v.tolerance).coerceAtLeast(0)
            val hi = (v.target + v.tolerance).coerceAtMost(100)
            drawArc(C.Gold, angleOf(lo.toFloat()), maxOf(270f * (hi - lo) / 100f, 2f), false, tl, arc, style = Stroke(tw))
            for (i in 0..100 step 5) {
                val a = angleOf(i.toFloat())
                val major = i % 10 == 0
                drawLine(if (major) C.Sub else C.Faint, polar(c, r * 0.90f, a), polar(c, r * (if (major) 0.98f else 0.95f), a), r * (if (major) 0.014f else 0.008f))
            }
            listOf(0, 50, 100).forEach { label(tm, "$it", polar(c, r * 0.66f, angleOf(it.toFloat())), r * 0.10f, C.Sub) }
            // Which way to turn: curved arrow from the needle toward the gold zone.
            if (!ok) {
                val from = angleOf(v.current.toFloat())
                val to = angleOf(v.target.toFloat())
                val sweep = (to - from).let { if (abs(it) > 40f) 40f * sign(it) else it * 0.8f }
                val ar = r * 0.50f
                drawArc(C.Amber, from, sweep, false, Offset(c.x - ar, c.y - ar), Size(ar * 2, ar * 2), style = Stroke(r * 0.03f, cap = StrokeCap.Round))
                val end = from + sweep
                val tip = polar(c, ar, end + sign(sweep) * 6f)
                drawPath(Path().apply {
                    moveTo(tip.x, tip.y)
                    polar(c, ar - r * 0.06f, end).let { lineTo(it.x, it.y) }
                    polar(c, ar + r * 0.06f, end).let { lineTo(it.x, it.y) }
                    close()
                }, C.Amber)
            }
            val a = angleOf(v.current.toFloat())
            val needle = if (ok) C.Gold else C.Amber
            // Needle stops short of the 0/50/100 labels (r*0.66); a knob on the track marks the value.
            drawLine(needle, c, polar(c, r * 0.52f, a), r * 0.035f, StrokeCap.Round)
            drawCircle(needle, tw * 0.75f, polar(c, tr, a))
            drawCircle(C.BgDeep, tw * 0.3f, polar(c, tr, a))
            drawCircle(needle, r * 0.07f, c)
            drawCircle(C.BgDeep, r * 0.03f, c)
        }
        // Sits in the gap of the 270 degree arc, below the 0/100 labels (which end at ~0.76 side). Sized from the dial,
        // not the window, so a small dial in a narrow portrait window cannot push the readout into the labels.
        val d = LocalDims.current
        Column(
            Modifier.fillMaxWidth().padding(top = (side * 0.77f).dp).height((side * 0.19f).dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Txt("$unit ${v.current}%".trim(), minOf(52f, side * 0.075f / d.s), color = if (ok) C.Gold else C.Text, weight = FontWeight.Bold, maxLines = 1)
            Txt("목표 ${v.target}%", minOf(28f, side * 0.042f / d.s), color = C.Gold, maxLines = 1)
        }
    } }
}

@Composable
fun MorseView(v: PuzzleVisual.Morse, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    val lit = v.entered.isNotEmpty()
    val lamp = @Composable { m: Modifier ->
        SquareBox(m) {
            Canvas(Modifier.fillMaxSize(0.9f).align(Alignment.Center)) {
                val r = size.minDimension / 2f
                drawCircle(Brush.radialGradient(listOf(C.Amber.copy(alpha = if (lit) 0.45f else 0.18f), Color.Transparent), center, r), r)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE2A8), C.Amber, Color(0xFF8A4B14)), center, r * 0.42f), r * 0.42f)
                drawCircle(C.Line, r * 0.5f, style = Stroke(r * 0.03f))
            }
        }
    }
    val signals = @Composable { m: Modifier ->
        Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.fillMaxWidth().height(d.dp(90))) {
                val n = v.pattern.length.coerceAtLeast(1)
                val slot = min(size.width / n, size.height * 2.2f)
                val x0 = (size.width - slot * n) / 2f
                val h = min(size.height * 0.5f, slot * 0.3f)
                v.pattern.forEachIndexed { i, ch ->
                    val cx = x0 + slot * (i + 0.5f)
                    val cy = size.height / 2f
                    val done = i < v.entered.length
                    val col = when {
                        done && v.entered[i] == ch -> C.Gold
                        done -> C.Amber
                        i == v.entered.length -> C.Text
                        else -> C.Faint
                    }
                    val style = if (done) androidx.compose.ui.graphics.drawscope.Fill else Stroke(h * 0.16f)
                    if (ch == '-') {
                        val w = slot * 0.66f
                        drawRoundRect(col, Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(h / 2), style = style)
                    } else drawCircle(col, h * 0.6f, Offset(cx, cy), style = style)
                }
            }
            Spacer(Modifier.height(d.dp(12)))
            Txt("점은 짧게, 선은 길게 · ${v.entered.length} / ${v.pattern.length}", 26f, color = C.Sub, align = TextAlign.Center)
        }
    }
    // Portrait is short on height: lamp and signals side by side so the lamp keeps its size.
    if (d.land) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        lamp(Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(d.dp(24)))
        signals(Modifier.fillMaxWidth())
    } else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        lamp(Modifier.weight(1f).fillMaxHeight())
        signals(Modifier.weight(1f))
    }
}

@Composable
fun SequenceView(v: PuzzleVisual.Sequence, modifier: Modifier = Modifier, memory: Boolean = false) {
    val d = LocalDims.current
    val tm = rememberTextMeasurer()
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
    val next = v.target.getOrNull(v.entered.size)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val n = v.target.size.coerceAtLeast(1)
            val chip = min(min(maxWidth.value / (n * 1.18f + 0.15f), maxHeight.value * 0.6f), 200f)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dp(chip * 0.18f)), verticalAlignment = Alignment.CenterVertically) {
                    v.target.forEachIndexed { i, sym ->
                        val done = i < v.entered.size
                        val right = v.entered.getOrNull(i) == sym
                        val current = i == v.entered.size
                        Canvas(Modifier.size(Dp(chip * if (current) 1.15f else 1f))) {
                            val cr = CornerRadius(size.width * 0.2f)
                            val (fill, edge, mark) = when {
                                done && right -> Triple(C.Gold, C.Gold, C.OnGold)
                                done -> Triple(C.Amber, C.Amber, C.OnGold)
                                current -> Triple(C.Surface2, C.Gold.copy(alpha = pulse), C.Text)
                                else -> Triple(C.Surface, C.Line, C.Faint.copy(alpha = 0.6f))
                            }
                            drawRoundRect(fill, cornerRadius = cr)
                            drawRoundRect(edge, cornerRadius = cr, style = Stroke(size.width * (if (current) 0.06f else 0.02f)))
                            if (sym == "?") label(tm, "?", center, size.width * 0.5f, mark, bold = true)
                            else chevron(sym.uppercase().startsWith("L"), mark)
                        }
                    }
                }
                Spacer(Modifier.height(d.dp(28)))
                if (next != null && !memory) Txt("다음: ${if (next.uppercase().startsWith("L")) "왼쪽" else "오른쪽"}", 36f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
                Txt("${v.entered.size} / ${v.target.size}", 28f, color = C.Sub)
            }
        }
    }
}

private fun DrawScope.chevron(left: Boolean, color: Color) {
    val w = size.width
    val s = if (left) -1f else 1f
    val p = Path().apply {
        moveTo(w / 2 - s * w * 0.12f, w * 0.26f)
        lineTo(w / 2 + s * w * 0.14f, w / 2)
        lineTo(w / 2 - s * w * 0.12f, w * 0.74f)
    }
    drawPath(p, color, style = Stroke(w * 0.09f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/**
 * Level gauge (seat/steering heat, wiper count). Every cell carries its number; the target cell is a dashed
 * gold outline with a faint hatch and "목표", so it never reads as "selected". The car part sits beside it.
 */
@Composable
fun LevelView(v: PuzzleVisual.Level, input: VehicleInput?, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    val max = v.max.coerceAtLeast(1)
    val tm = rememberTextMeasurer()
    val unit = if (input == VehicleInput.WIPER) "번" else "단"
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            input?.let { InputIcon(it, d.dp(120), if (v.current > 0) C.Amber else C.Sub); Spacer(Modifier.width(d.dp(40))) }
            Canvas(Modifier.fillMaxHeight().width(d.dp(300))) {
                val gap = size.height * 0.04f
                val seg = min((size.height - gap * (max - 1)) / max, d.dp(160).toPx())
                val gh = seg * max + gap * (max - 1)
                val gw = size.width
                val y0 = (size.height - gh) / 2f
                val cr = CornerRadius(gw * 0.08f)
                val dash = PathEffect.dashPathEffect(floatArrayOf(gw * 0.05f, gw * 0.035f))
                for (i in 0 until max) {
                    val y = y0 + gh - (i + 1) * seg - i * gap
                    val on = i < v.current
                    val target = i == v.target - 1
                    val tl = Offset(0f, y)
                    val sz = Size(gw, seg)
                    if (on) drawRoundRect(androidx.compose.ui.graphics.lerp(C.Gold, C.Amber, (i + 1f) / max), tl, sz, cr)
                    else {
                        drawRoundRect(C.Surface, tl, sz, cr)
                        if (target) {
                            clipRect(0f, y, gw, y + seg) {
                                var x = -seg
                                while (x < gw) { drawLine(C.Gold.copy(alpha = 0.18f), Offset(x, y + seg), Offset(x + seg, y), gw * 0.012f); x += gw * 0.07f }
                            }
                        }
                        drawRoundRect(if (target) C.Gold else C.Line, tl, sz, cr, style = Stroke(if (target) gw * 0.018f else gw * 0.01f, pathEffect = dash))
                    }
                    val txt = if (target) "${i + 1}$unit 목표" else "${i + 1}"
                    label(tm, txt, Offset(gw / 2, y + seg / 2), min(seg * 0.32f, gw * 0.12f), if (on) C.OnGold else if (target) C.Gold else C.Faint, bold = target || on)
                }
            }
        }
        Spacer(Modifier.height(d.dp(16)))
        Txt("현재 ${v.current}$unit · 목표 ${v.target}$unit", 30f, color = if (v.current == v.target) C.Gold else C.Sub, weight = FontWeight.SemiBold)
    }
}

/**
 * Top-down car, nose to the right, driver on the top (left-hand drive). The part [input] uses glows gold
 * and flashes whenever the car reports a change to it, so the screen shows the car answered.
 */
@Composable
fun CarMap(input: VehicleInput, vehicle: VehicleState, width: Dp) {
    val key: Any = when (input) {
        VehicleInput.WINDOW -> vehicle.windowDriverPercent
        VehicleInput.READING_LIGHT -> vehicle.readingLightOn
        VehicleInput.TURN_SIGNAL -> vehicle.turnSignal
        VehicleInput.STEERING_HEAT -> vehicle.steeringHeatLevel
        VehicleInput.SEAT_HEAT -> vehicle.seatHeatDriverLevel
        VehicleInput.WIPER -> vehicle.wiperOn
        VehicleInput.SEATBELT -> vehicle.belts
        VehicleInput.PASSENGER_WINDOW -> vehicle.windowPercent(CarWindow.ROW_1_RIGHT)
        VehicleInput.REAR_WINDOW -> vehicle.windows
    }
    val flash = remember { Animatable(0f) }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first.value) { first.value = false; return@LaunchedEffect }
        flash.snapTo(1f); flash.animateTo(0f, tween(800))
    }
    Canvas(Modifier.size(width, width * 0.5f)) {
        val w = size.width
        val h = size.height
        val f = flash.value
        val faint = C.Faint.copy(alpha = 0.55f)
        val gold = androidx.compose.ui.graphics.lerp(C.Gold, Color.White, f * 0.6f)
        val line = Stroke(h * 0.035f, cap = StrokeCap.Round)
        fun glow(at: Offset, r: Float) = drawCircle(Brush.radialGradient(listOf(C.Gold.copy(alpha = 0.35f + 0.5f * f), Color.Transparent), at, r), r, at)
        // wheels, body, glass, seats, steering wheel
        listOf(0.16f, 0.7f).forEach { x -> listOf(0f, 0.82f).forEach { y -> drawRoundRect(C.Faint, Offset(w * x, h * y), Size(w * 0.14f, h * 0.18f), CornerRadius(h * 0.05f)) } }
        drawRoundRect(C.Surface2, Offset(w * 0.02f, h * 0.08f), Size(w * 0.96f, h * 0.84f), CornerRadius(h * 0.3f))
        drawRoundRect(C.Sub, Offset(w * 0.02f, h * 0.08f), Size(w * 0.96f, h * 0.84f), CornerRadius(h * 0.3f), style = Stroke(h * 0.025f))
        val ws = Path().apply { moveTo(w * 0.66f, h * 0.2f); lineTo(w * 0.76f, h * 0.26f); lineTo(w * 0.76f, h * 0.74f); lineTo(w * 0.66f, h * 0.8f); close() }
        drawPath(ws, C.Surface)
        drawPath(Path().apply { moveTo(w * 0.16f, h * 0.24f); lineTo(w * 0.1f, h * 0.3f); lineTo(w * 0.1f, h * 0.7f); lineTo(w * 0.16f, h * 0.76f); close() }, C.Surface)
        listOf(0.2f, 0.56f).forEach { y -> listOf(0.44f, 0.24f).forEach { x -> drawRoundRect(faint, Offset(w * x, h * y), Size(w * 0.12f, h * 0.24f), CornerRadius(h * 0.05f), style = Stroke(h * 0.02f)) } }
        drawCircle(faint, h * 0.1f, Offset(w * 0.6f, h * 0.32f), style = Stroke(h * 0.02f))
        when (input) {
            VehicleInput.WINDOW -> { glow(Offset(w * 0.53f, h * 0.08f), h * 0.4f); drawLine(gold, Offset(w * 0.42f, h * 0.08f), Offset(w * 0.64f, h * 0.08f), h * 0.07f, StrokeCap.Round) }
            VehicleInput.READING_LIGHT -> { glow(Offset(w * 0.52f, h * 0.5f), h * 0.42f); drawCircle(gold, h * 0.08f, Offset(w * 0.52f, h * 0.5f)) }
            VehicleInput.TURN_SIGNAL -> listOf(Offset(w * 0.95f, h * 0.16f), Offset(w * 0.95f, h * 0.84f), Offset(w * 0.05f, h * 0.16f), Offset(w * 0.05f, h * 0.84f))
                .forEach { glow(it, h * 0.24f); drawCircle(gold, h * 0.06f, it) }
            VehicleInput.STEERING_HEAT -> { glow(Offset(w * 0.6f, h * 0.32f), h * 0.36f); drawCircle(gold, h * 0.1f, Offset(w * 0.6f, h * 0.32f), style = line) }
            VehicleInput.SEAT_HEAT -> { glow(Offset(w * 0.5f, h * 0.32f), h * 0.4f); drawRoundRect(gold, Offset(w * 0.44f, h * 0.2f), Size(w * 0.12f, h * 0.24f), CornerRadius(h * 0.05f)) }
            VehicleInput.WIPER -> {
                glow(Offset(w * 0.72f, h * 0.5f), h * 0.45f)
                drawLine(gold, Offset(w * 0.72f, h * 0.74f), Offset(w * 0.69f, h * 0.36f), h * 0.04f, StrokeCap.Round)
                drawLine(gold, Offset(w * 0.72f, h * 0.5f), Offset(w * 0.69f, h * 0.18f), h * 0.04f, StrokeCap.Round)
            }
            VehicleInput.PASSENGER_WINDOW -> { glow(Offset(w * 0.53f, h * 0.92f), h * 0.4f); drawLine(gold, Offset(w * 0.42f, h * 0.92f), Offset(w * 0.64f, h * 0.92f), h * 0.07f, StrokeCap.Round) }
            VehicleInput.REAR_WINDOW -> listOf(0.08f, 0.92f).forEach { y ->
                glow(Offset(w * 0.3f, h * y), h * 0.3f); drawLine(gold, Offset(w * 0.22f, h * y), Offset(w * 0.38f, h * y), h * 0.07f, StrokeCap.Round)
            }
            VehicleInput.SEATBELT -> listOf(0.2f, 0.56f).forEach { y -> listOf(0.44f, 0.24f).forEach { x ->
                glow(Offset(w * (x + 0.06f), h * (y + 0.12f)), h * 0.2f)
                drawLine(gold, Offset(w * x, h * y), Offset(w * (x + 0.12f), h * (y + 0.24f)), h * 0.04f, StrokeCap.Round)
            } }
        }
    }
}

/** Charge ring: SoC arc plus a gold tick at the target. */
@Composable
fun SocRing(soc: Float, target: Int, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.minDimension * 0.08f
            val r = this.size.minDimension / 2 - w
            val tl = Offset(center.x - r, center.y - r)
            drawCircle(C.Surface2, r, style = Stroke(w))
            drawArc(Brush.sweepGradient(listOf(C.Gold, C.Amber, C.Gold)), -90f, 360f * soc / 100f, false, tl, Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
            val a = -90f + 360f * target / 100f
            drawLine(C.Gold, polar(center, r - w, a), polar(center, r + w, a), w * 0.3f)
        }
        Txt("${soc.toInt()}%", 40f, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** Thin horizontal charge bar with target marker. */
@Composable
fun SocBar(soc: Float, target: Int, modifier: Modifier = Modifier) = Canvas(modifier) {
    val h = size.height
    val cr = CornerRadius(h / 2)
    drawRoundRect(C.Surface2, cornerRadius = cr)
    drawRoundRect(Brush.horizontalGradient(listOf(C.Gold, C.Amber)), size = Size(size.width * soc.coerceIn(0f, 100f) / 100f, h), cornerRadius = cr)
    val tx = size.width * target / 100f
    drawLine(C.Text, Offset(tx, -h * 0.4f), Offset(tx, h * 1.4f), h * 0.25f)
}

/** Brand mark: keyhole inside a ring. */
@Composable
fun LogoMark(size: Dp) = Canvas(Modifier.size(size)) {
    val r = this.size.minDimension / 2
    drawCircle(Brush.sweepGradient(listOf(C.Gold, C.Amber, C.Gold)), r * 0.92f, style = Stroke(r * 0.1f))
    drawCircle(C.Gold, r * 0.2f, Offset(center.x, center.y - r * 0.12f))
    drawPath(Path().apply {
        moveTo(center.x - r * 0.1f, center.y - r * 0.05f); lineTo(center.x + r * 0.1f, center.y - r * 0.05f)
        lineTo(center.x + r * 0.17f, center.y + r * 0.42f); lineTo(center.x - r * 0.17f, center.y + r * 0.42f); close()
    }, C.Gold)
}

/** Line pictogram for a car part. Drawn, not emoji, so it stays crisp and on-palette. */
@Composable
fun InputIcon(input: VehicleInput, size: Dp, color: Color = C.Gold) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val st = Stroke(w * 0.07f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    fun heat(x0: Float, y0: Float, h: Float) = repeat(3) { k ->
        val x = x0 + k * w * 0.16f
        drawPath(Path().apply { moveTo(x, y0); cubicTo(x - w * 0.06f, y0 - h * 0.33f, x + w * 0.06f, y0 - h * 0.66f, x, y0 - h) }, color, style = st)
    }
    when (input) {
        VehicleInput.WINDOW, VehicleInput.PASSENGER_WINDOW, VehicleInput.REAR_WINDOW -> {
            drawPath(Path().apply { moveTo(w * 0.15f, w * 0.62f); lineTo(w * 0.32f, w * 0.2f); lineTo(w * 0.85f, w * 0.2f); lineTo(w * 0.85f, w * 0.62f); close() }, color, style = st)
            drawLine(color, Offset(w * 0.55f, w * 0.68f), Offset(w * 0.55f, w * 0.9f), st.width, StrokeCap.Round)
            drawPath(Path().apply { moveTo(w * 0.45f, w * 0.8f); lineTo(w * 0.55f, w * 0.9f); lineTo(w * 0.65f, w * 0.8f) }, color, style = st)
        }
        VehicleInput.READING_LIGHT -> {
            drawArc(color, 180f, 180f, true, Offset(w * 0.25f, w * 0.12f), Size(w * 0.5f, w * 0.5f))
            listOf(-0.3f, 0f, 0.3f).forEach { dx -> drawLine(color, Offset(w * (0.5f + dx * 0.5f), w * 0.5f), Offset(w * (0.5f + dx), w * 0.88f), st.width, StrokeCap.Round) }
        }
        VehicleInput.TURN_SIGNAL -> {
            drawPath(Path().apply { moveTo(w * 0.08f, w * 0.5f); lineTo(w * 0.4f, w * 0.24f); lineTo(w * 0.4f, w * 0.76f); close() }, color)
            drawPath(Path().apply { moveTo(w * 0.92f, w * 0.5f); lineTo(w * 0.6f, w * 0.24f); lineTo(w * 0.6f, w * 0.76f); close() }, color)
        }
        VehicleInput.STEERING_HEAT -> {
            drawCircle(color, w * 0.3f, Offset(w * 0.5f, w * 0.6f), style = st)
            drawCircle(color, w * 0.07f, Offset(w * 0.5f, w * 0.6f))
            drawLine(color, Offset(w * 0.2f, w * 0.6f), Offset(w * 0.8f, w * 0.6f), st.width)
            drawLine(color, Offset(w * 0.5f, w * 0.6f), Offset(w * 0.5f, w * 0.9f), st.width)
            heat(w * 0.34f, w * 0.24f, w * 0.18f)
        }
        VehicleInput.SEAT_HEAT -> {
            drawPath(Path().apply {
                moveTo(w * 0.22f, w * 0.12f); lineTo(w * 0.3f, w * 0.62f); lineTo(w * 0.72f, w * 0.62f)
                lineTo(w * 0.72f, w * 0.78f); lineTo(w * 0.28f, w * 0.78f)
            }, color, style = st)
            heat(w * 0.48f, w * 0.48f, w * 0.3f)
        }
        VehicleInput.WIPER -> {
            drawArc(color, 200f, 140f, false, Offset(w * 0.08f, w * 0.2f), Size(w * 0.84f, w * 0.84f), style = st)
            drawLine(color, Offset(w * 0.5f, w * 0.82f), Offset(w * 0.28f, w * 0.3f), st.width, StrokeCap.Round)
            drawCircle(color, w * 0.06f, Offset(w * 0.5f, w * 0.82f))
        }
        VehicleInput.SEATBELT -> {
            drawLine(color, Offset(w * 0.2f, w * 0.12f), Offset(w * 0.56f, w * 0.56f), st.width, StrokeCap.Round)
            drawRoundRect(color, Offset(w * 0.46f, w * 0.52f), Size(w * 0.36f, w * 0.28f), CornerRadius(w * 0.06f), style = st)
            drawLine(color, Offset(w * 0.64f, w * 0.8f), Offset(w * 0.64f, w * 0.94f), st.width, StrokeCap.Round)
        }
    }
}

/** 기억 금고: the arrows while they show, then "?" slots that fill as the player answers. */
@Composable
fun MemoryView(v: PuzzleVisual.Memory, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    val showing = v.revealLeftMs > 0
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Pill(if (showing) "잘 보고 외워요 · ${(v.revealLeftMs + 999) / 1000}초" else "이제 똑같이 켜 보세요", if (showing) C.Amber else C.Gold)
        Spacer(Modifier.height(d.dp(12)))
        val shown = if (showing) v.target else v.entered + v.target.drop(v.entered.size) // hidden: target is all "?"
        SequenceView(PuzzleVisual.Sequence(shown, if (showing) emptyList() else v.entered), Modifier.weight(1f).fillMaxWidth(), memory = true)
    }
}

/** 수수께끼 금고: the riddle on a card, the live value as cells. Wiper max is the answer, so it shows a plain count. */
@Composable
fun RiddleView(v: PuzzleVisual.Riddle, input: VehicleInput?, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    val wiper = input == VehicleInput.WIPER
    val unit = when { wiper -> "번"; input?.isWindow == true -> "칸"; else -> "단" }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Panel(Modifier.fillMaxWidth(), highlight = true) {
            Column(Modifier.fillMaxWidth().padding(d.dp(32)), horizontalAlignment = Alignment.CenterHorizontally) {
                Pill("수수께끼")
                Spacer(Modifier.height(d.dp(16)))
                Txt(v.question, 38f, weight = FontWeight.SemiBold, align = TextAlign.Center, maxLines = 4)
            }
        }
        Spacer(Modifier.height(d.dp(32)))
        if (!wiper) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = v.max.coerceAtLeast(1)
            val cell = min(maxWidth.value / (n * 1.25f), 80f)
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(Dp(cell * 0.25f))) {
                repeat(n) { i ->
                    val on = i < v.current
                    Box(Modifier.size(Dp(cell)).clip(RoundedCornerShape(Dp(cell * 0.22f))).background(if (on) C.Gold else C.Surface).border(1.dp, if (on) C.Gold else C.Line, RoundedCornerShape(Dp(cell * 0.22f))))
                }
            }
        }
        Spacer(Modifier.height(d.dp(20)))
        Txt(if (input?.isWindow == true) "10칸 중 지금 ${v.current}칸" else "지금 ${v.current}$unit", 34f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** 연쇄 금고: numbered step chips on top, the active step's own visual below. */
@Composable
fun ChainView(v: PuzzleVisual.Chain, input: VehicleInput?, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.dp(12))) {
            v.chips.forEachIndexed { i, c ->
                val done = i < v.done
                val cur = i == v.done
                val shape = RoundedCornerShape(d.dp(18))
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).background(if (done) C.Gold else C.Surface2)
                        .border(if (cur) 3.dp else 1.dp, if (cur || done) C.Gold else C.Line, shape).padding(horizontal = d.dp(12), vertical = d.dp(10)),
                    contentAlignment = Alignment.Center,
                ) { Txt("${i + 1}. $c", 24f, color = if (done) C.OnGold else if (cur) C.Text else C.Faint, weight = FontWeight.SemiBold, align = TextAlign.Center, maxLines = 2) }
            }
        }
        Spacer(Modifier.height(d.dp(20)))
        PuzzleVisualView(v.current, input, Modifier.weight(1f).fillMaxWidth())
    }
}

// Top view, nose up, left-hand drive. Fractions of the body box. ROW_2_CENTER is narrower (7-seat bench).
private val SeatAt = mapOf(
    CarSeat.ROW_1_LEFT to Offset(0.3f, 0.3f), CarSeat.ROW_1_RIGHT to Offset(0.7f, 0.3f),
    CarSeat.ROW_2_LEFT to Offset(0.26f, 0.53f), CarSeat.ROW_2_CENTER to Offset(0.5f, 0.53f), CarSeat.ROW_2_RIGHT to Offset(0.74f, 0.53f),
    CarSeat.ROW_3_LEFT to Offset(0.32f, 0.76f), CarSeat.ROW_3_RIGHT to Offset(0.68f, 0.76f),
)

/** 찰칵: 3-row top view. Target seats dashed gold (numbered in a relay), clicked seats solid gold, buckled ones show a strap. */
@Composable
fun SeatsView(v: PuzzleVisual.Seats, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    val tm = rememberTextMeasurer()
    val pulse = rememberInfiniteTransition(label = "seat").animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "seat").value
    val next = if (v.ordered) v.target.getOrNull(v.done.size) else null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val bw = min(size.width, size.height * 0.62f)
            val bh = bw / 0.62f
            val o = Offset((size.width - bw) / 2f, (size.height - bh) / 2f)
            drawRoundRect(C.Surface2, o, Size(bw, bh), CornerRadius(bw * 0.28f))
            drawRoundRect(C.Sub, o, Size(bw, bh), CornerRadius(bw * 0.28f), style = Stroke(bw * 0.012f))
            drawArc(C.Surface, 200f, 140f, false, Offset(o.x + bw * 0.12f, o.y + bh * 0.06f), Size(bw * 0.76f, bh * 0.22f), style = Stroke(bw * 0.04f))
            val minPx = 16.sp.toPx() // Pleos text floor, also on the canvas
            label(tm, "앞", Offset(o.x + bw / 2, o.y + bh * 0.06f), max(bw * 0.09f, minPx), C.Sub)
            val dash = PathEffect.dashPathEffect(floatArrayOf(bw * 0.025f, bw * 0.018f))
            SeatAt.forEach { (seat, f) ->
                val sw = bw * (if (seat == CarSeat.ROW_2_CENTER) 0.16f else 0.2f)
                val sh = bh * 0.15f
                val tl = Offset(o.x + bw * f.x - sw / 2, o.y + bh * f.y - sh / 2)
                val cr = CornerRadius(sw * 0.22f)
                val done = seat in v.done
                val idx = v.target.indexOf(seat)
                when {
                    done -> drawRoundRect(C.Gold, tl, Size(sw, sh), cr)
                    idx >= 0 -> {
                        drawRoundRect(C.Gold.copy(alpha = 0.14f), tl, Size(sw, sh), cr)
                        drawRoundRect(C.Gold.copy(alpha = if (seat == next) pulse else 1f), tl, Size(sw, sh), cr, style = Stroke(bw * (if (seat == next) 0.018f else 0.01f), pathEffect = dash))
                    }
                    else -> drawRoundRect(C.Faint.copy(alpha = 0.6f), tl, Size(sw, sh), cr, style = Stroke(bw * 0.008f))
                }
                if (seat in v.buckled && !done) drawLine(C.Sub, tl + Offset(sw * 0.2f, sh * 0.15f), tl + Offset(sw * 0.8f, sh * 0.85f), bw * 0.014f, StrokeCap.Round)
                val mark = when { done -> "찰칵"; v.ordered && idx >= 0 -> "${idx + 1}"; else -> null }
                mark?.let { label(tm, it, tl + Offset(sw / 2, sh / 2), max(minPx, if (done) min(sh * 0.34f, sw * 0.27f) else min(sh * 0.5f, sw * 0.55f)), if (done) C.OnGold else C.Gold, bold = true) }
            }
        }
        Spacer(Modifier.height(d.dp(16)))
        if (next != null) Txt("다음: ${next.label}", 34f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
        Txt("찰칵 ${v.done.size} / ${v.need}", 28f, color = C.Sub, maxLines = 1)
    }
}

/** 쌍둥이 창문: two panes side by side, glass drops as the window opens. Band = allowed range, link line = the gap. */
@Composable
fun TwinView(v: PuzzleVisual.Twin, input: VehicleInput?, modifier: Modifier = Modifier) {
    val d = LocalDims.current
    val tm = rememberTextMeasurer()
    val inRange = v.left in v.min..v.max && v.right in v.min..v.max
    val ok = inRange && abs(v.left - v.right) <= v.tolerance
    val other = if (input == VehicleInput.REAR_WINDOW) "2열" else "조수석"
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val pw = min(size.width * 0.34f, size.height * 0.5f)
            val top = size.height * 0.1f
            val ph = size.height * 0.78f
            val gap = pw * 0.5f
            val x0 = (size.width - pw * 2 - gap) / 2f
            val levels = listOf(v.left, v.right)
            val ys = levels.map { top + ph * it.coerceIn(0, 100) / 100f }
            levels.forEachIndexed { i, pct ->
                val x = x0 + i * (pw + gap)
                val cr = CornerRadius(pw * 0.1f)
                drawRoundRect(C.BgDeep, Offset(x, top), Size(pw, ph), cr)
                drawRect(C.Gold.copy(alpha = 0.10f), Offset(x, top + ph * v.min / 100f), Size(pw, ph * (v.max - v.min) / 100f))
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A5A86), Color(0xFF1C2E4A)), ys[i], top + ph), Offset(x, ys[i]), Size(pw, top + ph - ys[i]), cr)
                drawRoundRect(C.Sub, Offset(x, top), Size(pw, ph), cr, style = Stroke(pw * 0.03f))
                drawLine(if (ok) C.Gold else C.Amber, Offset(x, ys[i]), Offset(x + pw, ys[i]), pw * 0.05f, StrokeCap.Round)
                label(tm, if (i == 0) "운전석" else other, Offset(x + pw / 2, top / 2), min(top * 0.6f, pw * 0.16f), C.Sub, bold = true)
                label(tm, "$pct%", Offset(x + pw / 2, top + ph + top * 0.6f), min(top * 0.7f, pw * 0.18f), if (ok) C.Gold else C.Text, bold = true)
            }
            drawLine(if (ok) C.Gold else C.Amber, Offset(x0 + pw, ys[0]), Offset(x0 + pw + gap, ys[1]), pw * 0.025f, StrokeCap.Round,
                pathEffect = if (ok) null else PathEffect.dashPathEffect(floatArrayOf(pw * 0.06f, pw * 0.04f)))
        }
        Spacer(Modifier.height(d.dp(16)))
        val msg = when {
            ok -> "똑같아요! 그대로 잠깐 기다려요"
            !inRange -> "두 창문 모두 ${v.min}%에서 ${v.max}% 사이로"
            else -> "높이 차이 ${abs(v.left - v.right)}%"
        }
        Txt(msg, 30f, color = if (ok) C.Gold else C.Sub, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 번개 금고 countdown: ring empties toward 0. total = seconds when the puzzle began. */
@Composable
fun BonusRing(secLeft: Int, total: Int, size: Dp) {
    val out = secLeft <= 0
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.minDimension * 0.1f
            val r = this.size.minDimension / 2 - w
            drawCircle(C.Surface2, r, style = Stroke(w))
            drawArc(C.Amber, -90f, 360f * secLeft / total.coerceAtLeast(1), false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
        }
        Txt(if (out) "끝" else "$secLeft", 26f, color = if (out) C.Faint else C.Amber, weight = FontWeight.Bold, maxLines = 1)
    }
}
