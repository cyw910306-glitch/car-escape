@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.carescape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carescape.core.GameUiState
import com.carescape.core.ParkingAnchor

/** Where the 갈림길 card is. Compose state only: nothing reaches the engine before "맞아요". */
enum class ParkStep { ASK, ENTRY, CONFIRM }

/** The grown-up's 주차 암호 while it is being typed. floor null = not picked yet. */
data class ParkDraft(val step: ParkStep = ParkStep.ASK, val floor: Int? = null, val zone: String = "", val number: String = "") {
    fun anchor() = floor?.let { ParkingAnchor(it, zone, number) }
}

private val FLOORS = (-6..0).toList()
private val ZONES = listOf("A", "B", "C", "D", "E", "F", "G", "H")
private fun floorKey(f: Int) = if (f < 0) "지하 ${-f}" else "지상"

/** 갈림길: parked after a drive. One tap stays, one tap starts the 암호. */
@Composable
internal fun ParkForkCard(actions: GameActions, onLeave: () -> Unit, modifier: Modifier) {
    val d = LocalDims.current
    Panel(modifier, highlight = true) {
        Column(Modifier.padding(d.dp(44))) {
            Pill("주차했어요")
            Spacer(Modifier.height(d.dp(20)))
            Txt("어떻게 할까요?", 60f, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(d.dp(12)))
            Txt("다녀오면 다시 탔을 때 주차 암호를 함께 맞혀요", 28f, color = C.Sub)
            Spacer(Modifier.height(d.dp(if (d.compact) 24 else 40)))
            Column(verticalArrangement = Arrangement.spacedBy(d.dp(20))) {
                BigButton("다녀올게요", onLeave, Modifier.fillMaxWidth())
                BigButton("차에서 기다려요", actions.onParkStay, Modifier.fillMaxWidth(), primary = false)
            }
        }
    }
}

/** 주차 암호 남기기: floor, zone letter, number on big keys. Whole window, so every key keeps the Pleos touch size. */
@Composable
internal fun AnchorEntry(draft: ParkDraft, onChange: (ParkDraft) -> Unit, onSkip: () -> Unit) {
    val d = LocalDims.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(d.pad), verticalArrangement = Arrangement.spacedBy(d.dp(18))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("주차 암호 남기기")
            Spacer(Modifier.width(d.dp(20)))
            Txt("어른이 눌러 주세요. 아이는 듣고 외워요.", 28f, color = C.Sub, maxLines = 2)
        }
        Txt(draft.anchor()?.label() ?: "층부터 골라 주세요", if (draft.floor == null) 48f else 72f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
        KeyRow("층") { FLOORS.forEach { f -> Key(floorKey(f), draft.floor == f) { onChange(draft.copy(floor = f)) } } }
        KeyRow("구역") {
            ZONES.forEach { z -> Key(z, draft.zone == z) { onChange(draft.copy(zone = z)) } }
            Key("없음", draft.zone.isEmpty() && draft.floor != null) { onChange(draft.copy(zone = "")) }
        }
        KeyRow("번호") {
            (1..9).plus(0).forEach { n -> Key("$n", false) { if (draft.number.length < 3) onChange(draft.copy(number = draft.number + n)) } }
            Key("지우기", false) { onChange(draft.copy(number = draft.number.dropLast(1))) }
        }
        Spacer(Modifier.height(d.dp(4)))
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(20))) {
            BigButton("저장", { if (draft.floor != null) onChange(draft.copy(step = ParkStep.CONFIRM)) }, Modifier.weight(1f), enabled = draft.floor != null)
            BigButton("건너뛰기", onSkip, Modifier.weight(1f), primary = false)
        }
    }
}

@Composable
private fun KeyRow(label: String, keys: @Composable () -> Unit) {
    val d = LocalDims.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.width(d.dp(110)).heightIn(min = d.touch), contentAlignment = Alignment.CenterStart) {
            Txt(label, 28f, color = C.Sub, weight = FontWeight.SemiBold, maxLines = 1)
        }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(d.dp(12)), verticalArrangement = Arrangement.spacedBy(d.dp(12))) { keys() }
    }
}

