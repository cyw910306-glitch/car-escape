package com.carescape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carescape.core.CarSeat
import com.carescape.core.CarWindow
import com.carescape.core.DriveMode
import com.carescape.core.Gear
import com.carescape.core.TurnSignal
import com.carescape.core.VehicleState
import kotlin.math.min
import kotlin.math.roundToInt

/** Demo panel wiring. update = SimVehicleGateway::update. Hidden in a real car (showDemo = false). */
data class DemoControls(
    val update: ((VehicleState) -> VehicleState) -> Unit = {},
    val timelapse: Boolean = false,
    val onTimelapse: (Boolean) -> Unit = {},
    val initiallyOpen: Boolean = false,
)

private val Shape = RoundedCornerShape(14.dp)

/**
 * Demo panel: its own strip next to the game (landscape: right column, portrait: bottom sheet), never on top of it.
 * Closed, it shrinks to a 64dp rail with an open button.
 */
@Composable
fun DemoPanel(v: VehicleState, demo: DemoControls, open: Boolean, onOpen: (Boolean) -> Unit, modifier: Modifier = Modifier) =
    BoxWithConstraints(modifier.background(C.Surface)) {
        val land = maxHeight > maxWidth // tall strip = landscape side panel (or its closed rail)
        if (!open) {
            Box(Modifier.fillMaxSize().clickable(role = Role.Button) { onOpen(true) }, contentAlignment = Alignment.Center) {
                Text(if (land) "‹\n시\n연" else "시연 패널 열기 ˄", color = C.Gold, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            return@BoxWithConstraints
        }
        val up = demo.update
        val items: List<@Composable () -> Unit> = listOf(
            { Item("기어") { Seg(listOf("P", "D"), if (v.gear == Gear.PARK) 0 else 1) { i -> up { it.copy(gear = if (i == 0) Gear.PARK else Gear.DRIVE) } } } },
            {
                Item("주행 모드") {
                    Seg(listOf("모름", "노멀", "에코", "스포츠"), v.driveMode?.let { it.ordinal + 1 } ?: 0) { i ->
                        up { it.copy(driveMode = if (i == 0) null else DriveMode.entries[i - 1]) }
                    }
                }
            },
            { Item("속도 ${v.speedKmh.roundToInt()}km/h") { Sld(v.speedKmh, 0f..60f) { x -> up { it.copy(speedKmh = x) } } } },
            { Item("배터리 ${v.socPercent.roundToInt()}%") { Sld(v.socPercent, 0f..100f) { x -> up { it.copy(socPercent = x) } } } },
            { Toggle("충전 중", v.charging) { b -> up { it.copy(charging = b) } } },
            { Toggle("타임랩스 충전 x30", demo.timelapse, demo.onTimelapse) },
            { Item("운전석 창문 ${v.windowDriverPercent}%") { Sld(v.windowDriverPercent.toFloat(), 0f..100f) { x -> up { it.copy(windowDriverPercent = x.roundToInt()) } } } },
            { WinSlider("조수석 창문", CarWindow.ROW_1_RIGHT, v, up) },
            { WinSlider("2열 오른쪽 창문", CarWindow.ROW_2_RIGHT, v, up) },
            { Item("독서등") { Hold(v.readingLightOn) { on -> up { it.copy(readingLightOn = on) } } } },
            {
                Item("깜빡이") {
                    val sel = when (v.turnSignal) { TurnSignal.LEFT -> 0; TurnSignal.RIGHT -> 2; else -> 1 }
                    Seg(listOf("좌", "끔", "우"), sel) { i -> up { it.copy(turnSignal = listOf(TurnSignal.LEFT, TurnSignal.OFF, TurnSignal.RIGHT)[i]) } }
                }
            },
            { Item("핸들 열선") { Seg(listOf("0", "1", "2", "3"), v.steeringHeatLevel) { i -> up { it.copy(steeringHeatLevel = i) } } } },
            { Item("시트 열선") { Seg(listOf("0", "1", "2", "3"), v.seatHeatDriverLevel) { i -> up { it.copy(seatHeatDriverLevel = i) } } } },
            { Toggle("와이퍼", v.wiperOn) { b -> up { it.copy(wiperOn = b) } } },
            { Belts(v, up) },
            // v3 cards (Virtual Console: Charge Port, Door Position, Window Lock, Remaining Range)
            { Toggle("충전구 열림", v.chargePortOpen == true) { b -> up { it.copy(chargePortOpen = b) } } },
            { Toggle("문 열림", v.doorOpen) { b -> up { it.copy(doorOpen = b) } } },
            { Toggle("문 잠금", v.doorLocked == true) { b -> up { it.copy(doorLocked = b) } } },
            { Toggle("2열 창문 잠금", v.rearWindowLocked) { b -> up { it.copy(rearWindowLocked = b) } } },
            { Toggle("주행 가능 거리 ${v.rangeMeters?.let { "${(it / 1000).roundToInt()}km" } ?: "모름"}", v.rangeMeters != null) { b ->
                up { it.copy(rangeMeters = if (b) it.socPercent * 5_000f else null) } } }, // same 500 km at 100 % as SIM_METERS_PER_PERCENT
        )
        Column(Modifier.fillMaxSize().border(1.dp, C.Line).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(Shape).background(C.Surface2).border(1.dp, C.Line, Shape).clickable(role = Role.Button) { onOpen(false) },
                    contentAlignment = Alignment.Center,
                ) { Text(if (land) "›" else "˅", color = C.Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text("시연 패널", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("심사 시연용 가상 차량 입력", color = C.Sub, fontSize = 15.sp)
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (land) items.forEach { it() }
                else items.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        pair.forEach { Box(Modifier.weight(1f)) { it() } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

@Composable
private fun WinSlider(name: String, w: CarWindow, v: VehicleState, up: ((VehicleState) -> VehicleState) -> Unit) =
    Item("$name ${v.windowPercent(w)}%") { Sld(v.windowPercent(w).toFloat(), 0f..100f) { x -> up { it.copy(windows = it.windows + (w to x.roundToInt())) } } }

/** Seat belts as a 3-row seat map (2 / 3 / 2). Tap = buckle or unbuckle that seat. */
@Composable
private fun Belts(v: VehicleState, up: ((VehicleState) -> VehicleState) -> Unit) = Item("안전벨트 (탭 = 찰칵 / 풀기)") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            listOf(CarSeat.ROW_1_LEFT, CarSeat.ROW_1_RIGHT),
            listOf(CarSeat.ROW_2_LEFT, CarSeat.ROW_2_CENTER, CarSeat.ROW_2_RIGHT),
            listOf(CarSeat.ROW_3_LEFT, CarSeat.ROW_3_RIGHT),
        ).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { seat ->
                    val on = seat in v.belts
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(Shape).background(if (on) C.Gold else C.Surface2)
                            .border(1.dp, if (on) C.Gold else C.Line, Shape)
                            .clickable(role = Role.Checkbox) { up { it.copy(belts = if (seat in it.belts) it.belts - seat else it.belts + seat) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(seat.label, color = if (on) C.OnGold else C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun Item(label: String, content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, color = C.Sub, fontSize = 16.sp)
    content()
}

@Composable
private fun Seg(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(56.dp).clip(Shape).background(if (on) C.Gold else C.Surface2)
                    .border(1.dp, if (on) C.Gold else C.Line, Shape).clickable(role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) C.OnGold else C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

@Composable
private fun Sld(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) = Slider(
    value, onChange, valueRange = range,
    colors = SliderDefaults.colors(thumbColor = C.Gold, activeTrackColor = C.Amber, inactiveTrackColor = C.Surface2),
)

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Amber, checkedThumbColor = C.OnGold, uncheckedTrackColor = C.Surface2))
    }

/** Press-and-hold: on while the finger is down, so hold duration reaches the game as-is (Morse input). */
@Composable
private fun Hold(on: Boolean, onHold: (Boolean) -> Unit) {
    val cb by rememberUpdatedState(onHold)
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(Shape).background(if (on) C.Gold else C.Surface2).border(1.dp, C.Line, Shape)
            .pointerInput(Unit) { detectTapGestures(onPress = { cb(true); tryAwaitRelease(); cb(false) }) },
        contentAlignment = Alignment.Center,
    ) { Text(if (on) "켜짐 · 손을 떼면 꺼짐" else "누르고 있는 동안 켜짐", color = if (on) C.OnGold else C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}