@Composable
private fun Key(label: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDims.current
    val shape = RoundedCornerShape(d.dp(20))
    Box(
        Modifier.heightIn(min = d.touch).widthIn(min = d.touch).clip(shape).background(if (selected) C.Gold else C.Surface2)
            .border(2.dp, if (selected) C.Gold else C.Line, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = d.dp(22)),
        contentAlignment = Alignment.Center,
    ) { Txt(label, 32f, color = if (selected) C.OnGold else C.Text, weight = FontWeight.SemiBold, maxLines = 1) }
}

/** 확인 후 저장: the 암호 is kept only after "맞아요". */
@Composable
internal fun AnchorConfirm(draft: ParkDraft, onChange: (ParkDraft) -> Unit, onSave: (ParkingAnchor) -> Unit, modifier: Modifier) {
    val d = LocalDims.current
    val a = draft.anchor() ?: return
    Panel(modifier, highlight = true) {
        Column(Modifier.padding(d.dp(44))) {
            Pill("주차 암호")
            Spacer(Modifier.height(d.dp(20)))
            Txt("이렇게 남길까요?", 52f, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(d.dp(16)))
            Txt(a.label(), 72f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(d.dp(if (d.compact) 24 else 40)))
            Row(horizontalArrangement = Arrangement.spacedBy(d.dp(20))) {
                BigButton("맞아요", { onSave(a) }, Modifier.weight(1f))
                BigButton("다시 고치기", { onChange(draft.copy(step = ParkStep.ENTRY)) }, Modifier.weight(1f), primary = false)
            }
        }
    }
}

/** 다시 탔어요 (출발 금고): the family answers out loud, the grown-up taps 정답 보기. Wrong answers are fine. */
@Composable
internal fun ReturnCard(ui: GameUiState, actions: GameActions, modifier: Modifier) {
    val d = LocalDims.current
    Panel(modifier, highlight = true) {
        Column(Modifier.padding(d.dp(44))) {
            Pill("다시 탔어요")
            Spacer(Modifier.height(d.dp(20)))
            if (!ui.returnReveal) {
                Txt("우리 차 주차 암호, 누가 기억해요?", 52f, weight = FontWeight.Bold)
                Spacer(Modifier.height(d.dp(12)))
                Txt("말로 맞혀 보세요. 틀려도 괜찮아요.", 28f, color = C.Sub)
                Spacer(Modifier.height(d.dp(if (d.compact) 24 else 40)))
                Row(horizontalArrangement = Arrangement.spacedBy(d.dp(20))) {
                    BigButton("정답 보기", actions.onReturnReveal, Modifier.weight(1f))
                    BigButton("출발 준비", actions.onReturnClose, Modifier.weight(1f), primary = false)
                }
            } else {
                Txt("정답은", 30f, color = C.Sub, maxLines = 1)
                Txt(ui.parkAnchor, 72f, color = C.Gold, weight = FontWeight.Bold, maxLines = 1)
                if (ui.awayLine.isNotEmpty()) {
                    Spacer(Modifier.height(d.dp(16)))
                    Txt(ui.awayLine, 32f)
                }
                Spacer(Modifier.height(d.dp(if (d.compact) 24 else 40)))
                BigButton("출발 준비", actions.onReturnClose, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Title panel line while a 암호 is kept. Tap = ask now (cars without door lock data rely on it). */
@Composable
internal fun ParkChip(label: String, onClick: () -> Unit) {
    val d = LocalDims.current
    val shape = RoundedCornerShape(d.dp(20))
    Column(
        Modifier.fillMaxWidth().heightIn(min = d.touch).clip(shape).background(C.Surface2).border(2.dp, C.Gold, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = d.dp(28), vertical = d.dp(12)),
        verticalArrangement = Arrangement.Center,
    ) {
        Txt("주차 암호 보관 중", 22f, color = C.Gold, weight = FontWeight.SemiBold, maxLines = 1)
        Txt(label, 32f, weight = FontWeight.Bold, maxLines = 1)
    }
}
